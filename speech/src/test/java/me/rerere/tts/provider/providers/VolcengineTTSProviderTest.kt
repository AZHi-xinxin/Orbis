package me.rerere.tts.provider.providers

import me.rerere.tts.provider.TTSProviderSetting
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VolcengineTTSProviderTest {
    private val setting = TTSProviderSetting.Volcengine(apiKey = "test-only", speaker = "test-voice")
    @Test fun newAuthAndCorrectResource() {
        val request = volcengineTtsRequest(setting, "test")
        assertEquals("test-only", request.header("X-Api-Key"))
        assertNull(request.header("X-Api-Access-Key"))
        assertEquals("seed-tts-2.0", request.header("X-Api-Resource-Id"))
        assertTrue(request.url.encodedPath.endsWith("/unidirectional/sse"))
        val buf = okio.Buffer(); request.body!!.writeTo(buf)
        val params = Json.parseToJsonElement(buf.readUtf8()).jsonObject.getValue("req_params").jsonObject
        assertEquals("test-voice", params.getValue("speaker").jsonPrimitive.content)
        assertFalse(params.containsKey("model"))
    }
    @Test fun legacyAuthDoesNotSendNewKey() {
        val request = volcengineTtsRequest(setting.copy(legacyAuth=true, appId="test-app", accessToken="test-token"), "test")
        assertNull(request.header("X-Api-Key"))
        assertEquals("test-token", request.header("X-Api-Access-Key"))
    }
    @Test fun completeStreamRetainsAllPcmBytes() {
        val decoder=VolcengineTtsDecoder(setting)
        assertArrayEquals(byteArrayOf(1,2), decoder.decode("""{"code":0,"data":"AQI="}""")!!.data)
        assertNull(decoder.decode("""{"code":0,"data":null,"sentence":{}}"""))
        assertTrue(decoder.decode("""{"code":20000000,"data":null}""")!!.isLast)
        assertTrue(decoder.finished)
    }
    @Test fun malformedAndServerErrorsFailClosed() {
        for (data in listOf("broken", """{"code":45000000,"message":"private"}""", """{"code":0,"data":"%%"}""", """{"code":20000000}""")) {
            val error = runCatching { VolcengineTtsDecoder(setting).decode(data) }.exceptionOrNull()
            assertNotNull(error); assertFalse(error!!.message.orEmpty().contains("private"))
        }
    }
    @Test fun insecureEndpointAndBlankCredentialsRejected() {
        assertTrue(runCatching { volcengineTtsRequest(setting.copy(baseUrl="http://example.com"), "test") }.isFailure)
        assertTrue(runCatching { volcengineTtsRequest(setting.copy(apiKey=""), "test") }.isFailure)
    }
}
