package me.rerere.rikkahub.data.orbis.integration

import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** Only public setup fields live here. Credentials are native settings, never tool receipts. */
@Serializable
data class ConnectionDraft(val kind: String, val name: String, val endpoint: String,
    val model: String = "", val voice: String = "", val auth: String = "bearer") {
    fun normalized(): ConnectionDraft {
        require(kind in setOf("tts_openai", "model_openai", "mcp_http", "mcp_sse")) { "unsupported_kind" }
        require(auth in setOf("none", "bearer")) { "auth: none or bearer" }
        require(name.trim().length in 1..60 && name.none(Char::isISOControl)) { "name: 1..60 characters" }
        require(endpoint.length <= 2048) { "endpoint too long" }
        val uri = URI(endpoint.trim())
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.port in -1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            !uri.rawPath.orEmpty().contains("\\") && uri.normalize().rawPath == uri.rawPath) {
            "endpoint must be a complete HTTP(S) URL without credentials, query, fragment or dot paths"
        }
        require(listOf(model, voice).all { it.length <= 160 && it.none(Char::isISOControl) }) { "invalid model or voice" }
        if (kind == "tts_openai") require(model.isNotBlank() && voice.isNotBlank()) { "tts_openai requires model and voice" }
        if (kind == "model_openai") require(model.isNotBlank()) { "model_openai requires model" }
        val port = if ((uri.scheme.equals("https", true) && uri.port == 443) || (uri.scheme.equals("http", true) && uri.port == 80)) -1 else uri.port
        val url = URI(uri.scheme.lowercase(), null, uri.host.lowercase(), port, uri.path.trimEnd('/'), null, null).toASCIIString()
        return copy(name = name.trim(), endpoint = url, model = model.trim(), voice = voice.trim())
    }
    fun scope(owner: String) = "$owner\n$kind\n$endpoint\n$auth"
    fun id(owner: String) = UUID.nameUUIDFromBytes(("orbis-connection-v1\n" + scope(owner)).toByteArray(Charsets.UTF_8)).toString()
}

@Serializable
data class ConnectionProposal(val owner: String, val draft: ConnectionDraft, val status: String = "pending",
    val alwaysAllow: Boolean = false, val installed: ConnectionDraft? = null) {
    val id: String get() = draft.id(owner)
}

/** Pure transaction: an allowed scope can only update its own native record. Selection is separate. */
internal object ConnectionProposalRules {
    fun validate(items: List<ConnectionProposal>) {
        require(items.size <= 100 && items.map { it.id }.distinct().size == items.size)
        items.forEach {
            UUID.fromString(it.owner); require(it.draft == it.draft.normalized())
            require(it.status in setOf("pending", "saved", "rejected", "changed_in_settings"))
            it.installed?.let { previous -> require(previous.scope(it.owner) == it.draft.scope(it.owner)) }
        }
    }
    fun propose(settings: Settings, owner: String, input: ConnectionDraft): Settings {
        check(!settings.init && settings.assistants.any { it.id.toString() == owner }) { "assistant unavailable" }
        val draft = input.normalized(); val id = draft.id(owner)
        val old = settings.connectionProposals.firstOrNull { it.id == id }
        if (old?.draft == draft) return settings // retries never prompt or reset a rejection
        val proposal = ConnectionProposal(owner, draft, alwaysAllow = old?.alwaysAllow == true, installed = old?.installed)
        val next = replace(settings, proposal)
        return if (proposal.alwaysAllow && old?.status == "saved") {
            if (matchesInstalled(settings, old)) approve(next, id, null, true)
            else replace(next, proposal.copy(status = "changed_in_settings", alwaysAllow = false))
        } else next
    }
    /** Keyless connections need no credential form. MCP stays off until the human enables it. */
    fun stageKeyless(settings: Settings, owner: String, input: ConnectionDraft): Settings {
        val proposed = propose(settings, owner, input)
        val p = proposed.connectionProposals.single { it.id == input.normalized().id(owner) }
        if (p.draft.auth != "none" || p.installed != null || p.status != "pending") return proposed
        return approve(proposed, p.id, null, remember = true, enableMcp = false)
    }

    fun approve(settings: Settings, id: String, key: String?, remember: Boolean, enableMcp: Boolean = true): Settings {
        val p = settings.connectionProposals.single { it.id == id }
        check(settings.assistants.any { it.id.toString() == p.owner }) { "assistant unavailable" }
        val draft = p.draft.normalized(); val uuid = Uuid.parse(id)
        require(key == null || (key.length <= 8192 && key.none(Char::isISOControl))) { "invalid credential" }
        // This id is dedicated to the proposal. Do not overwrite independently edited native entries.
        if (p.installed != null) check(matchesInstalled(settings, p)) { "changed_in_settings" }
        val next = if (draft.kind == "tts_openai") {
            val old = settings.ttsProviders.firstOrNull { it.id == uuid }
            check(old == null || p.installed != null) { "configuration id conflict" }
            val credential = if(draft.auth == "none") "" else key ?: (old as? TTSProviderSetting.OpenAI)?.apiKey.orEmpty()
            require(draft.auth == "none" || credential.isNotBlank()) { "waiting_for_native_key" }
            val value = TTSProviderSetting.OpenAI(uuid, draft.name, credential, draft.endpoint, draft.model, draft.voice, strictEndpoint = true)
            settings.copy(ttsProviders = settings.ttsProviders.filterNot { it.id == uuid } + value)
        } else if (draft.kind == "model_openai") {
            val old = settings.providers.firstOrNull { it.id == uuid }
            check(old == null || p.installed != null) { "configuration id conflict" }
            val credential = if (draft.auth == "none") "" else key ?: (old as? ProviderSetting.OpenAI)?.apiKey.orEmpty()
            require(draft.auth == "none" || credential.isNotBlank()) { "waiting_for_native_key" }
            val modelId = connectionModelId(id)
            val value = ProviderSetting.OpenAI(id = uuid, name = draft.name, apiKey = credential,
                baseUrl = draft.endpoint, models = listOf(Model(id = modelId, modelId = draft.model, displayName = draft.model)))
            settings.copy(providers = settings.providers.filterNot { it.id == uuid } + value)
        } else {
            val old = settings.mcpServers.firstOrNull { it.id == uuid }
            check(old == null || p.installed != null) { "configuration id conflict" }
            val credential = if(draft.auth == "none") "" else key?.let { "Bearer $it" }
                ?: old?.commonOptions?.headers?.firstOrNull { it.first.equals("Authorization", true) }?.second.orEmpty()
            require(draft.auth == "none" || credential.isNotBlank()) { "waiting_for_native_key" }
            // Stable ASCII MCP namespace, independent of the human-friendly proposal title.
            val options = (old?.commonOptions ?: McpCommonOptions()).copy(enable = old?.commonOptions?.enable ?: enableMcp,
                name = "Orbis" + id.replace("-", "").take(12),
                headers = if(credential.isBlank()) emptyList() else listOf("Authorization" to credential), strictEndpoint = true)
            val value: McpServerConfig = if (draft.kind == "mcp_sse")
                McpServerConfig.SseTransportServer(uuid, options, draft.endpoint)
            else McpServerConfig.StreamableHTTPServer(uuid, options, draft.endpoint)
            settings.copy(mcpServers = settings.mcpServers.filterNot { it.id == uuid } + value,
                assistants = settings.assistants.map { if(it.id.toString() == p.owner) it.copy(mcpServers = it.mcpServers + uuid) else it })
        }
        return replace(next, p.copy(status = "saved", alwaysAllow = remember, installed = draft))
    }
    fun matchesInstalled(settings: Settings, p: ConnectionProposal): Boolean {
        val expected = p.installed ?: return false
        val id = Uuid.parse(p.id)
        return if(expected.kind == "tts_openai") {
            val native = settings.ttsProviders.filterIsInstance<TTSProviderSetting.OpenAI>().firstOrNull { it.id == id } ?: return false
            native.name == expected.name && native.baseUrl == expected.endpoint && native.model == expected.model && native.voice == expected.voice && native.strictEndpoint
        } else if (expected.kind == "model_openai") {
            val native = settings.providers.filterIsInstance<ProviderSetting.OpenAI>().firstOrNull { it.id == id } ?: return false
            native.name == expected.name && native.baseUrl == expected.endpoint && native.enabled &&
                native.models == listOf(Model(id = connectionModelId(p.id), modelId = expected.model, displayName = expected.model)) &&
                native.chatCompletionsPath == "/chat/completions" && !native.useResponseApi &&
                native.chatGptAccountId == null && !native.balanceOption.enabled
        } else {
            val native = settings.mcpServers.firstOrNull { it.id == id } ?: return false
            if ((expected.kind == "mcp_sse") != (native is McpServerConfig.SseTransportServer)) return false
            val options = native.commonOptions
            native.serverUrl == expected.endpoint && options.strictEndpoint && options.oauth == null &&
                settings.assistants.any { it.id.toString() == p.owner && id in it.mcpServers } &&
                options.name == "Orbis" + p.id.replace("-", "").take(12) &&
                (if (expected.auth == "none") options.headers.isEmpty() else
                    options.headers.size == 1 && options.headers.single().first.equals("Authorization", true) &&
                        options.headers.single().second.startsWith("Bearer "))
        }
    }
    /** Human UI action; intentionally not exposed by the configuration tools. */
    fun enableMcp(settings: Settings, owner: String, id: String): Settings {
        val p = settings.connectionProposals.single { it.owner == owner && it.id == id }
        check(p.draft.kind.startsWith("mcp_") && matchesInstalled(settings, p))
        return settings.copy(mcpServers = settings.mcpServers.map {
            if (it.id.toString() == id) it.clone(commonOptions = it.commonOptions.copy(enable = true)) else it
        })
    }
    fun connectionModelId(proposalId: String): Uuid = Uuid.parse(
        UUID.nameUUIDFromBytes("orbis-connection-model-v1\n$proposalId".toByteArray(Charsets.UTF_8)).toString())
    fun replace(settings: Settings, proposal: ConnectionProposal): Settings {
        val items = settings.connectionProposals.filterNot { it.id == proposal.id } + proposal
        validate(items)
        return settings.copy(connectionProposals = items)
    }
}
