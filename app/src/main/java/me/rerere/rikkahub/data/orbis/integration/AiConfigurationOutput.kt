package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings

/** Read-only, whitelisted views. No full provider serialization or execution callbacks. */
internal fun configurationInspection(settings: Settings, owner: String, args: JsonObject): JsonObject {
    fun text(key: String, fallback: String = "") = args[key]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("invalid_field")
    } ?: fallback
    fun number(key: String, fallback: Int) = args[key]?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("invalid_page")
    } ?: fallback
    val assistant = AiConfigurationRules.requireOwner(settings, owner)
    val section = text("section", "current")
    require(section in setOf("current", "providers", "models", "tts", "mcp")) { "invalid_section" }
    val query = text("query"); val providerId = text("provider_id")
    require(query.length <= 120 && query.none(Char::isISOControl)) { "invalid_query" }
    val offset = number("offset", 0); val limit = number("limit", 10)
    require(offset >= 0 && limit in 1..20) { "invalid_page" }
    require(("provider_id" !in args || section == "models") &&
        (section != "current" || args.keys.none { it in setOf("query", "offset", "limit", "provider_id") })) { "invalid_catalog_filter" }
    if (section == "current") return buildJsonObject {
        AiConfigurationRules.inspect(settings, owner, includeCatalog = false).forEach { (k, v) -> put(k, v) }
        put("catalog_counts", buildJsonObject {
            put("providers", settings.providers.size); put("models", settings.providers.sumOf { it.models.size })
            put("tts", settings.ttsProviders.size); put("mcp", settings.mcpServers.count { it.id in assistant.mcpServers })
        })
        put("catalog_query", "inspect(section=providers/models/tts/mcp,query?,offset?,limit?); models可加provider_id")
    }
    val rows: List<JsonObject> = when (section) {
        "providers" -> settings.providers.map { p -> buildJsonObject {
            put("id", p.id.toString()); put("name", p.name.take(160)); put("enabled", p.enabled)
            put("kind", when(p) { is ProviderSetting.OpenAI -> "openai"; is ProviderSetting.Google -> "google"; is ProviderSetting.Claude -> "claude" })
            put("model_count", p.models.size)
        } }
        "models" -> settings.providers.filter { providerId.isEmpty() || it.id.toString() == providerId }.flatMap { p ->
            p.models.map { m -> buildJsonObject {
                put("id", m.id.toString()); put("model", m.modelId.take(240)); put("name", m.displayName.take(160))
                put("type", m.type.name.lowercase()); put("provider_id", p.id.toString()); put("provider_name", p.name.take(160))
                put("enabled", p.enabled && (m.providerOverwrite?.enabled != false))
                put("supports_tools", ModelAbility.TOOL in m.abilities)
                put("supports_images", me.rerere.ai.provider.Modality.IMAGE in m.inputModalities)
            } }
        }
        "tts" -> settings.ttsProviders.map(AiConfigurationRules::ttsSummary)
        else -> settings.mcpServers.filter { it.id in assistant.mcpServers }.map { s -> buildJsonObject {
            put("id", s.id.toString()); put("name", s.commonOptions.displayTitle.take(160))
            put("enabled", s.commonOptions.enable); put("tool_count", s.commonOptions.tools.count { it.enable })
        } }
    }
    val filtered = rows.filter { row -> query.isBlank() || listOf("id", "name", "model", "provider_id", "provider_name")
        .any { row[it]?.jsonPrimitive?.content?.contains(query, ignoreCase = true) == true } }
    val page = filtered.drop(offset).take(limit)
    val next = if (page.size < filtered.size - offset) offset + page.size else null
    return buildJsonObject {
        put("ok", true); put("section", section); put("items", JsonArray(page))
        put("page", buildJsonObject {
            put("offset", offset); put("limit", limit); put("total", filtered.size)
            put("returned", page.size); put("next_offset", next?.let(::JsonPrimitive) ?: JsonNull)
        })
        if (next != null) put("next_call", buildJsonObject {
            put("action", "inspect"); put("section", section); put("offset", next); put("limit", limit)
            if (query.isNotEmpty()) put("query", query)
            if (providerId.isNotEmpty()) put("provider_id", providerId)
        })
    }
}

/** Successful writes return only their own target/result, never the unrelated full catalogue. */
internal fun configurationMutationReceipt(settings: Settings, owner: String, action: String, args: JsonObject): JsonObject {
    fun text(key: String, fallback: String = "") = args[key]?.jsonPrimitive?.content ?: fallback
    val assistant = AiConfigurationRules.requireOwner(settings, owner)
    return buildJsonObject {
        put("ok", true); put("status", "saved"); put("action", action); put("connection_verified", false)
        when (action) {
            "select_model" -> {
                val role = text("role")
                put("scope", if (role == "chat") "assistant" else "application")
                put("role", role); put("id", AiConfigurationRules.selectedModels(settings, owner).getValue(role).toString())
            }
            "register_model" -> {
                val provider = settings.providers.single { it.id.toString() == text("provider_id") }
                val model = provider.models.first { it.modelId == text("model") && it.type.name.lowercase() == text("model_type", "chat") }
                put("scope", "application"); put("provider_id", provider.id.toString()); put("id", model.id.toString())
                put("model", model.modelId); put("model_type", model.type.name.lowercase()); put("selection_changed", false)
            }
            "select_tts", "update_tts", "tts_from_provider" -> {
                val id = if (action == "tts_from_provider") {
                    val p = settings.providers.single { it.id.toString() == text("provider_id") } as ProviderSetting.OpenAI
                    ConnectionDraft("tts_openai", text("name", "AI 配置的语音"), p.baseUrl, text("model"), text("voice"),
                        if (p.apiKey.isBlank()) "none" else "bearer").normalized().id(owner)
                } else text("id")
                put("scope", "application"); put("tts", AiConfigurationRules.ttsSummary(settings.ttsProviders.single { it.id.toString() == id }))
                put("selected_tts_id", settings.selectedTTSProviderId.toString())
            }
            "memory" -> {
                put("scope", "assistant"); put("mode", assistant.orbisMemoryMode.name.lowercase())
                put("auto_inject", assistant.orbisMemoryAutoInject); put("semantic_recall", assistant.orbisSemanticRecall)
            }
        }
    }
}
