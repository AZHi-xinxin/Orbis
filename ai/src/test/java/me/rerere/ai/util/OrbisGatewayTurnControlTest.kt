package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** All HTTP responses are synthetic: these tests open no sockets and send no credentials. */
class OrbisGatewayTurnControlTest {
    private val binding = """{"session_id":"${"1".repeat(32)}","generation":1,"revision":4,"batch_id":"${"2".repeat(64)}"}"""
    private fun payload(state: String, canStop: Boolean, includeBinding: Boolean = true) =
        """{"protocol":"st-turn-control/1","state":"$state","can_stop":$canStop,"external_tool_status":"unknown"${if (includeBinding) ",\"binding\":$binding" else ""}}"""

    private fun response(request: Request, body: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun handle(conversation: String = "window", token: String = "synthetic-key", model: String = "actual-model"): OrbisGatewayRequest {
        var observed: OrbisGatewayRequest? = null
        val params = TextGenerationParams(Model(modelId = "configured-model"), orbisConversationId = conversation,
            customHeaders = listOf(CustomHeader("private-custom", "never-forward")), onGatewayRequest = { observed = it })
        Request.Builder().url("https://unused.invalid:9443/nested/v1/chat/completions?secret=not-forwarded")
            .headers(params.orbisSourceHeaders()).header("Authorization", "Bearer $token")
            .post("{}".toRequestBody()).build().observeOrbisGatewayRequest(params, model)
        return checkNotNull(observed)
    }

    @Test fun statusThenExactStopUsesSameOriginKeyThreadModelAndOpaqueBinding() = runBlocking {
        val handle = handle()
        val requests = mutableListOf<Request>()
        val control = OrbisGatewayTurnControl { request ->
            requests += request
            response(request, if (requests.size == 1) payload("waiting_tool_results", true) else payload("retired", false, false))
        }
        val status = control.status(handle)
        assertEquals(OrbisGatewayState.WAITING_TOOL_RESULTS, status.state)
        assertEquals(OrbisGatewayStopResult.RETIRED, control.stop(checkNotNull(status.stopPermit)))
        assertEquals(listOf("/v1/turns/status", "/v1/turns/stop"), requests.map { it.url.encodedPath })
        requests.forEach { request ->
            assertEquals("unused.invalid", request.url.host); assertEquals(9443, request.url.port)
            assertEquals("https", request.url.scheme); assertNull(request.url.query)
            assertEquals("Bearer synthetic-key", request.header("Authorization"))
            assertEquals("orbis:window", request.header("X-ST-Thread-ID"))
            assertNull(request.header("private-custom"))
            val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("actual-model", body["model"]!!.jsonPrimitive.content)
            assertEquals(handle.requestId, body["request_id"]!!.jsonPrimitive.content)
            if (request.url.encodedPath.endsWith("stop")) assertEquals(json.parseToJsonElement(binding), body["binding"])
            else assertFalse(body.containsKey("binding"))
        }
        assertFalse(handle.toString().contains("synthetic-key"))
        assertFalse(status.toString().contains("synthetic-key"))
    }

    @Test fun unsupportedOrdinaryProviderNeverGetsAutomaticStop() = runBlocking {
        listOf(404, 405).forEach { code ->
            var calls = 0
            val client = OrbisGatewayTurnControl { request -> calls++; response(request, "not a gateway", code) }
            val status = client.status(handle())
            assertEquals(OrbisGatewayState.UNSUPPORTED, status.state); assertNull(status.stopPermit); assertEquals(1, calls)
        }
    }

    @Test fun generatingAndNotCurrentDoNotMintStopPermitOrPretendRetired() = runBlocking {
        listOf("generating" to OrbisGatewayState.GENERATING, "not_current" to OrbisGatewayState.NOT_CURRENT).forEach { (wire, state) ->
            val client = OrbisGatewayTurnControl { request -> response(request, payload(wire, false, wire != "not_current")) }
            val status = client.status(handle())
            assertEquals(state, status.state); assertNull(status.stopPermit)
        }
    }

    @Test fun conflictAfterStatusIsNotSuccessfulReleaseAndNeverRetried() = runBlocking {
        var calls = 0
        val client = OrbisGatewayTurnControl { request ->
            calls++; response(request, if (calls == 1) payload("waiting_tool_results", true) else "private-server-error", if (calls == 1) 200 else 409)
        }
        val permit = checkNotNull(client.status(handle()).stopPermit)
        try { client.stop(permit); fail("must reject stale binding") }
        catch (failure: OrbisGatewayControlFailure) {
            assertEquals(409, failure.httpStatus); assertEquals("binding_not_released", failure.code)
            assertFalse(failure.message!!.contains("private-server-error"))
        }
        assertEquals(2, calls)
    }

    @Test fun cleanupPendingNeverMeansRetiredAndSamePermitCanBeExplicitlyRetried() = runBlocking {
        var calls = 0
        val client = OrbisGatewayTurnControl { request ->
            calls++
            response(request, when(calls) { 1 -> payload("cleanup_pending", true); 2 -> payload("cleanup_pending", false); else -> payload("retired", false, false) })
        }
        val permit = checkNotNull(client.status(handle()).stopPermit)
        assertEquals(OrbisGatewayStopResult.CLEANUP_PENDING, client.stop(permit))
        assertEquals(2, calls)
        assertEquals(OrbisGatewayStopResult.RETIRED, client.stop(permit))
        assertEquals(3, calls)
    }

    @Test fun foreignProtocolAndMalformedOrImpossibleBindingsCannotAuthorizeStop() = runBlocking {
        val valid = payload("waiting_tool_results", true)
        val invalid = listOf(valid.replace("st-turn-control/1", "other/1"), valid.replace("unknown", "cancelled"),
            valid.replace("\"generation\":1", "\"generation\":0"), valid.replace("\"revision\":4", "\"revision\":-1"),
            valid.replace("\"generation\":1", "\"generation\":\"1\""), valid.replace("\"can_stop\":true", "\"can_stop\":\"true\""), valid.replace("1".repeat(32), "short"),
            valid.replace("2".repeat(64), "wrong-batch"), payload("not_current", true), payload("generating", true),
            payload("waiting_tool_results", true, false), "{}", "x".repeat(16385))
        invalid.forEach { body ->
            val client = OrbisGatewayTurnControl { request -> response(request, body) }
            try { client.status(handle()); fail("must reject invalid protocol") } catch (_: OrbisGatewayControlFailure) { }
        }
    }

    @Test fun wrongCleanupBindingOrUnexpectedStopStateCannotRelease() = runBlocking {
        listOf(payload("cleanup_pending", false).replace("\"revision\":4", "\"revision\":5"),
            payload("not_current", false, false), payload("generating", false), payload("retired", true, false)).forEach { bad ->
            var calls = 0
            val client = OrbisGatewayTurnControl { request -> response(request, if (++calls == 1) payload("waiting_tool_results", true) else bad) }
            try { client.stop(checkNotNull(client.status(handle()).stopPermit)); fail("must not release") }
            catch (_: OrbisGatewayControlFailure) { }
        }
    }

    @Test fun cancelledOrFailedStatusDoesNotRetryAndDoesNotLeakError() = runBlocking {
        var calls = 0
        val cancelled = OrbisGatewayTurnControl { _ -> calls++; throw CancellationException("cancelled") }
        try { cancelled.status(handle()); fail("cancelled") } catch (_: CancellationException) { }
        assertEquals(1, calls)
        val failed = OrbisGatewayTurnControl { _ -> calls++; throw IllegalStateException("private-key-and-body") }
        try { failed.status(handle()); fail("failed") } catch (failure: OrbisGatewayControlFailure) {
            assertFalse(failure.message!!.contains("private-key-and-body")); assertNull(failure.cause)
        }
        assertEquals(2, calls)
    }

    @Test fun transportDisablesRedirectRetryCookiesAuthenticationAndLoggingInterceptors() {
        val supplied = OkHttpClient.Builder().addInterceptor { error("must never log") }
            .addNetworkInterceptor { error("must never log") }.build()
        val control = orbisGatewayControlHttpClient(supplied)
        assertFalse(control.followRedirects); assertFalse(control.followSslRedirects); assertFalse(control.retryOnConnectionFailure)
        assertSame(Authenticator.NONE, control.authenticator); assertSame(Authenticator.NONE, control.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, control.cookieJar); assertTrue(control.interceptors.isEmpty())
        assertTrue(control.networkInterceptors.isEmpty()); assertNull(control.cache); assertEquals(10000, control.callTimeoutMillis)
    }

    @Test fun ledgerRetainsEarlierWaitingCandidateAcrossRejectedRequestsAndScopesWindows() {
        val ledger = OrbisGatewayRequestLedger()
        val waiting = handle(); val rejected = handle(); val other = handle("other")
        ledger.remember(waiting); ledger.remember(rejected); ledger.remember(other)
        assertEquals(listOf(rejected, waiting), ledger.recent("window"))
        assertEquals(listOf(other), ledger.recent("other"))
        ledger.forget(rejected); assertEquals(listOf(waiting), ledger.recent("window"))
        repeat(40) { ledger.remember(handle()) }; assertEquals(16, ledger.recent("window").size)
        ledger.clear(); assertTrue(ledger.recent("other").isEmpty())
    }

    @Test fun observerAndTransportSecretsAreExcludedFromSerializedParams() {
        var called = false
        val params = TextGenerationParams(Model(), orbisConversationId = "window", onGatewayRequest = { called = true })
        val encoded = Json.encodeToString(params)
        assertFalse(encoded.contains("onGatewayRequest")); assertFalse(called)
    }

    @Test fun ambiguousAuthOrForgedThreadCannotCreateControlHandle() {
        val observed = mutableListOf<OrbisGatewayRequest>()
        val params = TextGenerationParams(Model(), orbisConversationId = "window", onGatewayRequest = { observed += it })
        val base = Request.Builder().url("https://unused.invalid/v1/chat/completions").headers(params.orbisSourceHeaders())
            .header("Authorization", "Bearer key").build()
        base.newBuilder().addHeader("Authorization", "Bearer another").build().observeOrbisGatewayRequest(params, "model")
        base.newBuilder().header("X-ST-Thread-ID", "orbis:foreign").build().observeOrbisGatewayRequest(params, "model")
        base.newBuilder().header("X-ST-Request-ID", "forged").build().observeOrbisGatewayRequest(params, "model")
        base.observeOrbisGatewayRequest(params, null)
        assertTrue(observed.isEmpty())
    }
}
