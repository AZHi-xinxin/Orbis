package me.rerere.ai.util

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.common.http.awaitAndUse
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Ephemeral handle for one actual HTTP request, never serialized or logged. */
class OrbisGatewayRequest internal constructor(
    val conversationId: String,
    val requestId: String,
    val model: String,
    internal val endpoint: HttpUrl,
    internal val authorization: String,
    internal val thread: String,
) {
    @Volatile private var acceptedControlProtocol = false
    /** A successful response from this exact request advertised the terminal-receipt protocol. */
    val supportsAutomaticFinish: Boolean get() = acceptedControlProtocol
    val origin: String get() = endpoint.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()

    /** Scope comparison only. Neither credentials nor private routing data leave this handle. */
    fun hasAutomaticControlScopeOf(evidence: OrbisGatewayRequest): Boolean =
        evidence.supportsAutomaticFinish && conversationId == evidence.conversationId &&
            endpoint == evidence.endpoint && authorization == evidence.authorization &&
            thread == evidence.thread && model == evidence.model

    /** Classification only: a known control peer is not proof that this new owner was retired. */
    fun hasObservedControlPeerOf(evidence: OrbisGatewayRequest): Boolean =
        evidence.supportsAutomaticFinish && conversationId == evidence.conversationId &&
            endpoint == evidence.endpoint && authorization == evidence.authorization && thread == evidence.thread

    internal fun acknowledgeControlProtocol() { acceptedControlProtocol = true }
    override fun toString() = "OrbisGatewayRequest(redacted)"
}

/** Failed/newer requests do not displace the earlier request still waiting for tool results. */
class OrbisGatewayRequestLedger {
    private val requests = linkedMapOf<String, OrbisGatewayRequest>()

    @Synchronized fun remember(request: OrbisGatewayRequest) {
        requests.remove(request.requestId)
        requests[request.requestId] = request
        val sameConversation = requests.values.filter { it.conversationId == request.conversationId }
        sameConversation.dropLast(16).forEach { requests.remove(it.requestId) }
        while (requests.size > 512) requests.remove(requests.keys.first())
    }

    @Synchronized fun recent(conversationId: String): List<OrbisGatewayRequest> =
        requests.values.filter { it.conversationId == conversationId }.asReversed()

    @Synchronized fun forget(request: OrbisGatewayRequest) { requests.remove(request.requestId, request) }
    @Synchronized fun clear() { requests.clear() }
}

enum class OrbisGatewayState { UNSUPPORTED, NOT_CURRENT, GENERATING, WAITING_TOOL_RESULTS, CLEANUP_PENDING }

/** The binding is an opaque server receipt; callers cannot construct a stop permit. */
class OrbisGatewayStopPermit internal constructor(
    internal val request: OrbisGatewayRequest,
    internal val binding: JsonObject,
) {
    val requestId: String get() = request.requestId
    override fun toString() = "OrbisGatewayStopPermit(redacted)"
}

data class OrbisGatewayStatus(val state: OrbisGatewayState, val stopPermit: OrbisGatewayStopPermit? = null)
enum class OrbisGatewayStopResult { RETIRED, CLEANUP_PENDING }
enum class OrbisGatewayTerminalReason(internal val wire: String) {
    FAILED("failed"), CANCELLED("cancelled"), BUDGET_EXHAUSTED("budget_exhausted")
}

/** Safe fixed code only; response bodies/URLs/credentials never enter UI exceptions. */
class OrbisGatewayControlFailure(val code: String, val httpStatus: Int? = null) :
    IllegalStateException("gateway_turn_control:$code")

/** Exact request control only. Does not poll, retry, redirect, replay or generate a model reply. */
class OrbisGatewayTurnControl private constructor(private val transport: GatewayControlTransport) {
    internal constructor(execute: suspend (Request) -> Response) : this(object : GatewayControlTransport {
        override suspend fun <T> read(request: Request, block: (Response) -> T): T = execute(request).use(block)
    })
    constructor(client: OkHttpClient) : this(controlTransport(client))

    suspend fun status(request: OrbisGatewayRequest): OrbisGatewayStatus = withContext(Dispatchers.IO) {
        exchange(controlRequest(request, "status", null)) { response ->
            if (response.code == 404 || response.code == 405) return@exchange OrbisGatewayStatus(OrbisGatewayState.UNSUPPORTED)
            requireSuccess(response)
            val result = response.readControlObject()
            result.requireProtocol()
            val state = when (result.string("state")) {
                "not_current" -> OrbisGatewayState.NOT_CURRENT
                "generating" -> OrbisGatewayState.GENERATING
                "waiting_tool_results" -> OrbisGatewayState.WAITING_TOOL_RESULTS
                "cleanup_pending" -> OrbisGatewayState.CLEANUP_PENDING
                else -> invalidControl()
            }
            val canStop = result.boolean("can_stop") ?: invalidControl()
            val binding = result["binding"]?.takeUnless { it == JsonNull }?.let { validateBinding(it.jsonObject) }
            if (state == OrbisGatewayState.NOT_CURRENT && (canStop || binding != null)) invalidControl()
            if (state != OrbisGatewayState.NOT_CURRENT && binding == null) invalidControl()
            if (state == OrbisGatewayState.GENERATING && canStop) invalidControl()
            val permit = if (canStop && state in setOf(OrbisGatewayState.WAITING_TOOL_RESULTS, OrbisGatewayState.CLEANUP_PENDING))
                OrbisGatewayStopPermit(request, checkNotNull(binding)) else null
            OrbisGatewayStatus(state, permit)
        }
    }

    /** A permit is obtained only by a successful protocol-aware status call for this exact request. */
    suspend fun stop(permit: OrbisGatewayStopPermit): OrbisGatewayStopResult =
        terminate(permit, "stop", null)

    /**
     * The caller must have fully unwound the owning invocation (including local tool execution).
     * A previous accepted request from that same invocation may prove capability for a later
     * continuation whose response was lost. It never proves that continuation was accepted or
     * that an external tool was cancelled: status + exact binding still decide that separately.
     */
    suspend fun finish(
        permit: OrbisGatewayStopPermit,
        reason: OrbisGatewayTerminalReason,
        capabilityEvidence: OrbisGatewayRequest = permit.request,
    ): OrbisGatewayStopResult {
        if (!permit.request.hasAutomaticControlScopeOf(capabilityEvidence)) {
            throw OrbisGatewayControlFailure("automatic_finish_not_supported")
        }
        return terminate(permit, "finish", reason)
    }

    private suspend fun terminate(
        permit: OrbisGatewayStopPermit,
        action: String,
        reason: OrbisGatewayTerminalReason?,
    ): OrbisGatewayStopResult = withContext(Dispatchers.IO) {
        exchange(controlRequest(permit.request, action, permit.binding, reason)) { response ->
            requireSuccess(response) // In particular, HTTP 409 NEVER releases a local hold.
            val result = response.readControlObject()
            result.requireProtocol()
            if (result.boolean("can_stop") != false) invalidControl()
            when (result.string("state")) {
                "retired" -> {
                    if (result["binding"]?.takeUnless { it == JsonNull } != null) invalidControl()
                    OrbisGatewayStopResult.RETIRED
                }
                "cleanup_pending" -> {
                    val binding = result["binding"]?.jsonObject?.let(::validateBinding) ?: invalidControl()
                    if (binding != permit.binding) invalidControl()
                    OrbisGatewayStopResult.CLEANUP_PENDING
                }
                else -> invalidControl()
            }
        }
    }

    private suspend fun <T> exchange(request: Request, read: (Response) -> T): T = try {
        transport.read(request, read)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: OrbisGatewayControlFailure) { throw failure }
    catch (_: Exception) { throw OrbisGatewayControlFailure("request_or_response_failed") }
}

/** Called after headers/key roulette/body are final and before the request is dispatched. */
internal fun Request.observeOrbisGatewayRequest(params: TextGenerationParams, effectiveModel: String?): Request {
    val observer = params.onGatewayRequest ?: return this
    val conversationId = params.orbisConversationId ?: return this
    val thread = header("X-ST-Thread-ID") ?: return this
    val requestId = header("X-ST-Request-ID") ?: return this
    val authorizations = headers.values("Authorization")
    // Ambiguous custom authentication is deliberately not turned into a remotely controllable handle.
    val authorization = authorizations.singleOrNull()?.takeIf { it.startsWith("Bearer ") && it.length > 7 } ?: return this
    val model = effectiveModel?.takeIf { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) } ?: return this
    if (thread != "orbis:$conversationId" || !requestId.matches(Regex("[0-9a-f]{32}")) ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return this
    val handle = OrbisGatewayRequest(conversationId, requestId, model, url, authorization, thread)
    observer(handle)
    return newBuilder().tag(OrbisGatewayRequest::class.java, handle).build()
}

/** Response headers are capability evidence only for the exact non-redirected accepted request. */
internal fun Request.observeOrbisGatewayResponse(response: Response) {
    val handle = tag(OrbisGatewayRequest::class.java) ?: return
    val actual = response.request
    if (!response.isSuccessful || response.priorResponse != null || actual.url != url || actual.method != method ||
        actual.headers.values("Authorization") != listOf(handle.authorization) ||
        actual.headers.values("X-ST-Thread-ID") != listOf(handle.thread) ||
        actual.headers.values("X-ST-Request-ID") != listOf(handle.requestId) ||
        response.headers.values("X-ST-Turn-Control") != listOf("st-turn-control/1") ||
        response.headers.values("X-ST-Request-ID") != listOf(handle.requestId)) return
    handle.acknowledgeControlProtocol()
}

private fun controlRequest(
    request: OrbisGatewayRequest,
    action: String,
    binding: JsonObject?,
    reason: OrbisGatewayTerminalReason? = null,
): Request {
    // Absolute gateway routes, same scheme/host/port; do not append to a provider-defined path/query.
    val endpoint = request.endpoint.newBuilder().encodedPath("/v1/turns/$action").query(null).fragment(null).build()
    val body = buildJsonObject {
        put("model", request.model); put("request_id", request.requestId)
        binding?.let { put("binding", it) }
        reason?.let { put("terminal_reason", it.wire) }
    }
    return Request.Builder().url(endpoint).header("Authorization", request.authorization)
        .header("X-ST-Thread-ID", request.thread).header("X-ST-Client-ID", "orbis-dev")
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
}

internal interface GatewayControlTransport {
    suspend fun <T> read(request: Request, block: (Response) -> T): T
}

internal fun controlTransport(client: OkHttpClient): GatewayControlTransport {
    val safe = orbisGatewayControlHttpClient(client)
    return object : GatewayControlTransport {
        override suspend fun <T> read(request: Request, block: (Response) -> T): T =
            safe.newCall(request).awaitAndUse(block)
    }
}

internal fun orbisGatewayControlHttpClient(client: OkHttpClient): OkHttpClient =
    client.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
        .cache(null).callTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()

private fun requireSuccess(response: Response) {
    if (!response.isSuccessful) throw OrbisGatewayControlFailure(
        if (response.code == 409) "binding_not_released" else "http_failure", response.code)
}

internal fun Response.readControlObject(): JsonObject {
    if (body.contentLength() > 16 * 1024) invalidControl()
    val source = body.source()
    if (source.request(16 * 1024L + 1)) invalidControl()
    return json.parseToJsonElement(source.buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)).jsonObject
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.requireProtocol() {
    if (string("protocol") != "st-turn-control/1" || string("external_tool_status") != "unknown") invalidControl()
}
internal fun validateBinding(binding: JsonObject): JsonObject {
    if (binding.keys != setOf("session_id", "generation", "revision", "batch_id") ||
        binding.string("session_id")?.matches(Regex("[0-9a-f]{32}")) != true) invalidControl()
    val generation = binding["generation"] as? JsonPrimitive ?: invalidControl()
    val revision = binding["revision"] as? JsonPrimitive ?: invalidControl()
    if (generation.isString || revision.isString || (generation.longOrNull ?: 0) < 1 || (revision.longOrNull ?: -1) < 0) invalidControl()
    if (binding["batch_id"] != JsonNull && binding.string("batch_id")?.matches(Regex("[0-9a-f]{64}")) != true) invalidControl()
    return binding
}
private fun invalidControl(): Nothing = throw OrbisGatewayControlFailure("invalid_response")
