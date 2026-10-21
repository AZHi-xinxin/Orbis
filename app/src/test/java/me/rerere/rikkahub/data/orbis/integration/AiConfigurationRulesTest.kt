package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class AiConfigurationRulesTest {
    private val humanModel = Model(modelId = "human-chat")
    private val helperModel = Model(modelId = "helper")
    private val imageModel = Model(modelId = "image", type = ModelType.IMAGE)
    private val provider = ProviderSetting.OpenAI(name = "Existing API", apiKey = "synthetic-credential",
        baseUrl = "https://api.example/v1", models = listOf(humanModel, helperModel, imageModel))
    private val owner = Assistant(name = "Owner", chatModelId = humanModel.id)
    private val other = Assistant(name = "Other", chatModelId = humanModel.id)
    private val tts = TTSProviderSetting.MiMo(apiKey = "synthetic-tts-secret", voice = "first")
    private val initial = Settings(assistants = listOf(owner, other), providers = listOf(provider), ttsProviders = listOf(tts))
    private val id get() = owner.id.toString()

    @Test fun registersModelUsingExistingApiWithoutModifyingSecretsOrExistingModels() {
        val saved = AiConfigurationRules.registerModel(initial, id, provider.id.toString(), "new-fast", "New fast", supportsTools = true)
        val updated = saved.providers.single() as ProviderSetting.OpenAI
        assertEquals(provider.apiKey, updated.apiKey); assertEquals(provider.baseUrl, updated.baseUrl)
        assertEquals(provider.models, updated.models.take(3))
        assertEquals(listOf(ModelAbility.TOOL), updated.models.last().abilities)
        assertEquals(saved, AiConfigurationRules.registerModel(saved, id, provider.id.toString(), "new-fast", "Another title"))
        assertEquals(initial.assistants, saved.assistants)
    }
    @Test fun chatSelectionIsOwnerScopedAndToolSlotsUseNativeGlobalFields() {
        val own = AiConfigurationRules.selectModel(initial, id, "chat", helperModel.id.toString())
        assertEquals(helperModel.id, own.assistants.first().chatModelId)
        assertEquals(other, own.assistants.last()); assertEquals(initial.chatModelId, own.chatModelId)
        val cases = listOf("fast", "translation", "ocr", "compression", "voice_archive", "voice_archive_fallback")
        cases.forEach { role ->
            val changed = AiConfigurationRules.selectModel(initial, id, role, helperModel.id.toString())
            assertEquals(helperModel.id, AiConfigurationRules.selectedModels(changed, id)[role])
            assertEquals(initial.assistants, changed.assistants)
        }
        assertEquals(imageModel.id, AiConfigurationRules.selectModel(initial, id, "image", imageModel.id.toString()).imageGenerationModelId)
    }
    @Test fun wrongTypeDisabledProviderMissingOwnerCannotSelect() {
        assertTrue(runCatching { AiConfigurationRules.selectModel(initial, id, "fast", imageModel.id.toString()) }.isFailure)
        assertTrue(runCatching { AiConfigurationRules.selectModel(initial, id, "image", helperModel.id.toString()) }.isFailure)
        assertTrue(runCatching { AiConfigurationRules.selectModel(initial.copy(providers = listOf(provider.copy(enabled = false))),
            id, "fast", helperModel.id.toString()) }.isFailure)
        assertTrue(runCatching { AiConfigurationRules.selectModel(initial.copy(assistants = listOf(other)), id, "chat", helperModel.id.toString()) }.isFailure)
        assertTrue(runCatching { AiConfigurationRules.registerModel(initial, id, provider.id.toString(), "bad\nmodel", "") }.isFailure)
    }
    @Test fun ttsEditsPreserveKeysUrlsMixerAndSelectionUntilExplicitlySelected() {
        val changed = AiConfigurationRules.updateTts(initial, id, tts.id.toString(), "new-tts", "second")
        val result = changed.ttsProviders.single() as TTSProviderSetting.MiMo
        assertEquals(tts.apiKey, result.apiKey); assertEquals(tts.baseUrl, result.baseUrl)
        assertEquals("new-tts", result.model); assertEquals("second", result.voice)
        assertEquals(initial.voiceMixer, changed.voiceMixer)
        assertEquals(initial.selectedTTSProviderId, changed.selectedTTSProviderId)
        assertEquals(tts.id, AiConfigurationRules.selectTts(changed, id, tts.id.toString()).selectedTTSProviderId)
    }
    @Test fun allNativeNetworkTtsVoiceFieldsAreSupportedWithoutRevealingSecrets() {
        val providers = listOf<TTSProviderSetting>(TTSProviderSetting.OpenAI(), TTSProviderSetting.Gemini(),
            TTSProviderSetting.MiniMax(), TTSProviderSetting.Qwen(), TTSProviderSetting.Groq(), TTSProviderSetting.MiMo(),
            TTSProviderSetting.ElevenLabs(), TTSProviderSetting.Step(), TTSProviderSetting.FishAudio(),
            TTSProviderSetting.XAI(), TTSProviderSetting.Volcengine())
        providers.forEach { native ->
            val result = AiConfigurationRules.updateTts(initial.copy(ttsProviders = listOf(native)), id, native.id.toString(), null, "new-voice")
            assertEquals("new-voice", AiConfigurationRules.ttsSummary(result.ttsProviders.single())["voice"]!!.jsonPrimitive.content)
        }
        val system = TTSProviderSetting.SystemTTS()
        assertTrue(runCatching { AiConfigurationRules.updateTts(initial.copy(ttsProviders = listOf(system)), id, system.id.toString(), null, "voice") }.isFailure)
    }
    @Test fun sameServiceTtsReusesOnlyNativeCredentialWithoutChangingChatOrAutomaticallySelecting() {
        val result = AiConfigurationRules.ttsFromProvider(initial, id, provider.id.toString(), "tts-v1", "voice-1", "My voice")
        val created = result.ttsProviders.filterIsInstance<TTSProviderSetting.OpenAI>().single()
        assertEquals(provider.apiKey, created.apiKey); assertEquals(provider.baseUrl, created.baseUrl)
        assertTrue(created.strictEndpoint)
        assertEquals(initial.selectedTTSProviderId, result.selectedTTSProviderId)
        assertEquals(initial.providers, result.providers)
        assertEquals(initial.assistants, result.assistants)
        assertFalse(AiConfigurationRules.inspect(result, id).toString().contains(provider.apiKey))
    }
    @Test fun accountTokensAndRotatingKeysAreNotCopiedToTts() {
        listOf(provider.copy(chatGptAccountId = "account"), provider.copy(apiKey = "first,second"), provider.copy(enabled = false)).forEach {
            assertTrue(runCatching { AiConfigurationRules.ttsFromProvider(initial.copy(providers = listOf(it)), id,
                provider.id.toString(), "tts", "voice", "TTS") }.isFailure)
        }
    }
    @Test fun localMemoryConfigurationKeepsOtherAssistantAndExplicitSemanticOptOut() {
        val source = initial.copy(assistants = listOf(owner.copy(orbisSemanticRecall = false), other))
        val changed = AiConfigurationRules.setMemory(source, id, "standard", true)
        assertEquals(OrbisMemoryMode.STANDARD, changed.assistants.first().orbisMemoryMode)
        assertFalse(changed.assistants.first().orbisSemanticRecall)
        assertEquals(other, changed.assistants.last())
        val semantic = AiConfigurationRules.setMemory(changed, id, "independent", true, true)
        assertTrue(semantic.assistants.first().orbisSemanticRecall)
        assertTrue(runCatching { AiConfigurationRules.setMemory(initial, id, "all", true) }.isFailure)
    }
    @Test fun inspectAndCheckErrorsNeverExposeCredentialsHeadersOrProviderMessages() {
        val server = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(
            name = "MCP", headers = listOf("Authorization" to "Bearer synthetic-mcp-secret")), url = "https://mcp.example")
        val settings = initial.copy(mcpServers = listOf(server), assistants = listOf(owner.copy(mcpServers = setOf(server.id)), other))
        val receipt = AiConfigurationRules.inspect(settings, id).toString()
        listOf("synthetic-credential", "synthetic-tts-secret", "synthetic-mcp-secret", "Authorization", "apiKey").forEach {
            assertFalse(receipt.contains(it))
        }
        val failure = AiConfigurationChecks.failure(HttpException("secret-reply", httpStatus = 401, rejectedParameter = "model"))
        assertEquals(401, failure["http_status"]!!.jsonPrimitive.int)
        assertFalse(failure.toString().contains("secret-reply"))
        assertFalse(AiConfigurationChecks.failure(IllegalStateException("token=secret")).toString().contains("secret"))
    }
}
