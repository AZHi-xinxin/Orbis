package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.common.http.awaitAndUse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** One generation, including tool continuations, keeps the same transport decision. */
class GatewayImageTurn(internal val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    internal var supported: Boolean? = null
    internal var scope: String? = null
    // Once references have been returned, subsequent tool requests must keep that representation.
    internal val references = mutableMapOf<String, String>()
    internal val checkedAt = mutableMapOf<String, Long>()
    internal val referencesUsed: Boolean get() = references.isNotEmpty()
    private val keys = mutableMapOf<String, String>()
    @Synchronized internal fun key(endpoint: HttpUrl, provider: String, pick: () -> String): String =
        if (GatewayImagePeers.known(endpoint)) keys.getOrPut("$provider:$endpoint", pick) else pick()
    override fun toString() = "GatewayImageTurn(redacted)"
}

enum class ImagePreparationPhase(val diagnosticPhase: String, val label: String) {
    LOOKUP("image_lookup", "查询图片复用记录"), UPLOAD("image_upload", "准备图片上传")
}

enum class ImagePreparationReason(val code: String, val label: String) {
    TIMEOUT("image_prepare_timeout", "连接或等待超时"),
    NETWORK("image_prepare_network_failed", "网络连接失败"),
    AUTH("image_prepare_auth_rejected", "图片接口认证被拒绝"),
    HTTP("image_prepare_http_failed", "图片接口返回错误"),
    INVALID_RESPONSE("image_prepare_invalid_response", "图片接口返回格式不符合约定"),
    TLS("image_prepare_tls_failed", "图片接口安全连接失败"),
}

/** Only closed diagnostic fields survive; never retain a response body, URL, key or image. */
class ImagePreparationException(
    val reason: ImagePreparationReason,
    val phase: ImagePreparationPhase,
    val httpStatus: Int? = null,
) : IllegalStateException("${phase.label}失败：${reason.label}" +
    (httpStatus?.takeIf { it in 100..599 }?.let { "（HTTP $it）" } ?: "") +
    "。原图仍保留，本次模型请求尚未发送；请保留已完成的工具结果，从新消息继续。") {
    internal val mayUseOriginal: Boolean get() = reason in setOf(ImagePreparationReason.TIMEOUT, ImagePreparationReason.NETWORK) ||
        (reason == ImagePreparationReason.HTTP && (httpStatus == 429 || (httpStatus ?: 0) in 500..599))
}

/** Opt in only after this exact endpoint advertised the protocol without a redirect. */
internal object GatewayImagePeers {
    private val endpoints = linkedSetOf<String>()
    @Synchronized fun known(endpoint: HttpUrl) = endpoint.toString() in endpoints
    @Synchronized fun observe(request: Request, response: Response) {
        if (!response.isSuccessful || response.priorResponse != null || response.request.url != request.url ||
            response.request.headers.values("Authorization") != request.headers.values("Authorization") ||
            response.headers.values("X-ST-Image-Files") != listOf("st-image-files/1")) return
        endpoints.add(request.url.toString())
        while (endpoints.size > 64) endpoints.remove(endpoints.first())
    }
}

internal class GatewayImageFiles(private val exchange: suspend (Request) -> Response) {
    constructor(client: OkHttpClient) : this({ request ->
        orbisGatewayControlHttpClient(client).newBuilder().callTimeout(90, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS).writeTimeout(70, TimeUnit.SECONDS).build()
            .newCall(request).awaitAndUse { response ->
                // Bound in read() below; do not consume the original response here.
                val body = response.body ?: error("image_files_empty_response")
                val bytes = boundedImageFilesResponse(body)
                response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
            }
    })

    private data class Image(val digest: String, val mime: String, val data: String)

    suspend fun project(body: JsonObject, endpoint: HttpUrl, authorization: String, turn: GatewayImageTurn): JsonObject {
        // The official API is already fast; other protocols/official endpoints remain unchanged.
        if (endpoint.host == "api.deepseek.com" || endpoint.query != null || endpoint.fragment != null ||
            endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty()) return body
        val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return body
        val scope = digest((endpoint.toString()+"\u0000"+authorization+"\u0000"+model).toByteArray())
        check(turn.scope == null || turn.scope == scope) { "image_files_scope_changed" }
        turn.scope = scope
        if (turn.supported == false && !turn.referencesUsed) return body
        val messages = body["messages"] as? JsonArray ?: return body
        val images = linkedMapOf<String, Image>()
        messages.forEach { message ->
            ((message as? JsonObject)?.get("content") as? JsonArray)?.forEach { part ->
                val url = imageUrl(part) ?: return@forEach
                if (!images.containsKey(url)) decode(url)?.let { images[url] = it }
            }
        }
        if (images.isEmpty()) return body
        if (turn.supported == false) return projectReferences(body, messages, images, turn.references)
        val api = endpoint.newBuilder().encodedPath("/v1/image-files").query(null).fragment(null).build()
        val references = turn.references.toMutableMap()
        val checkedAt = turn.checkedAt.toMutableMap()
        var phase = ImagePreparationPhase.LOOKUP
        try {
            // The server promises at least five minutes of validity after lookup. Reuse the
            // confirmed handles across tool steps; refresh before that window ends on long turns.
            val pending = images.values.distinctBy { it.digest }.filter {
                it.digest !in references || turn.now() - (checkedAt[it.digest] ?: 0) >= 240_000
            }
            for (batch in pending.chunked(64)) {
                val checkedBeforeLookup = turn.now()
                phase = ImagePreparationPhase.LOOKUP
                val probe = read(api, authorization, buildJsonObject {
                    put("model", model); put("digests", JsonArray(batch.map { JsonPrimitive(it.digest) }))
                }, allowUnsupported = turn.supported == null, phase = phase)
                if (probe == null) { turn.supported = false; return projectReferences(body, messages, images, turn.references) }
                turn.supported = true
                batch.forEach { references.remove(it.digest) }
                references.putAll(refs(probe, batch.map { it.digest }.toSet()))
                for (image in batch.filter { it.digest !in references }) {
                    phase = ImagePreparationPhase.UPLOAD
                    val uploaded = read(api, authorization, buildJsonObject {
                        put("model", model); put("sha256", image.digest); put("mime", image.mime); put("data", image.data)
                    }, allowUnsupported = false, phase = phase) ?: error("image_files_unavailable")
                    references.putAll(refs(uploaded, setOf(image.digest)))
                    check(image.digest in references) { "image_files_missing_reference" }
                }
                batch.forEach { checkedAt[it.digest] = checkedBeforeLookup }
            }
            val projected = projectReferences(body, messages, images, references)
            turn.references.putAll(references)
            turn.checkedAt.putAll(checkedAt)
            return projected
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = when (e) {
                is ImagePreparationException -> e
                is SocketTimeoutException -> ImagePreparationException(ImagePreparationReason.TIMEOUT, phase)
                is InterruptedIOException -> ImagePreparationException(ImagePreparationReason.TIMEOUT, phase)
                is SSLException -> ImagePreparationException(ImagePreparationReason.TLS, phase)
                is IOException -> ImagePreparationException(ImagePreparationReason.NETWORK, phase)
                else -> ImagePreparationException(ImagePreparationReason.INVALID_RESPONSE, phase)
            }
            // This preflight has not called the model. Keep previously sent handles stable for
            // older ST history checks, and send new images as their original data. No tool is
            // re-executed and no image/result is dropped. Stop optional lookups for this turn.
            if (failure.mayUseOriginal) {
                currentCoroutineContext().ensureActive()
                turn.supported = false
                return projectReferences(body, messages, images, turn.references)
            }
            throw failure
        }
    }

    private fun projectReferences(body: JsonObject, messages: JsonArray, images: Map<String, Image>,
        references: Map<String, String>): JsonObject {
        if (references.isEmpty()) return body
        return JsonObject(body + ("messages" to JsonArray(messages.map { message ->
            val obj = message as? JsonObject ?: return@map message
            val content = obj["content"] as? JsonArray ?: return@map message
            JsonObject(obj + ("content" to JsonArray(content.map partMap@ { part ->
                val image = imageUrl(part)?.let(images::get) ?: return@partMap part
                val reference = references[image.digest] ?: return@partMap part
                val original = part.jsonObject
                val replacedUrl = JsonObject(original.getValue("image_url").jsonObject + ("url" to JsonPrimitive(reference)))
                JsonObject(original + ("image_url" to replacedUrl))
            })))
        })))
    }

    private suspend fun read(url: HttpUrl, authorization: String, body: JsonObject, allowUnsupported: Boolean,
        phase: ImagePreparationPhase): JsonObject? {
        val request = Request.Builder().url(url).header("Authorization", authorization)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return exchange(request).use { response ->
            check(response.request.url == url && response.priorResponse == null) { "image_files_redirect" }
            if (allowUnsupported && response.code in setOf(404, 405)) return@use null
            if (!response.isSuccessful) throw ImagePreparationException(
                if (response.code in setOf(401, 403)) ImagePreparationReason.AUTH else ImagePreparationReason.HTTP,
                phase, response.code)
            val bytes = boundedImageFilesResponse(response.body ?: error("image_files_empty_response"))
            val raw = bytes.decodeToString(); rejectDuplicateControlKeys(raw)
            val result = json.parseToJsonElement(raw).jsonObject
            check(result["protocol"] == JsonPrimitive("st-image-files/1")) { "image_files_wrong_protocol" }
            if (allowUnsupported && result["supported"] == JsonPrimitive(false)) return@use null
            check(result["supported"] == JsonPrimitive(true)) { "image_files_unsupported" }
            result
        }
    }

    private fun refs(value: JsonObject, expected: Set<String>): Map<String, String> =
        (value["references"] as? JsonObject ?: error("image_files_no_references")).mapValues { (key, ref) ->
            check(key in expected) { "image_files_unknown_digest" }
            (ref as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("st-image://[0-9a-f]{64}")) }
                ?: error("image_files_invalid_reference")
        }

    private fun imageUrl(part: JsonElement): String? = (part as? JsonObject)?.takeIf {
        it["type"] == JsonPrimitive("image_url")
    }?.get("image_url")?.let { it as? JsonObject }?.get("url")?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun decode(url: String): Image? {
        val comma = url.indexOf(','); if (comma !in 1..64) return null
        val mime = url.substring(0, comma).removePrefix("data:").removeSuffix(";base64")
        if (url.substring(0, comma) != "data:$mime;base64" || mime !in setOf("image/png", "image/jpeg", "image/webp")) return null
        val encoded = url.substring(comma + 1)
        if (encoded.length > 22369624) return null
        val raw = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (raw.isEmpty() || raw.size > 16*1024*1024) return null
        return Image(digest(raw), mime, encoded)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun boundedImageFilesResponse(body: ResponseBody): ByteArray {
    val source = body.source()
    source.request(16385)
    check(source.buffer.size <= 16384) { "image_files_large_response" }
    return source.readByteArray()
}
