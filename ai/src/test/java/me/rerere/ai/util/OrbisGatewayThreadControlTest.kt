package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayThreadControlTest {
    private val provider = ProviderSetting.OpenAI(baseUrl="https://unused.invalid:9443/nested/v1", apiKey="synthetic-only")
    private val model = Model(modelId="public-model")
    private val cid = "11111111-2222-4333-8444-555555555555"
    private fun body(request: Request) = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun payload(request: Request, state: String = "gateway_lane_idle", owned: Boolean = false): String = buildJsonObject {
        put("protocol", "st-thread-recovery/1"); put("external_tool_status", "unknown")
        put("model", body(request).getValue("model")); put("thread_id", request.header("X-ST-Thread-ID"))
        put("challenge", body(request).getValue("challenge")); put("state", state); put("can_stop", owned)
        if (owned) {
            put("request_id", "a".repeat(32)); put("turn_state", "waiting_tool_results")
            putJsonObject("binding") { put("session_id", "b".repeat(32)); put("generation", 1); put("revision", 0); put("batch_id", JsonNull) }
        }
    }.toString()
    private fun response(request: Request, content: String, code: Int = 200, noStore: Boolean = true) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .header("Cache-Control", if (noStore) "no-store" else "max-age=60")
        .body(content.toResponseBody("application/json".toMediaType())).build()

    @Test fun freshBoundIdleIsOneReadonlyRequestAndNeverANonceGuess() = runBlocking {
        val sent = mutableListOf<Request>()
        val control = OrbisGatewayThreadControl { r -> sent += r; response(r, payload(r)) }
        repeat(2) { assertEquals(OrbisGatewayThreadState.IDLE, control.probe(provider,model,cid).state) }
        assertEquals(2,sent.size); assertNotEquals(body(sent[0])["challenge"],body(sent[1])["challenge"])
        sent.forEach { r ->
            assertEquals("/v1/turns/thread-status",r.url.encodedPath); assertEquals(9443,r.url.port)
            assertEquals("Bearer synthetic-only",r.header("Authorization")); assertEquals("orbis:$cid",r.header("X-ST-Thread-ID"))
            assertEquals(setOf("model","challenge"),body(r).keys); assertNull(r.header("X-ST-Request-ID")); assertNull(r.url.query)
        }
    }
    @Test fun ownedBusyReturnsRealHandleWithoutAutomaticFinishOrStopPermit() = runBlocking {
        val control=OrbisGatewayThreadControl { r -> response(r,payload(r,"owned_busy",true)) }
        val result=control.probe(provider,model,cid)
        assertEquals(OrbisGatewayThreadState.OWNED_BUSY,result.state)
        val handle=checkNotNull(result.request)
        assertEquals("a".repeat(32),handle.requestId); assertEquals(cid,handle.conversationId)
        assertFalse(handle.supportsAutomaticFinish); assertFalse(result.toString().contains("synthetic-only"))
        assertFalse(result.toString().contains(cid))
    }
    @Test fun foreignBusyAndLegacyOwnedBusyHaveNoHandle() = runBlocking {
        for (state in listOf("busy","owned_busy")) {
            val control=OrbisGatewayThreadControl { r -> response(r,payload(r,state)) }
            val value=control.probe(provider,model,cid)
            assertTrue(value.state in setOf(OrbisGatewayThreadState.BUSY,OrbisGatewayThreadState.OWNED_BUSY)); assertNull(value.request)
        }
    }
    @Test fun unsupportedAndErrorsNeverMeanIdle() = runBlocking {
        for (code in listOf(401,403,404,405,409,500,503)) {
            var calls=0
            val control=OrbisGatewayThreadControl { r -> calls++; response(r,payload(r),code) }
            assertEquals(if(code in listOf(404,405)) OrbisGatewayThreadState.UNSUPPORTED else OrbisGatewayThreadState.UNCONFIRMED,
                control.probe(provider,model,cid).state); assertEquals(1,calls)
        }
    }
    @Test fun boundFieldsProtocolAndExternalStatusAreAllRequired() = runBlocking {
        val mutations=listOf<Pair<String,JsonElement>>("model" to JsonPrimitive("other"),"thread_id" to JsonPrimitive("orbis:other"),
            "challenge" to JsonPrimitive("f".repeat(32)),"protocol" to JsonPrimitive("st-turn-control/1"),
            "external_tool_status" to JsonPrimitive("complete"),"can_stop" to JsonPrimitive(true),"extra" to JsonPrimitive(true))
        for ((key,value) in mutations) {
            val control=OrbisGatewayThreadControl { r ->
                response(r,JsonObject(json.parseToJsonElement(payload(r)).jsonObject + (key to value)).toString())
            }
            assertEquals(key,OrbisGatewayThreadState.UNCONFIRMED,control.probe(provider,model,cid).state)
        }
    }
    @Test fun malformedBindingOrNonceAndImpossibleOwnedStateFailClosed() = runBlocking {
        for (change in listOf<(String)->String>({it.replace("a".repeat(32),"guess")},{it.replace("\"generation\":1","\"generation\":0")},
            {it.replace("waiting_tool_results","generating")},{it.replace("\"revision\":0","\"revision\":\"0\"")})) {
            val control=OrbisGatewayThreadControl { r -> response(r,change(payload(r,"owned_busy",true))) }
            assertEquals(OrbisGatewayThreadState.UNCONFIRMED,control.probe(provider,model,cid).state)
        }
    }
    @Test fun cachedOrRedirectedReplyIsNeverEvidence() = runBlocking {
        val cached=OrbisGatewayThreadControl { r -> response(r,payload(r),noStore=false) }
        assertEquals(OrbisGatewayThreadState.UNCONFIRMED,cached.probe(provider,model,cid).state)
        val changed=OrbisGatewayThreadControl { r -> response(r.newBuilder().url("https://other.invalid/").build(),payload(r)) }
        assertEquals(OrbisGatewayThreadState.UNCONFIRMED,changed.probe(provider,model,cid).state)
    }
    @Test fun duplicateKeysIncludingEscapesAndOversizedBodyFailClosed() = runBlocking {
        for (change in listOf<(String)->String>({it.replace("{","{\"state\":\"busy\",",ignoreCase=false)},
            {it.replaceFirst("{","{\"st\\u0061te\":\"busy\",")},{"x".repeat(16385)})) {
            val control=OrbisGatewayThreadControl { r -> response(r,change(payload(r))) }
            assertEquals(OrbisGatewayThreadState.UNCONFIRMED,control.probe(provider,model,cid).state)
        }
    }
    @Test fun cancelledProbePropagatesAndNetworkFailureIsRedacted() = runBlocking {
        val cancelled=OrbisGatewayThreadControl { throw CancellationException("cancel") }
        try { cancelled.probe(provider,model,cid); fail("cancel expected") } catch(_:CancellationException) {}
        val failed=OrbisGatewayThreadControl { throw IllegalStateException("private-key") }
        val result=failed.probe(provider,model,cid)
        assertEquals(OrbisGatewayThreadState.UNCONFIRMED,result.state); assertFalse(result.toString().contains("private-key"))
    }
    @Test fun ambiguousCredentialsAndRoutingNeverSend() = runBlocking {
        var calls=0
        val control=OrbisGatewayThreadControl { calls++; error("must not send") }
        for (setting in listOf<ProviderSetting>(provider.copy(apiKey="one,two"),provider.copy(baseUrl="https://u:p@unused.invalid/v1"),
            provider.copy(chatCompletionsPath="/chat?token=private"),ProviderSetting.Google())) {
            assertEquals(OrbisGatewayThreadState.UNSUPPORTED,control.probe(setting,model,cid).state)
        }
        for (header in listOf("Authorization","Host","X-ST-Thread-ID","X-ST-Execution-Profile","Idempotency-Key"))
            assertEquals(OrbisGatewayThreadState.UNSUPPORTED,control.probe(provider,model.copy(customHeaders=listOf(CustomHeader(header,"value"))),cid).state)
        assertEquals(OrbisGatewayThreadState.UNSUPPORTED,control.probe(provider,model.copy(customBodies=listOf(CustomBody("model",JsonPrimitive("other")))),cid).state)
        assertEquals(0,calls)
    }
    @Test fun fingerprintsPinScopeWithoutPersistingRawAuthAndPermitSafeTuning() {
        fun fingerprint(p:ProviderSetting=provider,m:Model=model,c:String=cid,a:String="assistant") =
            OrbisGatewayThreadControl.scopeFingerprint(p,m,c,a)
        val value=checkNotNull(fingerprint()); assertTrue(value.matches(Regex("[0-9a-f]{64}")))
        assertEquals(value,fingerprint()); assertNotEquals(value,fingerprint(provider.copy(apiKey="other")))
        assertNotEquals(value,fingerprint(provider.copy(baseUrl="https://other.invalid/v1")))
        assertNotEquals(value,fingerprint(m=model.copy(modelId="other"))); assertNotEquals(value,fingerprint(a="other"))
        assertNotEquals(value,fingerprint(c="other"))
        assertNotNull(fingerprint(m=model.copy(customBodies=listOf(CustomBody("max_tokens",JsonPrimitive(20))))))
    }
    @Test fun duplicateScannerHandlesNestedStringsWithoutFalseMatch() {
        rejectDuplicateControlKeys("""{"a":"\"state\": 1", "b":[{"x":1},{"x":2}]}""")
        try { rejectDuplicateControlKeys("""{"a":{"x":1,"\u0078":2}}"""); fail("duplicate") }
        catch(_:OrbisGatewayControlFailure) {}
    }
}
