package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallPreflightTest {
    private val key = "synthetic-test-key"
    private val model = Model(modelId = "synthetic-chat")
    private val assistant = Assistant(chatModelId = model.id)
    private val selectedAsr = ASRProviderSetting.MiMo(apiKey = key)
    private val selectedTts = TTSProviderSetting.MiniMax(apiKey = key)
    private fun configured(asr: ASRProviderSetting = selectedAsr) = Settings(
        providers = listOf(ProviderSetting.OpenAI(baseUrl = "http://localhost:1234/v1", models = listOf(model))),
        assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id,
        asrProviders = listOf(asr), selectedASRProviderId = asr.id,
        ttsProviders = listOf(selectedTts), selectedTTSProviderId = selectedTts.id,
    )

    @Test fun `all five current ASR adapters pass their own complete settings`() {
        val providers = listOf(ASRProviderSetting.OpenAIRealtime(apiKey = key), ASRProviderSetting.DashScope(apiKey = key),
            ASRProviderSetting.Volcengine(apiKey = key), selectedAsr, ASRProviderSetting.Step(apiKey = key))
        providers.forEach { assertNull(it.name, OrbisVoiceCallPreflight.asrFailure(it)) }
    }

    @Test fun `MiMo and Step need not claim server VAD to permit calls`() {
        listOf(selectedAsr, ASRProviderSetting.Step(apiKey = key)).forEach {
            assertFalse(it.supportsServerVadVoiceMode)
            assertTrue(it.supportsVoiceCall)
            assertNull(OrbisVoiceCallPreflight.firstFailure(configured(it), assistant))
        }
    }

    @Test fun `missing selected providers are diagnosed without network`() {
        assertEquals(OrbisCallFailure.ASR_MISSING, OrbisVoiceCallPreflight.asrFailure(null))
        assertEquals(OrbisCallFailure.TTS_MISSING, OrbisVoiceCallPreflight.ttsFailure(null))
        assertEquals(OrbisCallFailure.MODEL_MISSING, OrbisVoiceCallPreflight.modelFailure(null, null))
    }

    @Test fun `real selected ASR wins over another working provider in list`() {
        val invalid = ASRProviderSetting.Step(apiKey = key, model = "")
        val settings = configured().copy(asrProviders = listOf(selectedAsr, invalid), selectedASRProviderId = invalid.id)
        assertEquals(OrbisCallFailure.ASR_MODEL, OrbisVoiceCallPreflight.firstFailure(settings, assistant))
    }

    @Test fun `answer recheck detects settings removed after a successful ring preflight`() {
        val atRing = configured()
        assertNull(OrbisVoiceCallPreflight.firstFailure(atRing, assistant))
        val atAnswer = atRing.copy(asrProviders = emptyList())
        assertEquals(OrbisCallFailure.ASR_MISSING, OrbisVoiceCallPreflight.firstFailure(atAnswer, assistant))
    }

    @Test fun `answer can use a new complete selected adapter`() {
        val atAnswer = configured(ASRProviderSetting.Step(apiKey = key))
        assertNull(OrbisVoiceCallPreflight.firstFailure(atAnswer, assistant))
    }

    @Test fun `missing official credential is a specific local failure`() {
        assertEquals(OrbisCallFailure.ASR_CREDENTIAL, OrbisVoiceCallPreflight.asrFailure(ASRProviderSetting.MiMo()))
        assertEquals(OrbisCallFailure.TTS_CREDENTIAL, OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.MiniMax()))
    }

    @Test fun `custom local endpoints may intentionally omit credentials`() {
        assertNull(OrbisVoiceCallPreflight.asrFailure(ASRProviderSetting.MiMo(baseUrl = "http://localhost:9000/v1")))
        assertNull(OrbisVoiceCallPreflight.asrFailure(ASRProviderSetting.OpenAIRealtime(websocketUrl = "ws://localhost:9000/realtime")))
        assertNull(OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.OpenAI(baseUrl = "http://localhost:9000/v1")))
        assertNull(OrbisVoiceCallPreflight.modelFailure(model, ProviderSetting.OpenAI(baseUrl = "http://localhost:9000/v1")))
    }

    @Test fun `endpoint validation distinguishes websocket and batch protocols`() {
        assertEquals(OrbisCallFailure.ASR_ENDPOINT, OrbisVoiceCallPreflight.asrFailure(selectedAsr.copy(baseUrl = "wss://example.invalid/asr")))
        assertEquals(OrbisCallFailure.ASR_ENDPOINT, OrbisVoiceCallPreflight.asrFailure(ASRProviderSetting.OpenAIRealtime(apiKey = key, websocketUrl = "https://example.invalid/asr")))
        assertEquals(OrbisCallFailure.TTS_ENDPOINT, OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.Qwen(apiKey = key)))
    }

    @Test fun `malformed endpoint cannot leak user supplied content to presentation`() {
        val privateValue = "private-url-and-secret-do-not-display"
        val failure = OrbisVoiceCallPreflight.asrFailure(selectedAsr.copy(baseUrl = privateValue))!!
        assertEquals(OrbisCallFailure.ASR_ENDPOINT, failure)
        assertFalse(failure.explanation.contains(privateValue))
        assertFalse(failure.code.contains(privateValue))
    }

    @Test fun `empty model voice and resource fields fail before ring`() {
        assertEquals(OrbisCallFailure.ASR_MODEL, OrbisVoiceCallPreflight.asrFailure(ASRProviderSetting.Volcengine(apiKey = key, resourceId = "")))
        assertEquals(OrbisCallFailure.TTS_MODEL, OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.MiniMax(apiKey = key, voiceId = "")))
        assertEquals(OrbisCallFailure.MODEL_CONFIGURATION, OrbisVoiceCallPreflight.modelFailure(model.copy(modelId = ""), ProviderSetting.OpenAI()))
        assertEquals(OrbisCallFailure.MODEL_CONFIGURATION, OrbisVoiceCallPreflight.modelFailure(model, null))
    }

    @Test fun `model provider overwrite is checked not ignored`() {
        val brokenModel = model.copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = ""))
        val settings = configured().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(brokenModel))))
        assertEquals(OrbisCallFailure.MODEL_CONFIGURATION, OrbisVoiceCallPreflight.firstFailure(settings, assistant))
    }

    @Test fun `batch sample rates cover existing settings but reject unusable values`() {
        listOf(8000, 16000, 24000, 32000, 44100, 48000).forEach {
            assertNull(OrbisVoiceCallPreflight.asrFailure(selectedAsr.copy(sampleRate = it)))
        }
        assertEquals(OrbisCallFailure.ASR_SAMPLE_RATE, OrbisVoiceCallPreflight.asrFailure(selectedAsr.copy(sampleRate = 0)))
        assertEquals(OrbisCallFailure.ASR_SAMPLE_RATE, OrbisVoiceCallPreflight.asrFailure(selectedAsr.copy(sampleRate = 12345)))
    }

    @Test fun `local system TTS needs no remote credentials`() {
        assertNull(OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.SystemTTS()))
    }

    @Test fun `Fish default voice does not require reference id`() {
        assertNull(OrbisVoiceCallPreflight.ttsFailure(TTSProviderSetting.FishAudio(apiKey = key, referenceId = "")))
    }

    @Test fun `all failure codes are stable ledger-safe and have readable reasons`() {
        assertEquals(OrbisCallFailure.entries.size, OrbisCallFailure.entries.map { it.code }.distinct().size)
        OrbisCallFailure.entries.forEach {
            assertTrue(it.code.matches(Regex("[a-z0-9_]{1,100}")))
            assertTrue(it.explanation.isNotBlank())
            assertSame(it, OrbisCallFailure.fromCode(it.code))
        }
    }

    @Test fun `legacy and unknown diagnostics never echo raw error body`() {
        assertSame(OrbisCallFailure.LEGACY_CONNECTION_FAILED, OrbisCallFailure.fromCode("connection_failed"))
        assertSame(OrbisCallFailure.UNKNOWN, OrbisCallFailure.fromCode("upstream response with a private token"))
        assertSame(OrbisCallFailure.UNKNOWN, OrbisCallFailure.fromCode(null))
    }

    @Test fun `typed exception contains only fixed failure code`() {
        val error = OrbisCallStartException(OrbisCallFailure.ASR_MODEL)
        assertEquals("voice_asr_model_missing", error.message)
        assertNull(error.cause)
    }
}
