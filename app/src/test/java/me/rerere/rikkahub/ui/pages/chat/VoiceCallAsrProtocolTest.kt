package me.rerere.rikkahub.ui.pages.chat

import me.rerere.asr.ASRProviderSetting
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercise the real speech-module request builders without constructing a recorder or WebSocket.
 * Reflection is restricted to these tests because two builders are private and one is internal in
 * another module; production visibility is intentionally not widened just for a protocol assertion.
 */
class VoiceCallAsrProtocolTest {
    @Test
    fun `openai actual session update requests 3000ms server vad only for calls`() {
        val saved = ASRProviderSetting.OpenAIRealtime(language = "zh")
        val event = sessionUpdate("OpenAIRealtimeASRControllerKt", saved.forVoiceCallSilence())
        assertEquals("session.update", event.getString("type"))
        assertEquals("transcription", event.getJSONObject("session").getString("type"))
        val input = event.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertEquals("server_vad", input.getJSONObject("turn_detection").getString("type"))
        assertEquals(3_000, input.getJSONObject("turn_detection").getInt("silence_duration_ms"))
        assertEquals(saved.model, input.getJSONObject("transcription").getString("model"))
        assertEquals("zh", input.getJSONObject("transcription").getString("language"))
        assertEquals(300, input.getJSONObject("turn_detection").getInt("prefix_padding_ms"))

        val dictation = sessionUpdate("OpenAIRealtimeASRControllerKt", saved)
        assertEquals(500, dictation.getJSONObject("session").getJSONObject("audio")
            .getJSONObject("input").getJSONObject("turn_detection").getInt("silence_duration_ms"))
    }

    @Test
    fun `qwen actual session update requests 3000ms server vad only for calls`() {
        val saved = ASRProviderSetting.DashScope(language = "zh")
        val event = sessionUpdate("DashScopeASRControllerKt", saved.forVoiceCallSilence())
        assertEquals("session.update", event.getString("type"))
        val session = event.getJSONObject("session")
        assertEquals("server_vad", session.getJSONObject("turn_detection").getString("type"))
        assertEquals(3_000, session.getJSONObject("turn_detection").getInt("silence_duration_ms"))
        assertEquals(16_000, session.getInt("sample_rate"))
        assertEquals("zh", session.getJSONObject("input_audio_transcription").getString("language"))

        val dictation = sessionUpdate("DashScopeASRControllerKt", saved)
        assertEquals(400, dictation.getJSONObject("session")
            .getJSONObject("turn_detection").getInt("silence_duration_ms"))
    }

    @Test
    fun `volcengine actual request enables endpointing and sends 3000ms without touching dictation`() {
        val saved = ASRProviderSetting.Volcengine(language = "zh-CN")
        val event = volcengineRequest(saved.forVoiceCallSilence() as ASRProviderSetting.Volcengine)
        val request = event.getJSONObject("request")
        assertTrue(request.getBoolean("enable_nonstream"))
        assertEquals(3_000, request.getInt("end_window_size"))
        assertEquals("full", request.getString("result_type"))
        assertFalse(request.has("force_to_speech_time"))
        assertEquals("zh-CN", event.getJSONObject("audio").getString("language"))
        assertEquals(800, volcengineRequest(saved).getJSONObject("request").getInt("end_window_size"))
    }

    @Test
    fun `actual request builders preserve supported longer call pauses`() {
        val openai = ASRProviderSetting.OpenAIRealtime(silenceDurationMs = 4_500)
        assertEquals(4_500, sessionUpdate("OpenAIRealtimeASRControllerKt", openai.forVoiceCallSilence())
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
            .getJSONObject("turn_detection").getInt("silence_duration_ms"))
        val qwen = ASRProviderSetting.DashScope(silenceDurationMs = 6_000)
        assertEquals(6_000, sessionUpdate("DashScopeASRControllerKt", qwen.forVoiceCallSilence())
            .getJSONObject("session").getJSONObject("turn_detection").getInt("silence_duration_ms"))
        val volcengine = ASRProviderSetting.Volcengine(silenceDurationMs = 5_000)
        assertEquals(5_000, volcengineRequest(volcengine.forVoiceCallSilence() as ASRProviderSetting.Volcengine)
            .getJSONObject("request").getInt("end_window_size"))
    }

    private fun sessionUpdate(fileClassName: String, provider: ASRProviderSetting): JSONObject {
        val fileClass = Class.forName("me.rerere.asr.providers.$fileClassName")
        val method = fileClass.getDeclaredMethod("sessionUpdateEvent", provider.javaClass)
        method.isAccessible = true
        return method.invoke(null, provider) as JSONObject
    }

    private fun volcengineRequest(provider: ASRProviderSetting.Volcengine): JSONObject {
        val protocol = Class.forName("me.rerere.asr.providers.VolcengineASRProtocol")
        val instance = protocol.getField("INSTANCE").get(null)
        val method = protocol.getDeclaredMethod("request", ASRProviderSetting.Volcengine::class.java)
        method.isAccessible = true
        return JSONObject((method.invoke(instance, provider) as ByteArray).toString(Charsets.UTF_8))
    }
}
