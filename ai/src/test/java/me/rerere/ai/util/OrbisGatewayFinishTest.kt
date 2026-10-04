package me.rerere.ai.util

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic response tests; no network, real credential, conversation or tool execution. */
class OrbisGatewayFinishTest {
    private val binding = """{"session_id":"${"1".repeat(32)}","generation":1,"revision":4,"batch_id":null}"""
    private val waiting = """{"protocol":"st-turn-control/1","state":"waiting_tool_results","can_stop":true,"external_tool_status":"unknown","binding":$binding}"""
    private val retired = """{"protocol":"st-turn-control/1","state":"retired","can_stop":false,"external_tool_status":"unknown"}"""

    private fun request(
        endpoint: String = "https://unused.invalid/v1/chat/completions",
        conversation: String = "synthetic-window",
        authorization: String = "Bearer synthetic-key",
        model: String = "synthetic-model",
    ): Request {
        val params = TextGenerationParams(Model(modelId = model), orbisConversationId = conversation,
            onGatewayRequest = {})
        return Request.Builder().url(endpoint).headers(params.orbisSourceHeaders())
            .header("Authorization", authorization).post("{}".toRequestBody()).build()
            .observeOrbisGatewayRequest(params, model)
    }

    private fun handle(request: Request) = checkNotNull(request.tag(OrbisGatewayRequest::class.java))
    private fun response(request: Request, body: String = "{}", code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private fun accepted(request: Request): Response = response(request).newBuilder()
        .header("X-ST-Turn-Control", "st-turn-control/1")
        .header("X-ST-Request-ID", checkNotNull(request.header("X-ST-Request-ID"))).build()

    @Test fun onlyExactSingleAcceptedResponseHeadersEnableAutomaticFinish() {
        val mutations: List<(Response) -> Response> = listOf(
            { it.newBuilder().code(409).build() },
            { it.newBuilder().removeHeader("X-ST-Turn-Control").build() },
            { it.newBuilder().header("X-ST-Turn-Control", "future/2").build() },
            { it.newBuilder().addHeader("X-ST-Turn-Control", "st-turn-control/1").build() },
            { it.newBuilder().removeHeader("X-ST-Request-ID").build() },
            { it.newBuilder().header("X-ST-Request-ID", "2".repeat(32)).build() },
            { it.newBuilder().addHeader("X-ST-Request-ID", it.header("X-ST-Request-ID")!!).build() },
            { it.newBuilder().request(it.request.newBuilder().url("https://foreign.invalid/v1/chat/completions").build()).build() },
            { it.newBuilder().request(it.request.newBuilder().url("https://unused.invalid/other").build()).build() },
            { it.newBuilder().request(it.request.newBuilder().header("Authorization", "Bearer other").build()).build() },
            { it.newBuilder().request(it.request.newBuilder().header("X-ST-Thread-ID", "orbis:other").build()).build() },
            { it.newBuilder().request(it.request.newBuilder().header("X-ST-Request-ID", "3".repeat(32)).build()).build() },
            { it.newBuilder().request(it.request.newBuilder().addHeader("Authorization", "Bearer another").build()).build() },
            { it.newBuilder().priorResponse(Response.Builder().request(it.request)
                .protocol(Protocol.HTTP_1_1).code(307).message("synthetic redirect").build()).build() },
        )
        mutations.forEach { mutation ->
            val request = request()
            mutation(accepted(request)).use { request.observeOrbisGatewayResponse(it) }
            assertFalse(handle(request).supportsAutomaticFinish)
        }
        val request = request()
        assertFalse(handle(request).supportsAutomaticFinish)
        accepted(request).use { request.observeOrbisGatewayResponse(it) }
        assertTrue(handle(request).supportsAutomaticFinish)
        assertEquals("OrbisGatewayRequest(redacted)", handle(request).toString())
    }

    @Test fun capabilityFromOneAcceptedRequestDoesNotMarkRejectedNewRequestAccepted() {
        val earlier = request(); val rejected = request()
        accepted(earlier).use { earlier.observeOrbisGatewayResponse(it) }
        response(rejected, code = 409).use { rejected.observeOrbisGatewayResponse(it) }
        assertTrue(handle(earlier).supportsAutomaticFinish)
        assertFalse(handle(rejected).supportsAutomaticFinish)
        assertTrue(handle(rejected).hasAutomaticControlScopeOf(handle(earlier)))
        assertFalse(handle(earlier).hasAutomaticControlScopeOf(handle(rejected)))
    }

    @Test fun automaticCapabilityNeverCrossesCapturedRouteAccountConversationOrModel() {
        val proven = request()
        accepted(proven).use { proven.observeOrbisGatewayResponse(it) }
        listOf(request(endpoint = "http://unused.invalid/v1/chat/completions"),
            request(endpoint = "https://foreign.invalid/v1/chat/completions"),
            request(endpoint = "https://unused.invalid:8443/v1/chat/completions"),
            request(endpoint = "https://unused.invalid/v1/responses"),
            request(endpoint = "https://unused.invalid/v1/chat/completions?route=another"),
            request(authorization = "Bearer other-account"), request(conversation = "other-window"),
            request(model = "other-model")).forEach { candidate ->
            assertFalse(handle(candidate).hasAutomaticControlScopeOf(handle(proven)))
        }
    }

    @Test fun ordinaryProviderCannotBeFinishedEvenWithSyntheticStatusPermit() = runBlocking {
        val request = request()
        var calls = 0
        val control = OrbisGatewayTurnControl { outbound -> calls++; response(outbound, waiting) }
        val permit = checkNotNull(control.status(handle(request)).stopPermit)
        try { control.finish(permit, OrbisGatewayTerminalReason.FAILED); fail("No advertised capability") }
        catch (error: OrbisGatewayControlFailure) { assertEquals("automatic_finish_not_supported", error.code) }
        assertEquals(1, calls) // No automatic finish request is dispatched.
    }

    @Test fun exactTerminalReceiptIsDistinctFromManualStopAndDoesNotClaimExternalToolCancelled() = runBlocking {
        OrbisGatewayTerminalReason.entries.forEach { reason ->
            val request = request()
            accepted(request).use { request.observeOrbisGatewayResponse(it) }
            val sent = mutableListOf<Request>()
            val control = OrbisGatewayTurnControl { outbound ->
                sent += outbound
                response(outbound, if (sent.size == 1) waiting else retired)
            }
            val permit = checkNotNull(control.status(handle(request)).stopPermit)
            assertEquals(OrbisGatewayStopResult.RETIRED, control.finish(permit, reason))
            assertEquals(listOf("/v1/turns/status", "/v1/turns/finish"), sent.map { it.url.encodedPath })
            val payload = json.parseToJsonElement(Buffer().also { sent.last().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals(setOf("model", "request_id", "binding", "terminal_reason"), payload.keys)
            assertEquals(reason.wire, payload.getValue("terminal_reason").jsonPrimitive.content)
            assertEquals(json.parseToJsonElement(binding), payload.getValue("binding"))
            assertFalse(payload.toString().contains("synthetic-key"))
        }
    }

    @Test fun lostContinuationResponseMayUseOnlySameScopeCapabilityAndExactNewStatusPermit() = runBlocking {
        val previous = request(); val continuation = request()
        accepted(previous).use { previous.observeOrbisGatewayResponse(it) }
        val sent = mutableListOf<Request>()
        val control = OrbisGatewayTurnControl { outbound ->
            sent += outbound; response(outbound, if (sent.size == 1) waiting else retired)
        }
        val permit = checkNotNull(control.status(handle(continuation)).stopPermit)
        assertEquals(OrbisGatewayStopResult.RETIRED,
            control.finish(permit, OrbisGatewayTerminalReason.CANCELLED, handle(previous)))
        val payload = json.parseToJsonElement(Buffer().also { sent.last().body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(handle(continuation).requestId, payload.getValue("request_id").jsonPrimitive.content)
        assertNotEquals(handle(previous).requestId, payload.getValue("request_id").jsonPrimitive.content)
        assertFalse(handle(continuation).supportsAutomaticFinish)
    }

    @Test fun finishConflictAndUnknownPendingNeverClaimReleaseOrAutomaticallyRetry() = runBlocking {
        val request = request()
        accepted(request).use { request.observeOrbisGatewayResponse(it) }
        var calls = 0
        val control = OrbisGatewayTurnControl { outbound ->
            calls++; response(outbound, if (calls == 1) waiting else "private synthetic detail", if (calls == 1) 200 else 409)
        }
        val permit = checkNotNull(control.status(handle(request)).stopPermit)
        try { control.finish(permit, OrbisGatewayTerminalReason.FAILED); fail("Must not release") }
        catch (error: OrbisGatewayControlFailure) {
            assertEquals("binding_not_released", error.code); assertEquals(409, error.httpStatus)
            assertFalse(error.toString().contains("private synthetic detail"))
        }
        assertEquals(2, calls)
        val pending = OrbisGatewayTurnControl { outbound -> response(outbound,
            waiting.replace("waiting_tool_results", "cleanup_pending").replace("\"can_stop\":true", "\"can_stop\":false")) }
        assertEquals(OrbisGatewayStopResult.CLEANUP_PENDING, pending.finish(permit, OrbisGatewayTerminalReason.CANCELLED))
    }
}
