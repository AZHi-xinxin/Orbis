package me.rerere.rikkahub.data.orbis.integration

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.safeRejectedParameter
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Independent bounded probes: never owns ChatService, microphone, player or a conversation. */
internal object AiConfigurationChecks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val ownerGates = ConcurrentHashMap<String, Mutex>()
    private data class VerifiedTarget(val identity: Any, val at: Long)
    private val verifiedTargets = ConcurrentHashMap<String, VerifiedTarget>()
    fun proposalVerified(settings: Settings, proposal: ConnectionProposal): Boolean {
        val kind = when(proposal.draft.kind) { "model_openai" -> "model"; "tts_openai" -> "tts"; else -> "mcp" }
        val id = if (kind == "model") ConnectionProposalRules.connectionModelId(proposal.id).toString() else proposal.id
        val previous = verifiedTargets["${proposal.owner}/$kind/$id"] ?: return false
        return System.currentTimeMillis() - previous.at in 0..86_400_000 &&
            runCatching { validateTarget(settings, proposal.owner, kind, id) == previous.identity }.getOrDefault(false)
    }
    private val receipts = ConcurrentHashMap<String, JsonObject>()
    private val client by lazy { OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(35, TimeUnit.SECONDS).build() }

    suspend fun request(context: Context, store: SettingsStore, mcp: McpManager?, owner: String,
        action: String, kind: String, id: String): JsonObject {
        val settings = store.settingsFlow.value
        AiConfigurationRules.requireOwner(settings, owner)
        if (action == "st_atlas_status") {
            val integrations = GlobalContext.get().get<OrbisIntegrationConnections>()
            val state = integrations.atlasBootstrap.state.value
            return buildJsonObject {
                put("ok", true); put("status", state.phase.name.lowercase()); put("loaded", state.loaded)
                put("busy", state.busy); put("provider_id", state.providerId?.let(::JsonPrimitive) ?: JsonNull)
                put("enabled", integrations[OrbisIntegration.ST_ATLAS].state.value.available)
                put("has_error", state.error != null)
                state.error?.let { put("detail", it) } // Closed local bootstrap messages, never an upstream response.
            }
        }
        if (action == "check_status") return receipts[owner] ?: buildJsonObject {
            put("ok", true); put("status", "not_checked_in_this_process")
        }
        require(action in setOf("check", "st_atlas_connect"))
        val probeKind = if (action == "st_atlas_connect") "st_atlas" else kind
        val identity = validateTarget(settings, owner, probeKind, id)
        val ownerGate = ownerGates.getOrPut(owner) { Mutex() }
        if (!ownerGate.tryLock()) return buildJsonObject { put("ok", true); put("status", "already_running") }
        if (!gate.tryAcquire()) {
            ownerGate.unlock()
            return buildJsonObject { put("ok", false); put("status", "checks_busy"); put("retryable", true) }
        }
        fun receipt(status: String, ok: Boolean, fields: JsonObject = buildJsonObject {}): JsonObject = buildJsonObject {
            put("ok", ok); put("status", status); put("kind", probeKind); put("id", id)
            put("at", System.currentTimeMillis()); fields.forEach { (k, v) -> put(k, v) }
        }
        val queued = receipt("queued", true)
        // A newer check supersedes an older success, including while running or if it fails.
        verifiedTargets.remove("$owner/$probeKind/$id")
        receipts[owner] = queued
        scope.launch {
            try {
                receipts[owner] = receipt("running", true)
                val result = withTimeout(40_000) {
                    check(validateTarget(store.settingsFlow.value, owner, probeKind, id) == identity) { "configuration_changed" }
                    when (probeKind) {
                        "model" -> {
                            val (model, provider) = modelTarget(settings, id)
                            val response = ProviderManager(client, context).getProviderByType(provider, me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context).requestContext(owner).check).generateText(provider,
                                listOf(UIMessage.user("Reply with OK.")),
                                TextGenerationParams(model = model.copy(tools = emptySet(), customHeaders = emptyList(), customBodies = emptyList()),
                                    maxTokens = 16, reasoningLevel = ReasoningLevel.OFF, tools = emptyList(), maxAutomaticContinuations = 0))
                            check(response.message.toText().isNotBlank() && response.message.getTools().isEmpty()) { "empty_or_tool_response" }
                            buildJsonObject { put("probe", "short_text_response"); put("playback_tested", false) }
                        }
                        "tts" -> {
                            val provider = settings.ttsProviders.single { it.id.toString() == id }
                            var bytes = 0L
                            TTSManager(context).generateSpeech(provider, TTSRequest("Orbis connection test.")).collect { chunk ->
                                bytes += chunk.data.size
                                check(bytes <= 8L * 1024 * 1024) { "audio_too_large" }
                            }
                            check(bytes > 0) { "empty_audio" }
                            buildJsonObject { put("probe", "audio_response"); put("audio_bytes", bytes); put("playback_tested", false) }
                        }
                        "mcp" -> {
                            val server = settings.mcpServers.single { it.id.toString() == id }
                            val manager = checkNotNull(mcp)
                            manager.addClient(server)
                            val status = manager.syncingStatus.value[server.id]
                            check(status == McpStatus.Connected) {
                                if (status == McpStatus.NeedsAuthorization) "mcp_authorization_required" else "mcp_not_connected"
                            }
                            buildJsonObject { put("probe", "mcp_handshake"); put("tool_count",
                                store.settingsFlow.value.mcpServers.firstOrNull { it.id == server.id }?.commonOptions?.tools?.size ?: 0) }
                        }
                        else -> {
                            val connections = GlobalContext.get().get<OrbisIntegrationConnections>()
                            val bootstrap = connections.atlasBootstrap
                            if (!bootstrap.state.value.loaded) bootstrap.reload()
                            val state = bootstrap.state.value
                            val connected = when {
                                state.phase == OrbisAtlasBootstrapPhase.NONE ->
                                    bootstrap.connect(id) { store.settingsFlow.value.providers }
                                state.phase == OrbisAtlasBootstrapPhase.PENDING && state.providerId == id ->
                                    bootstrap.retry { store.settingsFlow.value.providers }
                                state.phase == OrbisAtlasBootstrapPhase.ACTIVE && state.providerId == id -> bootstrap.setEnabled(true)
                                else -> error("atlas_existing_source")
                            }
                            check(connected && bootstrap.state.value.phase == OrbisAtlasBootstrapPhase.ACTIVE) { "atlas_not_connected" }
                            buildJsonObject { put("probe", "st_atlas_registration"); put("scope", "atlas.metadata.read") }
                        }
                    }
                }
                check(validateTarget(store.settingsFlow.value, owner, probeKind, id) == identity) { "configuration_changed" }
                if (verifiedTargets.size >= 100) verifiedTargets.clear()
                verifiedTargets["$owner/$probeKind/$id"] = VerifiedTarget(identity, System.currentTimeMillis())
                receipts[owner] = receipt("passed", true, result)
            } catch (e: TimeoutCancellationException) {
                receipts[owner] = receipt("timeout", false)
            } catch (e: CancellationException) {
                receipts[owner] = receipt("cancelled", false); throw e
            } catch (e: Exception) {
                receipts[owner] = receipt("failed", false, failure(e))
            } finally {
                ownerGate.unlock(); gate.release()
                val activeOwners = store.settingsFlow.value.assistants.map { it.id.toString() }.toSet()
                receipts.keys.removeAll { it !in activeOwners }
                ownerGates.entries.removeAll { it.key !in activeOwners && !it.value.isLocked }
            }
        }
        return queued
    }

    private fun modelTarget(settings: Settings, id: String): Pair<Model, ProviderSetting> {
        val model = settings.providers.flatMap { it.models }.singleOrNull { it.id.toString() == id } ?: error("model_missing")
        require(model.type == ModelType.CHAT) { "chat_test_only" }
        val provider = model.findProvider(settings.providers) ?: error("provider_missing")
        check(provider.enabled) { "provider_disabled" }
        return model to provider
    }

    /** Equality holds credentials in process only; no fingerprint/raw object ever becomes a receipt. */
    private fun validateTarget(settings: Settings, owner: String, kind: String, id: String): Any {
        val assistant = AiConfigurationRules.requireOwner(settings, owner)
        Uuid.parse(id)
        return when (kind) {
            "model" -> modelTarget(settings, id)
            "tts" -> settings.ttsProviders.singleOrNull { it.id.toString() == id } ?: error("tts_missing")
            "mcp" -> {
                val server = settings.mcpServers.singleOrNull { it.id.toString() == id && it.id in assistant.mcpServers }
                    ?: error("mcp_not_owned")
                check(server.commonOptions.enable) { "mcp_disabled" }
                // Tool discovery updates tool schemas, not connection identity.
                server.clone(commonOptions = server.commonOptions.copy(tools = emptyList(), displayName = ""))
            }
            "st_atlas" -> {
                val provider = settings.providers.singleOrNull { it.id.toString() == id } ?: error("provider_missing")
                atlasBootstrapSource(provider).fingerprint
            }
            else -> error("invalid_check_kind")
        }
    }

    internal fun failure(error: Exception): JsonObject = buildJsonObject {
        if (error is HttpException) {
            put("reason", "http_error")
            error.httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
            safeRejectedParameter(error.rejectedParameter)?.let { put("rejected_parameter", it) }
        } else put("reason", when (error) {
            is java.net.UnknownHostException -> "dns_failed"
            is javax.net.ssl.SSLException -> "tls_failed"
            is java.net.SocketTimeoutException -> "network_timeout"
            is java.net.ConnectException -> "connection_failed"
            else -> error.message?.takeIf { it in setOf("configuration_changed", "empty_or_tool_response", "audio_too_large",
                "empty_audio", "mcp_authorization_required", "mcp_not_connected", "atlas_existing_source", "atlas_not_connected",
                "model_missing", "provider_missing", "provider_disabled", "chat_test_only", "tts_missing", "mcp_not_owned", "mcp_disabled") }
                ?: "probe_failed"
        })
    }
}
