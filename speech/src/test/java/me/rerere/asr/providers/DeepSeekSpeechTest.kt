package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.DeepSeekSpeechFrames
import me.rerere.tts.provider.providers.deepSeekSpeechText
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSpeechTest {
    @Test fun `ASR uploads a WAV file with official authentication and no invented model parameter`() {
        val request = batchVoiceRequest(ASRProviderSetting.DeepSeek(apiKey="synthetic-key"), byteArrayOf(0,0,1,0))
        assertEquals("https://api.deepseek.com/v1/tools/asr",request.url.toString())
        assertEquals("Bearer synthetic-key",request.header("Authorization"))
        val body=request.body as MultipartBody
        assertEquals(1,body.parts.size)
        val data=Buffer().apply { body.parts.single().body.writeTo(this) }.readByteArray()
        assertEquals("RIFF",data.copyOfRange(0,4).decodeToString())
        assertEquals(48,data.size)
        assertEquals(16000,java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertEquals("你好",parseSiliconFlowResponse("{\"text\":\"你好\",\"duration_ms\":10}"))
    }
    @Test fun `provider selections roundtrip and do not claim server VAD`() {
        val asr:ASRProviderSetting=ASRProviderSetting.DeepSeek(apiKey="synthetic")
        assertEquals(asr,Json.decodeFromString<ASRProviderSetting>(Json.encodeToString(ASRProviderSetting.serializer(),asr)))
        assertTrue(asr.supportsVoiceCall);assertFalse(asr.supportsServerVadVoiceMode)
        val tts:TTSProviderSetting=TTSProviderSetting.DeepSeek(voice="tide")
        assertEquals(tts,Json.decodeFromString<TTSProviderSetting>(Json.encodeToString(TTSProviderSetting.serializer(),tts)))
    }
    @Test fun `stream succeeds only with an explicit complete audio finish`() {
        val frames=DeepSeekSpeechFrames()
        assertFalse(frames.audio(byteArrayOf(0,0)).isLast)
        assertTrue(frames.finish("{\"event\":\"finish\",\"code\":\"success\"}").isLast)
        assertTrue(frames.finished)
        assertThrows(IllegalStateException::class.java) { frames.audio(byteArrayOf(1,0)) }
    }
    @Test fun `error empty and odd audio never become successful speech`() {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(0))) {
            val frames=DeepSeekSpeechFrames();frames.audio(bytes)
            assertThrows(IllegalStateException::class.java) { frames.finish("{\"event\":\"finish\",\"code\":\"success\"}") }
            assertFalse(frames.finished)
        }
        val frames=DeepSeekSpeechFrames();frames.audio(byteArrayOf(0,0))
        assertThrows(IllegalStateException::class.java) { frames.finish("{\"event\":\"finish\",\"code\":\"content_filter\"}") }
        assertThrows(IllegalArgumentException::class.java) { deepSeekSpeechText(" ") }
        assertThrows(IllegalArgumentException::class.java) { deepSeekSpeechText("字".repeat(5001)) }
    }
}
