package me.rerere.rikkahub.data.orbis.integration

import java.io.IOException
import java.io.File
import java.net.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

internal const val HUB_RESPONSE_LIMIT = 1024 * 1024
internal const val HUB_VISIBLE_MESSAGES = 500
internal const val HUB_RETRY_MILLIS = 24 * 60 * 60 * 1000L
internal const val HUB_SEND_BODY_LIMIT = 32 * 1024
internal class OrbisTechHubException(val reason: String) : IOException(reason)
internal data class HubRoom(val room: String, val description: String, val lastSeq: Long)
internal data class HubAttachment(val id: String, val filename: String, val size: Long, val kind: String,
    val mediaType: String = "application/octet-stream")
internal data class HubMessage(val seq: Long, val eventId: String, val from: String, val text: String,
    val createdAt: String, val attachment: HubAttachment? = null)
internal data class HubPage(val events: List<HubMessage>, val nextCursor: Long, val hasOlder: Boolean)

/** One human click, one UUID and immutable body. Never stores a credential. */
internal data class HubSendIntent(val room: String, val text: String, val key: String,
    val createdAtMillis: Long, val connectionRevision: Long, val attachment: HubUploadAttachment? = null) {
    fun retryAllowed(nowMillis: Long): Boolean = nowMillis >= createdAtMillis && nowMillis - createdAtMillis < HUB_RETRY_MILLIS
    companion object {
        fun create(room: String, text: String, revision: Long, nowMillis: Long = System.currentTimeMillis()): HubSendIntent {
            requireHubRoom(room)
            val clean = text.trim()
            require(clean.isNotBlank() && clean.codePointCount(0, clean.length) <= 4000) { "invalid_text" }
            return HubSendIntent(room, clean, UUID.randomUUID().toString(), nowMillis, revision)
        }
        fun createMedia(room: String, text: String, revision: Long, media: HubUploadAttachment,
            nowMillis: Long = System.currentTimeMillis()): HubSendIntent {
            requireHubRoom(room); validateHubUpload(media)
            val clean = text.trim()
            require(clean.codePointCount(0, clean.length) <= 4000) { "invalid_text" }
            return HubSendIntent(room, clean, UUID.randomUUID().toString(), nowMillis, revision, media)
        }
    }
}

internal fun requireHubRoom(room: String) { require(Regex("[A-Za-z0-9_-]{1,32}").matches(room)) { "invalid_room" } }

internal fun checkHubJson(text: String) {
    val stack = ArrayList<MutableSet<String>?>()
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{', '[' -> { require(stack.size < 16); stack.add(if (text[i] == '{') HashSet() else null) }
            '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
            '"' -> {
                val start = i++
                while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                require(i < text.length && i - start <= 65536)
                var next = i + 1
                while (next < text.length && text[next].isWhitespace()) next++
                if (next < text.length && text[next] == ':') {
                    val keys = stack.lastOrNull() ?: error("invalid_json")
                    require(keys.add(Json.parseToJsonElement(text.substring(start, i + 1)).jsonPrimitive.content))
                }
            }
        }
        i++
    }
    require(stack.isEmpty())
}

private fun hubJson(bytes: ByteArray): JsonObject {
    require(bytes.size <= HUB_RESPONSE_LIMIT)
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    checkHubJson(text)
    return Json.parseToJsonElement(text).jsonObject
}
private fun JsonObject.text(key: String, max: Int): String = getValue(key).jsonPrimitive.let {
    require(it.isString && it.content.length <= max); it.content
}
private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString); it.long.also { value -> require(value in 0..1_000_000_000_000L) }
}
private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }

private fun parseHubAttachment(value: JsonObject): HubAttachment {
    val id = value.text("id", 32); require(Regex("[0-9a-f]{32}").matches(id))
    val kind = value.text("kind", 16); require(kind == "file" || kind == "image")
    val name = requireHubFilename(value.text("filename", 200))
    val size = value.number("size")
    require(size in 1..(if (kind == "image") HUB_IMAGE_LIMIT else HUB_FILE_LIMIT))
    val mime = value["content_type"]?.let { hubMediaType(value.text("content_type", 150)) }
        ?: "application/octet-stream"
    return HubAttachment(id, name, size, kind, mime)
}

internal fun parseHubUploaded(bytes: ByteArray, expected: HubUploadAttachment): HubUploadReceipt = hubParse {
    val root = hubJson(bytes)
    val seq = root.number("seq"); require(seq > 0)
    val media = parseHubAttachment(root.getValue("attachment").jsonObject)
    require(media.filename == expected.filename && media.size == expected.size && media.mediaType == expected.mediaType)
    require((media.kind == "image") == expected.mediaType.startsWith("image/"))
    HubUploadReceipt(seq, media)
}

private fun parseHubEvent(value: JsonElement, expectedRoom: String): HubMessage {
    val event = value.jsonObject
    require(event.text("room", 32) == expectedRoom && event.text("kind", 32) == "chat")
    val seq = event.number("seq"); require(seq > 0)
    val id = event.text("event_id", 128); require(id.isNotBlank())
    val from = event.text("from", 64); require(from.isNotBlank())
    val timestamp = event.text("created_at", 80); Instant.parse(timestamp)
    val payload = event.getValue("payload").jsonObject
    val text = payload.text("text", 8000)
    val attachment = payload["attachment"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseHubAttachment)
    return HubMessage(seq, id, from, text, timestamp, attachment)
}

internal fun parseHubRooms(bytes: ByteArray): List<HubRoom> = hubParse {
    val rooms = hubJson(bytes).getValue("rooms").jsonArray
    require(rooms.size <= 1000)
    val result = rooms.map { item -> item.jsonObject.let {
        val room = it.text("room", 32); requireHubRoom(room)
        HubRoom(room, it.text("description", 4000), it.number("last_seq"))
    } }
    require(result.map { it.room }.distinct().size == result.size)
    result
}

internal fun parseHubPage(bytes: ByteArray, room: String, after: Long = 0, until: Long = 0): HubPage = hubParse {
    requireHubRoom(room)
    val root = hubJson(bytes)
    val array = root.getValue("events").jsonArray; require(array.size <= 100)
    val messages = array.map { parseHubEvent(it, room) }
    require(messages.zipWithNext().all { (a, b) -> a.seq < b.seq })
    require(messages.all { it.seq > after && (until == 0L || it.seq <= until) })
    require(messages.map { it.eventId }.distinct().size == messages.size)
    val next = root.number("next_cursor")
    require(next >= after && (messages.isEmpty() || next == messages.last().seq))
    HubPage(messages, next, root.flag("has_older"))
}

internal fun parseHubSent(bytes: ByteArray, room: String): List<HubMessage> = hubParse {
    val root = hubJson(bytes)
    val items = root["events"]?.jsonArray ?: JsonArray(listOf(root))
    require(items.size in 1..5)
    items.map { parseHubEvent(it, room) }
}

private inline fun <T> hubParse(block: () -> T): T = try { block() }
catch (_: Exception) { throw OrbisTechHubException("invalid_response") }

/** Pure display merge: no interpretation, model invocation, /ack or task action. */
internal fun mergeHubMessages(current: List<HubMessage>, incoming: List<HubMessage>, keepNewest: Boolean): List<HubMessage> {
    val merged = (current + incoming).distinctBy { it.seq }.sortedBy { it.seq }
    return if (keepNewest) merged.takeLast(HUB_VISIBLE_MESSAGES) else merged.take(HUB_VISIBLE_MESSAGES)
}

/** Disable HTTP follow-up replay too (408/503), not only connection retries. */
internal fun hubOneShotBody(json: String): RequestBody {
    val bytes = json.toByteArray(Charsets.UTF_8)
    require(bytes.size <= HUB_SEND_BODY_LIMIT) { "invalid_text" }
    return object : RequestBody() {
        override fun contentType() = "application/json; charset=utf-8".toMediaType()
        override fun contentLength() = bytes.size.toLong()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
    }
}

internal class OrbisTechHubClient {
    internal val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    suspend fun rooms(credential: OrbisConnectionCredential): List<HubRoom> =
        parseHubRooms(request(credential, listOf("rooms")))

    suspend fun page(credential: OrbisConnectionCredential, room: String, after: Long = 0,
        until: Long = 0, tail: Boolean = false, waitSeconds: Int = 0): HubPage {
        requireHubRoom(room)
        require(after in 0..1_000_000_000_000L && until in 0..1_000_000_000_000L && waitSeconds in 0..30)
        require(until == 0L || until >= after)
        return parseHubPage(request(credential, listOf("rooms", room, "messages"), mapOf(
            "after" to after.toString(), "until" to until.toString(), "tail" to if (tail) "1" else "0",
            "ignore_fold" to "1", "limit" to "50", "wait_seconds" to waitSeconds.toString(),
        )), room, after, until)
    }

    suspend fun send(credential: OrbisConnectionCredential, intent: HubSendIntent,
        nowMillis: Long = System.currentTimeMillis()): List<HubMessage> {
        require(intent.attachment == null) { "media_requires_upload" }
        requireHubRoom(intent.room)
        require(intent.connectionRevision == credential.revision) { "configuration_changed" }
        require(intent.retryAllowed(nowMillis)) { "retry_expired" }
        val parsed = UUID.fromString(intent.key)
        require(parsed.version() == 4 && parsed.variant() == 2 && parsed.toString() == intent.key) { "invalid_send_key" }
        require(intent.text.isNotBlank() && intent.text.codePointCount(0, intent.text.length) <= 4000) { "invalid_text" }
        val body = buildJsonObject { put("text", intent.text) }.toString()
        return parseHubSent(request(credential, listOf("rooms", intent.room, "messages"),
            intent = intent, jsonBody = body), intent.room)
    }

    suspend fun upload(credential: OrbisConnectionCredential, intent: HubSendIntent, files: HubMediaFiles,
        nowMillis: Long = System.currentTimeMillis()): HubUploadReceipt {
        requireHubRoom(intent.room)
        require(intent.connectionRevision == credential.revision && intent.retryAllowed(nowMillis)) { "configuration_or_retry_changed" }
        val key = UUID.fromString(intent.key)
        require(key.version() == 4 && key.variant() == 2 && key.toString() == intent.key)
        require(intent.text.codePointCount(0, intent.text.length) <= 4000)
        val media = requireNotNull(intent.attachment)
        require(!media.filename.contains(credential.token) && !media.mediaType.contains(credential.token)
            && !intent.text.contains(credential.token)) { "unsafe_metadata" }
        val file = files.verified(media)
        return parseHubUploaded(request(credential, listOf("rooms", intent.room, "attachments"),
            query = mapOf("filename" to media.filename, "text" to intent.text), intent = intent,
            rawBody = hubUploadBody(file, media)), media)
    }

    /** A fixed same-origin ID endpoint, never any server-provided URL. */
    suspend fun download(credential: OrbisConnectionCredential, attachment: HubAttachment,
        destination: File): String = suspendCancellableCoroutine { continuation ->
        require(Regex("[a-f0-9]{32}").matches(attachment.id))
        requireHubFilename(attachment.filename)
        require(attachment.size in 1..(if (attachment.kind == "image") HUB_IMAGE_LIMIT else HUB_FILE_LIMIT))
        require(destination.isFile && destination.length() == 0L && destination.canonicalFile == destination.absoluteFile) { "unsafe_destination" }
        require(!attachment.filename.contains(credential.token)) { "unsafe_metadata" }
        val url = normalizeOrbisIntegrationUrl(credential.baseUrl).toHttpUrl().newBuilder()
            .addPathSegment("attachments").addPathSegment(attachment.id).addQueryParameter("download", "1").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${credential.token}")
            .header("Accept", "application/octet-stream").header("Accept-Encoding", "identity").get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                destination.delete()
                if (continuation.isActive) continuation.resumeWithException(OrbisTechHubException("unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (it.code != 200) throw OrbisTechHubException(when(it.code) {
                        401, 403 -> "unauthorized"; 404 -> "not_found"; in 300..399 -> "redirect_refused"; else -> "unavailable"
                    })
                    val length = it.body.contentLength()
                    if (length >= 0 && length != attachment.size) throw OrbisTechHubException("media_size_mismatch")
                    if (!it.header("Content-Encoding").isNullOrBlank() && it.header("Content-Encoding") != "identity")
                        throw OrbisTechHubException("invalid_response")
                    val mime = hubMediaType(it.body.contentType()?.toString() ?: "application/octet-stream")
                    if (mime.contains(credential.token)) throw OrbisTechHubException("invalid_response")
                    val size = it.body.byteStream().use { input -> destination.outputStream().use { output ->
                        copyHubMedia(input, output, attachment.size) {
                            if (!continuation.isActive) throw OrbisTechHubException("media_cancelled")
                        }.first.also { output.fd.sync() }
                    } }
                    if (size != attachment.size) throw OrbisTechHubException("media_size_mismatch")
                    mime
                } }
                if (!continuation.isActive) { destination.delete(); return }
                result.fold(onSuccess = { continuation.resume(it) }, onFailure = {
                    destination.delete()
                    continuation.resumeWithException(it as? OrbisTechHubException ?: OrbisTechHubException("invalid_response"))
                })
            }
        })
    }

    private suspend fun request(credential: OrbisConnectionCredential, segments: List<String>,
        query: Map<String, String> = emptyMap(), intent: HubSendIntent? = null, jsonBody: String? = null,
        rawBody: RequestBody? = null): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val url = normalizeOrbisIntegrationUrl(credential.baseUrl).toHttpUrl().newBuilder()
            segments.forEach(url::addPathSegment)
            query.forEach { (key, value) -> url.addQueryParameter(key, value) }
            val builder = Request.Builder().url(url.build()).header("Authorization", "Bearer ${credential.token}")
                .header("Accept", "application/json")
            if (intent == null) builder.get() else builder.header("Idempotency-Key", intent.key)
                .post(rawBody ?: hubOneShotBody(requireNotNull(jsonBody)))
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(OrbisTechHubException("unavailable"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw OrbisTechHubException(when (it.code) {
                            401, 403 -> "unauthorized"; 404 -> "not_found"; else -> "unavailable"
                        })
                        if (it.body.contentType()?.subtype != "json" || it.body.contentLength() > HUB_RESPONSE_LIMIT)
                            throw OrbisTechHubException("invalid_response")
                        it.body.byteStream().use { stream -> stream.cloudReadBounded(HUB_RESPONSE_LIMIT) }
                    } }
                    if (!continuation.isActive) return
                    result.fold(onSuccess = { continuation.resume(it) }, onFailure = {
                        continuation.resumeWithException(it as? OrbisTechHubException ?: OrbisTechHubException("invalid_response"))
                    })
                }
            })
        }
}
