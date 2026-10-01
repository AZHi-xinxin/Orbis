package me.rerere.tts.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.rerere.tts.provider.TTSProviderSetting
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsApiTest {
    @Test fun v4IsOfferedWithoutChangingLegacyDefault() {
        assertTrue(ELEVENLABS_TTS_MODELS.any { it.first == "eleven_v4" })
        assertEquals("eleven_multilingual_v2", TTSProviderSetting.ElevenLabs().model)
    }

    @Test fun requestUsesExactModelAndVoiceAndAcceptsRootOrV1Base() {
        for (base in listOf("https://api.elevenlabs.io", "https://api.elevenlabs.io/", "https://api.elevenlabs.io/v1/")) {
            val request = elevenLabsSpeechRequest(TTSProviderSetting.ElevenLabs(baseUrl = base, model = "eleven_v4", voiceId = "my-voice", apiKey = "synthetic"), "测试")
            assertEquals("/v1/text-to-dialogue", request.url.encodedPath)
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals("eleven_v4", payload["model_id"]!!.jsonPrimitive.content)
            assertEquals("my-voice", payload["inputs"]!!.jsonArray.single().jsonObject["voice_id"]!!.jsonPrimitive.content)
            assertTrue(payload["settings"]!!.jsonObject.containsKey("similarity"))
            assertFalse(payload.containsKey("voice_settings"))
            assertEquals("synthetic", request.header("xi-api-key"))
        }
    }

    @Test fun modelDiscoveryFiltersNonTtsDeduplicatesAndDoesNotGuessIds() {
        val found = parseElevenLabsTtsModels("""[
            {"model_id":"eleven_v4","name":"Eleven v4","can_do_text_to_speech":true},
            {"model_id":"scribe_v2","can_do_text_to_speech":false},
            {"model_id":"unknown","name":"Eleven v99"},
            {"model_id":"new_real_id","can_do_text_to_speech":true},
            {"model_id":"eleven_v4","can_do_text_to_speech":true},
            {"model_id":"eleven_v4_turbo","can_do_text_to_speech":true}
        ]""")
        assertEquals(listOf("eleven_v4", "new_real_id"), found.map { it.first })
    }

    @Test fun manuallyTypedModelPassesThroughUnchanged() {
        val request = elevenLabsSpeechRequest(TTSProviderSetting.ElevenLabs(model = "future_api_model"), "test")
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("future_api_model"))
        assertEquals("/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb", request.url.encodedPath)
    }

    @Test(expected = IllegalArgumentException::class) fun v4OversizedDirectRequestIsNotTruncatedOrSent() {
        elevenLabsSpeechRequest(TTSProviderSetting.ElevenLabs(model = "eleven_v4"), "a".repeat(2_001))
    }

    @Test(expected = IllegalArgumentException::class) fun websocketOnlyVariantDoesNotPretendToWorkOverHttp() {
        elevenLabsSpeechRequest(TTSProviderSetting.ElevenLabs(model = "eleven_v4_turbo"), "test")
    }

    @Test(expected = IllegalArgumentException::class) fun credentialsInAddressAreRejected() {
        elevenLabsApiUrl("https://user:secret@example.test", "models")
    }
}
