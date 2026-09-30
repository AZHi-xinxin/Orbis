package me.rerere.rikkahub.ui.pages.chat

import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceCallAsrSettingsTest {
    @Test
    fun `three default call providers request 3000ms without changing dictation defaults`() {
        val openai = ASRProviderSetting.OpenAIRealtime()
        val qwen = ASRProviderSetting.DashScope()
        val volcengine = ASRProviderSetting.Volcengine()

        assertEquals(openai.copy(silenceDurationMs = 3_000), openai.forVoiceCallSilence())
        assertEquals(qwen.copy(silenceDurationMs = 3_000), qwen.forVoiceCallSilence())
        assertEquals(volcengine.copy(silenceDurationMs = 3_000), volcengine.forVoiceCallSilence())
        assertEquals(500, openai.silenceDurationMs)
        assertEquals(400, qwen.silenceDurationMs)
        assertEquals(800, volcengine.silenceDurationMs)
    }

    @Test
    fun `mapping below the call minimum never rounds down`() {
        listOf(0, 200, 500, 1_000, 2_999).forEach { silence ->
            val original = ASRProviderSetting.OpenAIRealtime(silenceDurationMs = silence)
            assertEquals(original.copy(silenceDurationMs = 3_000), original.forVoiceCallSilence())
            val qwen = ASRProviderSetting.DashScope(silenceDurationMs = silence)
            assertEquals(qwen.copy(silenceDurationMs = 3_000), qwen.forVoiceCallSilence())
            val volcengine = ASRProviderSetting.Volcengine(silenceDurationMs = silence)
            assertEquals(volcengine.copy(silenceDurationMs = 3_000), volcengine.forVoiceCallSilence())
        }
    }

    @Test
    fun `longer valid call pauses are preserved`() {
        listOf(3_000, 3_500, 5_000).forEach { silence ->
            val originals = listOf(
                ASRProviderSetting.OpenAIRealtime(silenceDurationMs = silence),
                ASRProviderSetting.DashScope(silenceDurationMs = silence),
                ASRProviderSetting.Volcengine(silenceDurationMs = silence),
            )
            originals.forEach { original ->
                val call = original.forVoiceCallSilence()
                assertEquals(original, call)
                assertNotSame(original, call)
                assertEquals(call, call.forVoiceCallSilence())
            }
        }
    }

    @Test
    fun `openai call copy preserves every field other than silence`() {
        val original = ASRProviderSetting.OpenAIRealtime(
            id = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001"),
            name = "Synthetic OpenAI ASR",
            apiKey = "synthetic-test-key",
            websocketUrl = "wss://example.invalid/realtime?intent=transcription",
            model = "gpt-4o-mini-transcribe",
            language = "zh",
            prompt = "Synthetic phrase",
            sampleRate = 24_000,
            vadThreshold = 0.7f,
            prefixPaddingMs = 450,
            silenceDurationMs = 1_200,
        )
        assertEquals(original.copy(silenceDurationMs = 3_000), original.forVoiceCallSilence())
        assertEquals(1_200, original.silenceDurationMs)
    }

    @Test
    fun `qwen call copy preserves every field other than silence`() {
        val original = ASRProviderSetting.DashScope(
            id = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000002"),
            name = "Synthetic Qwen ASR",
            apiKey = "synthetic-test-key",
            websocketUrl = "wss://example.invalid/realtime",
            model = "qwen3-asr-flash-realtime",
            language = "zh",
            sampleRate = 16_000,
            vadThreshold = 0.2f,
            silenceDurationMs = 900,
        )
        assertEquals(original.copy(silenceDurationMs = 3_000), original.forVoiceCallSilence())
        assertEquals(900, original.silenceDurationMs)
    }

    @Test
    fun `volcengine call copy preserves every field other than silence`() {
        val original = ASRProviderSetting.Volcengine(
            id = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000003"),
            name = "Synthetic Volcengine ASR",
            apiKey = "synthetic-test-key",
            websocketUrl = "wss://example.invalid/asr",
            resourceId = "synthetic-resource",
            language = "zh-CN",
            silenceDurationMs = 1_100,
        )
        assertEquals(original.copy(silenceDurationMs = 3_000), original.forVoiceCallSilence())
        assertEquals(1_100, original.silenceDurationMs)
    }

    @Test
    fun `qwen documented upper bound is accepted and overflow is not silently clamped`() {
        val upper = ASRProviderSetting.DashScope(silenceDurationMs = 6_000)
        assertEquals(upper, upper.forVoiceCallSilence())
        val overflow = upper.copy(silenceDurationMs = 6_001)
        val error = assertThrows(IllegalArgumentException::class.java) { overflow.forVoiceCallSilence() }
        assertTrue(error.message.orEmpty().contains("6000"))
        assertEquals(6_001, overflow.silenceDurationMs)
    }

    @Test
    fun `volcengine adapter upper bound is accepted and overflow is not silently clamped`() {
        val upper = ASRProviderSetting.Volcengine(silenceDurationMs = 5_000)
        assertEquals(upper, upper.forVoiceCallSilence())
        val overflow = upper.copy(silenceDurationMs = 5_001)
        val error = assertThrows(IllegalArgumentException::class.java) { overflow.forVoiceCallSilence() }
        assertTrue(error.message.orEmpty().contains("适配器"))
        assertTrue(error.message.orEmpty().contains("5000"))
        assertEquals(5_001, overflow.silenceDurationMs)
    }

    @Test
    fun `openai does not acquire an invented numeric upper bound`() {
        // Published field docs do not specify a maximum. Server acceptance is not asserted here.
        val original = ASRProviderSetting.OpenAIRealtime(silenceDurationMs = 6_000)
        assertEquals(original, original.forVoiceCallSilence())
    }

    @Test
    fun `non endpointing providers stay unsupported and unchanged`() {
        val mimo = ASRProviderSetting.MiMo(segmentDurationSec = 20)
        val step = ASRProviderSetting.Step(segmentDurationSec = 10)
        assertThrows(IllegalArgumentException::class.java) { mimo.forVoiceCallSilence() }
        assertThrows(IllegalArgumentException::class.java) { step.forVoiceCallSilence() }
        assertEquals(20, mimo.segmentDurationSec)
        assertEquals(10, step.segmentDurationSec)
    }
}
