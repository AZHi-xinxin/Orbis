package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GatewayImageFilesTest {
    @Test fun transientLookupFailureUsesEveryOriginalPartAndFreezesTurnChoice()=runBlocking {
        for (status in listOf(429, 500, 502, 503, 504)) {
            var calls=0
            val transport=GatewayImageFiles { r -> calls++;response(r,buildJsonObject {},status) }
            val turn=GatewayImageTurn()
            assertSame(body,transport.project(body,url,"Bearer synthetic",turn))
            assertSame(body,transport.project(body,url,"Bearer synthetic",turn))
            assertEquals(1,calls)
            assertEquals(false,turn.supported)
            assertFalse(turn.referencesUsed)
        }
    }
    @Test fun failedUploadAfterSuccessfulLookupStillUsesOriginalImages()=runBlocking {
        val calls=mutableListOf<JsonObject>()
        val transport=GatewayImageFiles { r ->
            val sent=sent(r);calls+=sent
            response(r, if ("digests" in sent) result() else buildJsonObject {},
                if ("digests" in sent) 200 else 502)
        }
        val turn=GatewayImageTurn()
        assertSame(body,transport.project(body,url,"Bearer synthetic",turn))
        assertEquals(2,calls.size)
        assertEquals(image.substringAfter(','),calls.last()["data"]!!.jsonPrimitive.content)
        assertSame(body,transport.project(body,url,"Bearer synthetic",turn))
        assertEquals(2,calls.size)
    }
    @Test fun partiallyPreparedBatchNeverLeaksMixedReferencesIntoFallback()=runBlocking {
        val second=image.dropLast(4)+"BgcI"
        val mixed=buildJsonObject {
            put("model","model");put("tools",body.getValue("tools"))
            putJsonArray("messages") { addJsonObject {
                put("role","user");putJsonArray("content") {
                    for (original in listOf(image,second)) addJsonObject {
                        put("type","image_url");putJsonObject("image_url") { put("url",original) }
                    }
                }
            } }
        }
        var uploads=0
        val transport=GatewayImageFiles { r ->
            val sent=sent(r)
            when {
                "digests" in sent -> response(r,result())
                ++uploads==1 -> response(r,result(mapOf(sent["sha256"]!!.jsonPrimitive.content to "st-image://"+"a".repeat(64))))
                else -> response(r,buildJsonObject {},503)
            }
        }
        assertSame(mixed,transport.project(mixed,url,"Bearer synthetic",GatewayImageTurn()))
        assertEquals(2,uploads)
    }
    @Test fun firstNetworkFailureUsesOriginalImages()=runBlocking {
        for (error in listOf(java.net.SocketTimeoutException("PRIVATE"), java.io.InterruptedIOException("PRIVATE"), java.net.ConnectException("PRIVATE"))) {
            val transport=GatewayImageFiles { throw error }
            assertSame(body,transport.project(body,url,"Bearer synthetic",GatewayImageTurn()))
        }
    }
    @Test fun authAndInvalidResponsesRemainExplicitAndDoNotFallBack()=runBlocking {
        for (status in listOf(400,401,403,409,422)) {
            val transport=GatewayImageFiles { r -> response(r,buildJsonObject { put("private","SECRET") },status) }
            try { transport.project(body,url,"Bearer synthetic",GatewayImageTurn());fail("must reject") }
            catch (e:ImagePreparationException) {
                assertEquals(status,e.httpStatus);assertEquals(ImagePreparationPhase.LOOKUP,e.phase)
                assertFalse(e.message!!.contains("SECRET"));assertNull(e.cause)
            }
        }
        try {
            GatewayImageFiles { throw javax.net.ssl.SSLHandshakeException("SECRET") }
                .project(body,url,"Bearer synthetic",GatewayImageTurn());fail("must reject TLS failure")
        } catch (e:ImagePreparationException) { assertEquals(ImagePreparationReason.TLS,e.reason) }
    }
    @Test fun uploadAuthenticationFailureKeepsItsStatusAndPhase()=runBlocking {
        var calls=0
        val transport=GatewayImageFiles { r -> calls++; if(calls==1)response(r,result()) else response(r,buildJsonObject {},403) }
        try {
            transport.project(body,url,"Bearer synthetic",GatewayImageTurn())
            fail("must not ignore rejected authentication")
        } catch(e:ImagePreparationException) {
            assertEquals(403,e.httpStatus);assertEquals(ImagePreparationPhase.UPLOAD,e.phase)
            assertEquals(ImagePreparationReason.AUTH,e.reason)
        }
    }
    @Test fun capabilityIsBoundToExactResponseAndKeysStayStableOnlyForKnownGateway() {
        val endpoint="https://image-capability.invalid/v1/chat/completions".toHttpUrl()
        val request=Request.Builder().url(endpoint).header("Authorization","Bearer synthetic").build()
        val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .header("X-ST-Image-Files","st-image-files/1").build()
        assertFalse(GatewayImagePeers.known(endpoint))
        GatewayImagePeers.observe(request,response.newBuilder().code(401).build())
        assertFalse(GatewayImagePeers.known(endpoint))
        GatewayImagePeers.observe(request,response.newBuilder().request(request.newBuilder().url("https://elsewhere.invalid/").build()).build())
        assertFalse(GatewayImagePeers.known(endpoint))
        GatewayImagePeers.observe(request,response)
        assertTrue(GatewayImagePeers.known(endpoint))
        var selections=0;val turn=GatewayImageTurn()
        assertEquals("key1",turn.key(endpoint,"provider") {"key${++selections}"})
        assertEquals("key1",turn.key(endpoint,"provider") {"key${++selections}"})
        assertEquals(1,selections)
        assertEquals("key2",GatewayImageTurn().key(endpoint,"provider") {"key${++selections}"})
        assertEquals("key3",turn.key(url,"ordinary provider") {"key${++selections}"})
        assertEquals("key4",turn.key(url,"ordinary provider") {"key${++selections}"})
        assertFalse(turn.toString().contains("key1"))
    }
    private val url = "https://gateway.invalid/v1/chat/completions".toHttpUrl()
    private val image = "data:image/png;base64,"+Base64.getEncoder().encodeToString(byteArrayOf(1,2,3,4,5))
    private val body = buildJsonObject {
        put("model","model");putJsonArray("messages") {
            addJsonObject { put("role","user");putJsonArray("content") {
                addJsonObject { put("type","text");put("text","unchanged text") }
                addJsonObject { put("type","image_url");putJsonObject("image_url") { put("url",image) } }
            } }
        }; putJsonArray("tools") { addJsonObject { put("type","function");putJsonObject("function") { put("name","new_tool") } } }
    }
    private fun sent(request:Request)=json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun response(r:Request, value:JsonObject, code:Int=200)=Response.Builder().request(r).protocol(Protocol.HTTP_1_1)
        .code(code).message("synthetic").body(value.toString().toResponseBody("application/json".toMediaType())).build()
    private fun result(refs:Map<String,String> = emptyMap())=buildJsonObject {
        put("protocol","st-image-files/1");put("supported",true);put("references",JsonObject(refs.mapValues { JsonPrimitive(it.value) }))
    }
    @Test fun uploadOnceThenReuseConfirmedImageAndPreserveTools()=runBlocking {
        val requests=mutableListOf<Request>();val cached=mutableMapOf<String,String>()
        val transport=GatewayImageFiles { r ->
            requests+=r;val b=sent(r)
            if("data" in b) { assertEquals(image.substringAfter(','),b["data"]!!.jsonPrimitive.content)
                cached[b["sha256"]!!.jsonPrimitive.content]="st-image://"+"a".repeat(64) }
            response(r,result(cached))
        }
        val turn=GatewayImageTurn();val first=transport.project(body,url,"Bearer synthetic",turn)
        assertNotEquals(body,first);assertEquals(body["tools"],first["tools"])
        assertEquals(first,transport.project(body,url,"Bearer synthetic",turn))
        assertEquals(2,requests.size);assertEquals(1,requests.count { "data" in sent(it) })
        assertEquals(image,body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        requests.forEach { assertEquals("/v1/image-files",it.url.encodedPath);assertEquals("Bearer synthetic",it.header("Authorization")) }
    }
    @Test fun unsupportedIsStickyForTheWholeToolTurn()=runBlocking {
        var calls=0;val transport=GatewayImageFiles { r -> calls++;response(r,buildJsonObject {},404) };val turn=GatewayImageTurn()
        repeat(2) { assertEquals(body,transport.project(body,url,"Bearer synthetic",turn)) }
        assertEquals(1,calls)
    }
    @Test fun activeReferenceNeverSilentlyRevertsToRawOnFailure()=runBlocking {
        var fail=false;val transport=GatewayImageFiles { r ->
            if(fail)response(r,buildJsonObject {},404) else {
                val digests=sent(r)["digests"]!!.jsonArray.map { it.jsonPrimitive.content }
                response(r,result(digests.associateWith { "st-image://"+"b".repeat(64) }))
            }
        }
        var now=0L;val turn=GatewayImageTurn { now };transport.project(body,url,"Bearer synthetic",turn);fail=true;now=240_000
        try { transport.project(body,url,"Bearer synthetic",turn);fail("failure expected") }catch(e:IllegalStateException) { assertFalse(e.message!!.contains("synthetic")) }
    }
    @Test fun cancellationPropagatesAndOfficialNoImageRequestsStayUntouched()=runBlocking {
        val transport=GatewayImageFiles { throw CancellationException("cancel") }
        assertSame(body,transport.project(body,"https://api.deepseek.com/chat/completions".toHttpUrl(),"Bearer synthetic",GatewayImageTurn()))
        val empty=buildJsonObject { put("model","m");put("messages",JsonArray(emptyList())) }
        assertSame(empty,transport.project(empty,url,"Bearer synthetic",GatewayImageTurn()))
        try { transport.project(body,url,"Bearer synthetic",GatewayImageTurn());fail("cancel expected") }catch(_:CancellationException) {}
    }
    @Test fun wrongDigestReferenceOrProtocolFailsBeforeChatSubmission()=runBlocking {
        for (wrong in listOf(result(mapOf("unknown" to "st-image://"+"a".repeat(64))),
                buildJsonObject { put("protocol","wrong");put("supported",true) })) {
            val transport=GatewayImageFiles { r -> response(r,wrong) }
            try { transport.project(body,url,"Bearer synthetic",GatewayImageTurn());fail("reject expected") }catch(_:IllegalStateException) {}
        }
    }
}
