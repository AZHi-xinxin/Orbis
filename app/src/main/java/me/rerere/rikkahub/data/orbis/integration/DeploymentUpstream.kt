package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings

internal const val ST_UPSTREAM_FILE = "/workspace/.orbis-st-upstream.json"

/** Created only by the human's native button, never a tool argument/result. */
internal class DeploymentUpstream(val workspace: String, val model: String, val baseUrl: String, private val key: String) {
    override fun toString() = "DeploymentUpstream([redacted])"
    fun privateJson(): String = buildJsonObject {
        put("base_url", baseUrl); put("model", model); put("api_key", key)
    }.toString()
}

internal fun deploymentUpstream(settings: Settings, owner: String): DeploymentUpstream {
    val assistant = AiConfigurationRules.requireOwner(settings, owner)
    val workspace = checkNotNull(assistant.workspaceId) { "workspace_missing" }.toString()
    val id = assistant.chatModelId ?: settings.chatModelId
    val provider = settings.providers.single { p -> p.models.any { it.id == id } }
    val model = provider.models.single { it.id == id }
    val effective = (model.providerOverwrite ?: provider) as? ProviderSetting.OpenAI ?: error("openai_required")
    check(provider.enabled && effective.enabled && effective.chatGptAccountId == null)
    val draft = ConnectionDraft("model_openai", "ST upstream", effective.baseUrl, model.modelId).normalized()
    val key = effective.apiKey.trim()
    require(key.isNotBlank() && key.length <= 4096 && key.none { it.isWhitespace() || it == ',' }) { "single_key_required" }
    return DeploymentUpstream(workspace, draft.model, draft.endpoint, key)
}
