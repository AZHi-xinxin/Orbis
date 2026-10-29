package me.rerere.rikkahub.data.orbis.integration

import java.util.UUID
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** Configuration receipts are a whitelist, never serialized Settings/provider objects. */
internal object AiConfigurationRules {
    val modelRoles = setOf("chat", "fast", "translation", "ocr", "compression", "image", "voice_archive", "voice_archive_fallback")
    fun requireOwner(settings: Settings, owner: String) = settings.assistants.singleOrNull { it.id.toString() == owner }
        .also { check(!settings.init && it != null) { "assistant_unavailable" } }!!

    fun selectedModels(settings: Settings, owner: String): Map<String, Uuid?> = linkedMapOf(
        "chat" to (requireOwner(settings, owner).chatModelId ?: settings.chatModelId),
        "fast" to settings.fastModelId, "translation" to settings.translateModeId,
        "ocr" to settings.ocrModelId, "compression" to settings.compressModelId,
        "image" to settings.imageGenerationModelId, "voice_archive" to settings.orbisVoiceArchiveModelId,
        "voice_archive_fallback" to settings.orbisVoiceArchiveFallbackModelId,
    )

    fun inspect(settings: Settings, owner: String, includeCatalog: Boolean = true): JsonObject {
        val assistant = requireOwner(settings, owner)
        return buildJsonObject {
            put("ok", true)
            put("assistant_id", owner)
            put("workspace_id", assistant.workspaceId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("workspace_prepare", assistant.workspaceId == null)
            put("selected_models", buildJsonObject {
                selectedModels(settings, owner).forEach { (role, id) -> put(role, id?.let { JsonPrimitive(it.toString()) } ?: JsonNull) }
            })
            put("tool_model_scope", "application")
            val currentId = assistant.chatModelId ?: settings.chatModelId
            val provider = settings.providers.firstOrNull { p -> p.models.any { it.id == currentId } }
            val model = provider?.models?.firstOrNull { it.id == currentId }
            val effective = model?.providerOverwrite ?: provider
            if (effective is ProviderSetting.OpenAI && model != null) {
                val publicUrl = runCatching { ConnectionDraft("model_openai", "Current", effective.baseUrl, model.modelId).normalized().endpoint }.getOrNull()
                put("current_model_connection", buildJsonObject {
                    put("model", model.modelId); put("provider_id", checkNotNull(provider).id.toString())
                    put("base_url", publicUrl?.let(::JsonPrimitive) ?: JsonNull)
                    put("supports_tools", ModelAbility.TOOL in model.abilities)
                    put("supports_images", Modality.IMAGE in model.inputModalities)
                    put("credential_returned", false)
                })
            }
            put("selected_tts_id", settings.selectedTTSProviderId.toString())
            if (includeCatalog) put("providers", JsonArray(settings.providers.map { provider -> buildJsonObject {
                put("id", provider.id.toString()); put("name", provider.name.take(160)); put("enabled", provider.enabled)
                put("kind", when (provider) { is ProviderSetting.OpenAI -> "openai"; is ProviderSetting.Google -> "google"; is ProviderSetting.Claude -> "claude" })
                put("models", JsonArray(provider.models.map { model -> buildJsonObject {
                    put("id", model.id.toString()); put("model", model.modelId.take(240)); put("name", model.displayName.take(160))
                    put("type", model.type.name.lowercase())
                    put("supports_tools", ModelAbility.TOOL in model.abilities)
                    put("supports_images", Modality.IMAGE in model.inputModalities)
                } }))
            } }))
            if (includeCatalog) put("tts", JsonArray(settings.ttsProviders.map(::ttsSummary)))
            put("memory", buildJsonObject {
                put("mode", assistant.orbisMemoryMode.name.lowercase()); put("auto_inject", assistant.orbisMemoryAutoInject)
                put("semantic_recall", assistant.orbisSemanticRecall)
            })
            if (includeCatalog) put("mcp", JsonArray(settings.mcpServers.filter { it.id in assistant.mcpServers }.map { server -> buildJsonObject {
                put("id", server.id.toString()); put("name", server.commonOptions.displayTitle.take(160))
                put("enabled", server.commonOptions.enable); put("tool_count", server.commonOptions.tools.count { it.enable })
            } }))
            put("backup_tool", "orbis_backup")
        }
    }

    fun selectModel(settings: Settings, owner: String, role: String, id: String): Settings {
        requireOwner(settings, owner); require(role in modelRoles) { "unknown_role" }
        val uuid = Uuid.parse(id)
        val containing = settings.providers.singleOrNull { p -> p.models.any { it.id == uuid } }
            ?: error("model_missing")
        check(containing.enabled) { "provider_disabled" }
        val model = containing.models.single { it.id == uuid }
        val effective = model.providerOverwrite ?: containing
        check(effective.enabled) { "provider_disabled" }
        require(if (role == "image") model.type == ModelType.IMAGE else model.type == ModelType.CHAT) { "model_type_mismatch" }
        return when (role) {
            "chat" -> settings.copy(assistants = settings.assistants.map { if (it.id.toString() == owner) it.copy(chatModelId = uuid) else it })
            "fast" -> settings.copy(fastModelId = uuid)
            "translation" -> settings.copy(translateModeId = uuid)
            "ocr" -> settings.copy(ocrModelId = uuid)
            "compression" -> settings.copy(compressModelId = uuid)
            "image" -> settings.copy(imageGenerationModelId = uuid)
            "voice_archive" -> settings.copy(orbisVoiceArchiveModelId = uuid)
            else -> settings.copy(orbisVoiceArchiveFallbackModelId = uuid)
        }
    }

    fun registerModel(settings: Settings, owner: String, providerId: String, model: String, name: String,
        type: String = "chat", supportsTools: Boolean = true): Settings {
        requireOwner(settings, owner)
        require(model.isNotBlank() && model.length <= 240 && model.none(Char::isISOControl)) { "invalid_model" }
        require(name.length <= 160 && name.none(Char::isISOControl)) { "invalid_name" }
        val kind = when (type) { "chat" -> ModelType.CHAT; "image" -> ModelType.IMAGE; "embedding" -> ModelType.EMBEDDING; else -> error("model_type_mismatch") }
        val provider = settings.providers.singleOrNull { it.id.toString() == providerId } ?: error("provider_missing")
        check(provider.enabled) { "provider_disabled" }
        // Existing provider credentials and custom settings never enter tool input or output.
        if (provider.models.any { it.modelId == model && it.type == kind }) return settings
        require(provider.models.size < 1000) { "model_limit" }
        val id = Uuid.parse(UUID.nameUUIDFromBytes("orbis-ai-model-v1\n$owner\n$providerId\n$type\n$model".toByteArray(Charsets.UTF_8)).toString())
        val entry = Model(id = id, modelId = model, displayName = name.ifBlank { model }, type = kind,
            abilities = if (supportsTools && kind == ModelType.CHAT) listOf(ModelAbility.TOOL) else emptyList())
        return settings.copy(providers = settings.providers.map { if (it.id == provider.id) it.addModel(entry) else it })
    }

    fun selectTts(settings: Settings, owner: String, id: String): Settings {
        requireOwner(settings, owner)
        val selected = settings.ttsProviders.singleOrNull { it.id.toString() == id } ?: error("tts_missing")
        return settings.copy(selectedTTSProviderId = selected.id)
    }

    /** Reuse a human's existing single API credential only at the identical service URL. */
    fun ttsFromProvider(settings: Settings, owner: String, providerId: String, model: String, voice: String, name: String): Settings {
        requireOwner(settings, owner)
        val provider = settings.providers.singleOrNull { it.id.toString() == providerId } as? ProviderSetting.OpenAI
            ?: error("openai_provider_required")
        check(provider.enabled && provider.chatGptAccountId == null) { "provider_not_reusable" }
        require(provider.apiKey.isBlank() || provider.apiKey.trim().split(Regex("[\\s,]+")).size == 1) { "single_key_required" }
        val draft = ConnectionDraft("tts_openai", name, provider.baseUrl, model, voice,
            if (provider.apiKey.isBlank()) "none" else "bearer").normalized()
        val proposed = ConnectionProposalRules.propose(settings, owner, draft)
        return ConnectionProposalRules.approve(proposed, draft.id(owner), provider.apiKey, true)
    }

    fun setMemory(settings: Settings, owner: String, mode: String, autoInject: Boolean, semanticRecall: Boolean? = null): Settings {
        requireOwner(settings, owner)
        val tier = when (mode) {
            "light" -> OrbisMemoryMode.LIGHT; "standard" -> OrbisMemoryMode.STANDARD
            "independent" -> OrbisMemoryMode.INDEPENDENT; else -> error("invalid_memory_mode")
        }
        return settings.copy(assistants = settings.assistants.map {
            if (it.id.toString() == owner) it.copy(orbisMemoryMode = tier, orbisMemoryAutoInject = autoInject,
                orbisSemanticRecall = semanticRecall ?: it.orbisSemanticRecall) else it
        })
    }

    /** Model and voice only: URLs, credentials, generation parameters and mixer remain untouched. */
    fun updateTts(settings: Settings, owner: String, id: String, model: String?, voice: String?): Settings {
        requireOwner(settings, owner)
        require(model != null || voice != null) { "missing_change" }
        listOfNotNull(model, voice).forEach { require(it.isNotBlank() && it.length <= 240 && it.none(Char::isISOControl)) { "invalid_tts_value" } }
        val old = settings.ttsProviders.singleOrNull { it.id.toString() == id } ?: error("tts_missing")
        val updated = when (old) {
            is TTSProviderSetting.OpenAI -> old.copy(model = model ?: old.model, voice = voice ?: old.voice)
            is TTSProviderSetting.Gemini -> old.copy(model = model ?: old.model, voiceName = voice ?: old.voiceName)
            is TTSProviderSetting.MiniMax -> old.copy(model = model ?: old.model, voiceId = voice ?: old.voiceId)
            is TTSProviderSetting.Qwen -> old.copy(model = model ?: old.model, voice = voice ?: old.voice)
            is TTSProviderSetting.Groq -> old.copy(model = model ?: old.model, voice = voice ?: old.voice)
            is TTSProviderSetting.MiMo -> old.copy(model = model ?: old.model, voice = voice ?: old.voice)
            is TTSProviderSetting.ElevenLabs -> old.copy(model = model ?: old.model, voiceId = voice ?: old.voiceId)
            is TTSProviderSetting.Step -> old.copy(model = model ?: old.model, voice = voice ?: old.voice)
            is TTSProviderSetting.FishAudio -> old.copy(model = model ?: old.model, referenceId = voice ?: old.referenceId)
            is TTSProviderSetting.Volcengine -> old.copy(resourceId = model ?: old.resourceId, speaker = voice ?: old.speaker)
            is TTSProviderSetting.XAI -> { require(model == null) { "tts_has_no_model_field" }; old.copy(voiceId = voice ?: old.voiceId) }
            is TTSProviderSetting.SystemTTS -> error("system_tts_uses_system_voice")
        }
        // Keep an owned proposal aligned with this authorized native edit; no credentials are recorded.
        return settings.copy(ttsProviders = settings.ttsProviders.map { if (it.id == old.id) updated else it },
            connectionProposals = settings.connectionProposals.map { p ->
                if (p.owner == owner && p.id == id && p.installed != null && p.draft.kind == "tts_openai" &&
                    ConnectionProposalRules.matchesInstalled(settings, p)) {
                    val draft = p.draft.copy(model = model ?: p.draft.model, voice = voice ?: p.draft.voice)
                    p.copy(draft = draft, installed = draft)
                } else p
            })
    }

    fun ttsSummary(value: TTSProviderSetting): JsonObject = buildJsonObject {
        put("id", value.id.toString()); put("name", value.name.take(160))
        val detail = when (value) {
            is TTSProviderSetting.OpenAI -> Triple("openai", value.model, value.voice)
            is TTSProviderSetting.Gemini -> Triple("gemini", value.model, value.voiceName)
            is TTSProviderSetting.MiniMax -> Triple("minimax", value.model, value.voiceId)
            is TTSProviderSetting.Qwen -> Triple("qwen", value.model, value.voice)
            is TTSProviderSetting.Groq -> Triple("groq", value.model, value.voice)
            is TTSProviderSetting.MiMo -> Triple("mimo", value.model, value.voice)
            is TTSProviderSetting.ElevenLabs -> Triple("elevenlabs", value.model, value.voiceId)
            is TTSProviderSetting.Step -> Triple("step", value.model, value.voice)
            is TTSProviderSetting.FishAudio -> Triple("fish_audio", value.model, value.referenceId)
            is TTSProviderSetting.Volcengine -> Triple("volcengine", value.resourceId, value.speaker)
            is TTSProviderSetting.XAI -> Triple("xai", "", value.voiceId)
            is TTSProviderSetting.SystemTTS -> Triple("system", "", "")
        }
        put("kind", detail.first); put("model", detail.second.take(240)); put("voice", detail.third.take(240))
    }
}
