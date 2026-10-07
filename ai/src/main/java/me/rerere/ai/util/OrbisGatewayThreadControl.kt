package me.rerere.ai.util

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class OrbisGatewayThreadState { IDLE, OWNED_BUSY, BUSY, UNSUPPORTED, UNCONFIRMED }

/** A fresh lane snapshot, not a lease or evidence that an external tool completed. */
class OrbisGatewayThreadSnapshot internal constructor(
    val state: OrbisGatewayThreadState,
    val request: OrbisGatewayRequest? = null,
) {
    override fun toString() = "OrbisGatewayThreadSnapshot($state, redacted)"
}

/** Read-only lane evidence for explicit recovery or a guarded failed-frame idle check; never stops or retries. */
class OrbisGatewayThreadControl private constructor(private val transport: GatewayControlTransport) {
    internal constructor(execute: suspend (Request) -> Response) : this(object : GatewayControlTransport {
        override suspend fun <T> read(request: Request, block: (Response) -> T): T = execute(request).use(block)
    })
    constructor(client: OkHttpClient) : this(controlTransport(client))

    /** threadId is the local conversation ID; the public header is always orbis:<id>. */
    suspend fun probe(provider: ProviderSetting, model: Model, threadId: String): OrbisGatewayThreadSnapshot =
        withContext(Dispatchers.IO) {
            val scope = scope(provider, model, threadId)
                ?: return@withContext OrbisGatewayThreadSnapshot(OrbisGatewayThreadState.UNSUPPORTED)
            val challenge = UUID.randomUUID().toString().replace("-", "")
            val request = Request.Builder()
                .url(scope.endpoint.newBuilder().encodedPath("/v1/turns/thread-status").query(null).fragment(null).build())
                .header("Authorization", scope.authorization).header("X-ST-Thread-ID", scope.thread)
                .header("X-ST-Client-ID", "orbis-dev").header("Cache-Control", "no-store")
                .post(buildJsonObject { put("model", model.modelId); put("challenge", challenge) }
                    .toString().toRequestBody("application/json".toMediaType())).build()
            try {
                transport.read(request) { response ->
                    if (response.request.url != request.url || response.priorResponse != null ||
                        response.request.method != "POST" || response.request.headers != request.headers) invalid()
                    if (response.code == 404 || response.code == 405)
                        return@read OrbisGatewayThreadSnapshot(OrbisGatewayThreadState.UNSUPPORTED)
                    if (!response.isSuccessful || !response.cacheControl.noStore) invalid()
                    val source = response.body.source()
                    if (response.body.contentLength() > 16_384 || source.request(16_385)) invalid()
                    val raw = source.buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
                    rejectDuplicateControlKeys(raw)
                    val result = json.parseToJsonElement(raw).jsonObject
                    if (result.text("protocol") != "st-thread-recovery/1" ||
                        result.text("external_tool_status") != "unknown" || result.text("challenge") != challenge ||
                        result.text("model") != model.modelId || result.text("thread_id") != scope.thread) invalid()
                    val baseKeys = setOf("protocol", "external_tool_status", "challenge", "model", "thread_id", "state", "can_stop")
                    val canStop = (result["can_stop"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
                    when (result.text("state")) {
                        "gateway_lane_idle", "busy" -> {
                            if (result.keys != baseKeys || canStop) invalid()
                            OrbisGatewayThreadSnapshot(if (result.text("state") == "gateway_lane_idle")
                                OrbisGatewayThreadState.IDLE else OrbisGatewayThreadState.BUSY)
                        }
                        "owned_busy" -> {
                            // A legacy session may have no actual client nonce; it stays busy.
                            if (result.keys == baseKeys && !canStop)
                                return@read OrbisGatewayThreadSnapshot(OrbisGatewayThreadState.OWNED_BUSY)
                            if (result.keys != baseKeys + setOf("request_id", "binding", "turn_state")) invalid()
                            val nonce = result.text("request_id")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: invalid()
                            validateBinding(result["binding"]?.jsonObject ?: invalid())
                            val turnState = result.text("turn_state")
                            if (turnState !in setOf("generating", "waiting_tool_results", "cleanup_pending") ||
                                turnState == "generating" && canStop) invalid()
                            // No automatic-finish capability, nor stop permit, is minted here.
                            // The existing exact status route must validate a fresh binding first.
                            OrbisGatewayThreadSnapshot(OrbisGatewayThreadState.OWNED_BUSY,
                                OrbisGatewayRequest(threadId, nonce, model.modelId, scope.endpoint, scope.authorization, scope.thread))
                        }
                        else -> invalid()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OrbisGatewayThreadSnapshot(OrbisGatewayThreadState.UNCONFIRMED) }
        }

    companion object {
        /** Compare a captured HTTP request without exposing its credentials or endpoint. */
        fun matchesRequestScope(request: OrbisGatewayRequest, provider: ProviderSetting,
            model: Model, conversationId: String): Boolean {
            val expected = scope(provider, model, conversationId) ?: return false
            return request.conversationId == conversationId && request.model == model.modelId &&
                request.endpoint == expected.endpoint && request.authorization == expected.authorization &&
                request.thread == expected.thread
        }

        /** Only the digest is persistent. Credentials and private endpoints never enter reports. */
        fun scopeFingerprint(provider: ProviderSetting, model: Model, conversationId: String, assistantId: String): String? {
            val scope = scope(provider, model, conversationId) ?: return null
            val values = listOf(conversationId, assistantId, provider.id.toString(), model.id.toString(),
                model.modelId, scope.endpoint.toString(), scope.authorization)
            val canonical = values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun scope(provider: ProviderSetting, model: Model, conversationId: String): ThreadScope? {
            val setting = provider as? ProviderSetting.OpenAI ?: return null
            if (!setting.enabled || !conversationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,249}")) ||
                model.modelId.isBlank() || model.modelId.length > 512 || model.modelId.any(Char::isISOControl) ||
                !orbisThreadRecoveryCustomizationsSafe(model.customHeaders, model.customBodies)) return null
            val keys = setting.apiKey.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.distinct()
            val key = keys.singleOrNull()?.takeIf { k -> k.all { it.code in 33..126 } } ?: return null
            val endpoint = (setting.baseUrl + if (setting.useResponseApi) setting.responsesPath else setting.chatCompletionsPath)
                .toHttpUrlOrNull() ?: return null
            if (endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() || endpoint.fragment != null ||
                endpoint.query != null) return null
            return ThreadScope(endpoint, "Bearer $key", "orbis:$conversationId")
        }
    }
}

/** Preserve common generation tuning; reject request routing/identity/body overrides. */
fun orbisThreadRecoveryCustomizationsSafe(headers: List<CustomHeader>, bodies: List<CustomBody>): Boolean =
    headers.none { it.name.lowercase() in setOf("authorization", "proxy-authorization", "host",
        "x-st-thread-id", "x-session-id", "x-st-request-id", "x-st-client-id", "x-st-execution-profile",
        "x-st-source-kind", "x-request-id", "idempotency-key") } && bodies.all {
        it.key in setOf("temperature", "top_p", "top_k", "max_tokens", "max_completion_tokens", "max_output_tokens",
            "presence_penalty", "frequency_penalty", "repetition_penalty", "reasoning_effort", "thinking", "min_p", "seed")
    }

private class ThreadScope(val endpoint: HttpUrl, val authorization: String, val thread: String) {
    override fun toString() = "ThreadScope(redacted)"
}
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun invalid(): Nothing = throw OrbisGatewayControlFailure("invalid_thread_response")

/** Bound nesting and reject duplicate keys, including escaped spellings, before JSON decoding. */
internal fun rejectDuplicateControlKeys(raw: String) {
    var at = 0
    fun whitespace() { while (at < raw.length && raw[at].isWhitespace()) at++ }
    fun string(): String {
        val start = at++
        while (at < raw.length) {
            when (raw[at++]) {
                '\\' -> at++
                '"' -> return (json.parseToJsonElement(raw.substring(start, at)) as JsonPrimitive).content
            }
        }
        invalid()
    }
    fun value(depth: Int = 0) {
        if (depth > 32) invalid()
        whitespace()
        when (raw[at]) {
            '{' -> {
                at++; whitespace(); val keys = mutableSetOf<String>()
                if (raw[at] == '}') { at++; return }
                while (true) {
                    whitespace(); if (!keys.add(string())) invalid()
                    whitespace(); at++; value(depth + 1); whitespace()
                    if (raw[at++] == '}') break
                }
            }
            '[' -> {
                at++; whitespace(); if (raw[at] == ']') { at++; return }
                while (true) { value(depth + 1); whitespace(); if (raw[at++] == ']') break }
            }
            '"' -> string()
            else -> while (at < raw.length && raw[at] !in ",}]" && !raw[at].isWhitespace()) at++
        }
    }
    value()
}
