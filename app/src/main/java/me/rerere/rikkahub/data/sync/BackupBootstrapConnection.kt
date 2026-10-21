package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/**
 * AI restore only. Carry one whole local connection, never graft a key onto an archive URL.
 * This is configuration readiness, not a claim that an endpoint was contacted successfully.
 */
internal object BackupBootstrapConnection {
    private val json = Json { encodeDefaults = true }
    private val neutralId = Uuid.parse("00000000-0000-0000-0000-000000000000")

    fun preserve(restored: Settings, current: Settings?): Settings {
        if (current == null || current.init) return restored
        val selected = current.assistants.find { it.id == current.assistantId }
        val original = current.findModelById(selected?.chatModelId ?: current.chatModelId) ?: return restored
        // Match GenerationLoop's actual precedence: assistant first, model second.
        val model = original.copy(
            customHeaders = selected?.customHeaders.orEmpty() + original.customHeaders,
            customBodies = selected?.customBodies.orEmpty() + original.customBodies,
        )
        val provider = model.findProvider(current.providers) ?: return restored
        if (!ready(model, provider, allowLocalKeyless = true)) return restored
        // Flatten the complete effective override into its own provider. Parent/archived keys,
        // paths or endpoints never participate in this new connection.
        val carriedModel = model.copy(providerOverwrite = null)
        val carriedProvider = provider.copyProvider(models = emptyList())
        val existing = restored.providers.firstNotNullOfOrNull { candidate ->
            candidate.models.firstOrNull { candidateModel ->
                candidateModel.findProvider(listOf(candidate))?.let {
                    signature(it, candidateModel) == signature(carriedProvider, carriedModel)
                } == true
            }
        }
        val allModelIds = restored.providers.flatMap { it.models }.map { it.id }.toSet()
        val retainedModel = existing ?: carriedModel.copy(
            id = if (carriedModel.id in allModelIds) freshId(allModelIds) else carriedModel.id,
        )
        val providerIds = restored.providers.map { it.id }.toSet()
        val providers = if (existing != null) restored.providers else restored.providers +
            carriedProvider.copyProvider(
                id = if (carriedProvider.id in providerIds) freshId(providerIds) else carriedProvider.id,
                models = listOf(retainedModel),
            )
        fun restoredReady(id: Uuid?, assistant: me.rerere.rikkahub.data.model.Assistant? = null): Boolean {
            val restoredModel = restored.findModelById(id) ?: return false
            return restoredModel.findProvider(restored.providers)?.let {
                val effective = restoredModel.copy(
                    customHeaders = assistant?.customHeaders.orEmpty() + restoredModel.customHeaders,
                    customBodies = assistant?.customBodies.orEmpty() + restoredModel.customBodies,
                )
                ready(effective, it, allowLocalKeyless = false) ||
                    signature(it, effective) == signature(carriedProvider, carriedModel)
            } == true
        }
        return restored.copy(
            providers = providers,
            // The retained API was configured against this complete local network route.
            // Never combine its proxy credentials with an archived proxy endpoint.
            networkSetting = current.networkSetting,
            chatModelId = if (restoredReady(restored.chatModelId)) restored.chatModelId else retainedModel.id,
            assistants = restored.assistants.map { assistant ->
                val prior = assistant.chatModelId ?: restored.chatModelId
                if (!restoredReady(prior, assistant))
                    // Archived custom request fields belong to its old origin. The carried
                    // model already has the source connection's effective custom fields.
                    assistant.copy(chatModelId = retainedModel.id, customHeaders = emptyList(), customBodies = emptyList())
                else if (assistant.chatModelId == null && !restoredReady(restored.chatModelId))
                    assistant.copy(chatModelId = prior) // Its own auth header still targets the original endpoint.
                else assistant
            },
        )
    }

    private fun signature(provider: ProviderSetting, model: Model): String =
        json.encodeToString(ProviderSetting.serializer(),
            provider.copyProvider(id = neutralId, models = listOf(model.copy(id = neutralId, providerOverwrite = null))))

    private fun freshId(used: Set<Uuid>): Uuid {
        var id = Uuid.random()
        while (id in used) id = Uuid.random()
        return id
    }

    private fun ready(model: Model, provider: ProviderSetting, allowLocalKeyless: Boolean): Boolean {
        if (!provider.enabled || model.type != ModelType.CHAT || model.modelId.isBlank()) return false
        val address = when (provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl
            is ProviderSetting.Google -> provider.baseUrl
            is ProviderSetting.Claude -> provider.baseUrl
        }.toHttpUrlOrNull() ?: return false
        // A local archive URL may have lost a required key too. Only the live bootstrap
        // may carry a keyless local configuration; exact equivalents are handled above.
        val local = allowLocalKeyless && localHost(address.host)
        val headerAuth = model.customHeaders.any {
            it.name.lowercase() in setOf("authorization", "x-api-key", "api-key") && it.value.isNotBlank()
        }
        return when (provider) {
            is ProviderSetting.OpenAI -> provider.apiKey.isNotBlank() || local ||
                !provider.chatGptAccountId.isNullOrBlank() || headerAuth
            is ProviderSetting.Google -> if (provider.vertexAI && provider.useServiceAccount)
                provider.privateKey.isNotBlank() && provider.serviceAccountEmail.isNotBlank() &&
                    provider.projectId.isNotBlank() && provider.location.isNotBlank()
                else provider.apiKey.isNotBlank() || local || headerAuth
            is ProviderSetting.Claude -> provider.apiKey.isNotBlank() || local || headerAuth
        }
    }

    // No DNS/network probing. Explicit local/keyless APIs must not be mistaken for stripped keys.
    private fun localHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" ||
            host.endsWith(".local") || host.endsWith(".ts.net")) return true
        val ip = host.split('.').map { it.toIntOrNull() ?: return false }
        if (ip.size != 4 || ip.any { it !in 0..255 }) return false
        return ip[0] == 127 || ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 100 && ip[1] in 64..127) ||
            (ip[0] == 169 && ip[1] == 254)
    }
}
