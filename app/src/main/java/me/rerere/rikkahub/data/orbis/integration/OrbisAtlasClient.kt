package me.rerere.rikkahub.data.orbis.integration

import java.io.IOException
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import okhttp3.*

internal const val ATLAS_RESPONSE_LIMIT = 1024 * 1024
internal class OrbisAtlasException(val reason: String) : IOException(reason)

/** Bound nesting before the recursive JSON parser; duplicate object keys are never last-wins. */
private fun checkAtlasJsonStructure(text: String) {
    val stack = ArrayList<MutableSet<String>?>()
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{', '[' -> {
                require(stack.size < 16)
                stack.add(if (text[i] == '{') HashSet() else null)
            }
            '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
            '"' -> {
                val start = i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') i++
                    i++
                }
                require(i < text.length && i - start <= 512)
                var next = i + 1
                while (next < text.length && text[next].isWhitespace()) next++
                if (next < text.length && text[next] == ':') {
                    val keys = stack.lastOrNull() ?: error("invalid_object")
                    val key = Json.parseToJsonElement(text.substring(start, i + 1)).jsonPrimitive.content
                    require(keys.add(key))
                }
            }
        }
        i++
    }
    require(stack.isEmpty())
}

/** Strict allowlist: an endpoint returning text/summary/body instead of metadata is rejected. */
internal fun parseOrbisAtlas(bytes: ByteArray): Atlas.Snapshot {
    try {
        require(bytes.size <= ATLAS_RESPONSE_LIMIT)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        checkAtlasJsonStructure(text)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "generatedAt", "truncated", "stars", "edges"))
        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun time(value: String): String {
            require(value.length in 1..80)
            val instant = Instant.parse(value)
            // Match the Python metadata adapter's date range. Instant.MAX/MIN
            // parse successfully but cannot safely be formatted by the UI.
            require(instant >= Instant.parse("0001-01-01T00:00:00Z") &&
                instant <= Instant.parse("9999-12-31T23:59:59.999999999Z"))
            return instant.toString()
        }
        require(root.string("schema") == "orbis.st.atlas/1")
        val generatedAt = time(root.string("generatedAt"))
        val truncated = root.getValue("truncated").jsonPrimitive.let { require(!it.isString); it.boolean }
        val rawStars = root.getValue("stars").jsonArray
        val rawEdges = root.getValue("edges").jsonArray
        require(rawStars.size <= 2000 && rawEdges.size <= 5000)
        val seen = HashSet<String>()
        val stars = rawStars.map { element ->
            val star = element.jsonObject
            require(star.keys == setOf("id", "type", "storedAt"))
            val id = star.string("id")
            require(Regex("[0-9a-f]{64}").matches(id) && seen.add(id))
            val type = star.string("type")
            require(type in Atlas.SUPPORTED_TYPES)
            Atlas.Star(id, type, time(star.string("storedAt")))
        }
        val index = stars.mapIndexed { i, star -> star.id to i }.toMap()
        val edges = rawEdges.map { element ->
            val edge = element.jsonObject
            require(edge.keys == setOf("a", "b"))
            val a = index[edge.string("a")] ?: error("invalid_endpoint")
            val b = index[edge.string("b")] ?: error("invalid_endpoint")
            require(a != b)
            Atlas.Edge(minOf(a, b), maxOf(a, b))
        }.distinct()
        return Atlas.Snapshot(stars, edges, generatedAt, truncated)
    } catch (_: Exception) { throw OrbisAtlasException("invalid_metadata") }
}

internal class OrbisAtlasClient {
    // Never borrow the model HTTP client: no logging, provider proxy, cookies or credential redirects.
    internal val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun load(credential: OrbisConnectionCredential): Atlas.Snapshot = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(normalizeOrbisIntegrationUrl(credential.baseUrl) + "/v1/atlas")
            .header("Authorization", "Bearer ${credential.token}").header("Accept", "application/json").get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(OrbisAtlasException("unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (!it.isSuccessful) throw OrbisAtlasException(when(it.code) {
                        401, 403 -> "unauthorized"; 404 -> "endpoint_missing"; else -> "unavailable"
                    })
                    if (it.body.contentType()?.subtype != "json") throw OrbisAtlasException("invalid_metadata")
                    if (it.body.contentLength() > ATLAS_RESPONSE_LIMIT) throw OrbisAtlasException("invalid_metadata")
                    parseOrbisAtlas(it.body.byteStream().use { stream -> stream.cloudReadBounded(ATLAS_RESPONSE_LIMIT) })
                } }
                if (!continuation.isActive) return
                result.fold(onSuccess = { continuation.resume(it) }, onFailure = {
                    continuation.resumeWithException(it as? OrbisAtlasException ?: OrbisAtlasException("invalid_metadata"))
                })
            }
        })
    }
}
