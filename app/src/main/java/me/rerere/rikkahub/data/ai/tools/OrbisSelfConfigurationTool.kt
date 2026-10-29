package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.integration.AiConfigurationChecks
import me.rerere.rikkahub.data.orbis.integration.AiConfigurationRules
import me.rerere.rikkahub.data.orbis.integration.AiWorkspacePreparation
import me.rerere.rikkahub.data.orbis.integration.configurationInspection
import me.rerere.rikkahub.data.orbis.integration.configurationMutationReceipt
import me.rerere.rikkahub.data.repository.WorkspaceRepository

internal fun orbisSelfConfigurationTool(store: SettingsStore, owner: String, context: Context, mcp: McpManager,
    workspaceRepository: WorkspaceRepository, stRuntimeWorkspaceForRun: () -> String? = { null }) =
    createOrbisSelfConfigurationTool(owner, { store.settingsFlow.value }, { transform -> store.update(transform) },
        network = { action, kind, id ->
            AiConfigurationChecks.request(context.applicationContext, store, mcp, owner, action, kind, id)
        }, workspaceStatus = {
            val assistant = AiConfigurationRules.requireOwner(store.settingsFlow.value, owner)
            val id = assistant.workspaceId?.toString()
            val readiness = workspaceRepository.readiness(id)
            val workspace = id?.let { workspaceRepository.getById(it) }
            val host = me.rerere.rikkahub.data.orbis.st.StRuntimeHost.get(context)
            JsonObject(readiness + ("st_host" to me.rerere.rikkahub.data.orbis.st.stRuntimeReadiness(
                bound = workspace != null,
                enabled = workspace?.let { host.enabled(it.root) } == true,
                shellReady = readiness["shell_ready"]?.jsonPrimitive?.booleanOrNull == true,
                registeredForRun = workspace != null && id == stRuntimeWorkspaceForRun(),
                configured = workspace?.let { host.saved(it.root).spec != null } == true,
                phase = workspace?.let { host.current(it.root).phase } ?: "stopped",
            )))
        }, prepareWorkspace = {
            AiWorkspacePreparation.prepare(owner, { store.settingsFlow.value }, workspaceRepository::getById,
                workspaceRepository::createNative, workspaceRepository::prepareNative) { transform -> store.update(transform) }
        })

internal fun createOrbisSelfConfigurationTool(
    owner: String,
    snapshot: () -> Settings,
    update: suspend ((Settings) -> Settings) -> Boolean,
    network: suspend (action: String, kind: String, id: String) -> JsonObject,
    prepareWorkspace: suspend () -> JsonObject = { error("workspace_preparation_unavailable") },
    workspaceStatus: suspend () -> JsonObject = { error("workspace_preparation_unavailable") },
) = Tool(
    name = "orbis_configure",
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_configure"),
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(
            "inspect", "register_model", "select_model", "select_tts", "update_tts", "tts_from_provider",
            "memory", "check", "check_status", "st_atlas_connect", "st_atlas_status", "workspace_prepare", "workspace_status").map(::JsonPrimitive))) })
        listOf("id", "provider_id", "model", "name", "model_type", "role", "voice", "mode", "kind").forEach {
            put(it, buildJsonObject { put("type", "string") })
        }
        listOf("supports_tools", "auto_inject", "semantic_recall").forEach { put(it, buildJsonObject { put("type", "boolean") }) }
        put("section", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("current", "providers", "models", "tts", "mcp").map(::JsonPrimitive))); put("description", "inspect only; default current. Catalogues are paginated.") })
        put("query", buildJsonObject { put("type", "string"); put("maxLength", 120); put("description", "inspect catalogue name or ID filter; models can also use provider_id.") })
        put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 20); put("description", "inspect page size; default 10.") })
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("inspect", "check_status", "st_atlas_status", "workspace_status") },
    hostApproval = HostToolApproval("orbis:configure:$owner", "native-configuration-v2",
        "允许此助手管理原生模型/语音/记忆配置、准备自己的本机工作区及发起短连接测试（可记住；工具模型和TTS为应用共享）"),
    isApprovalCurrent = { runCatching { AiConfigurationRules.requireOwner(snapshot(), owner) }.isSuccess },
    execute = { args ->
        val result = try { withContext(Dispatchers.IO) {
            val a = args.jsonObject
            require(a.keys.all { it in setOf("action", "id", "provider_id", "model", "name", "model_type", "role", "voice", "mode", "kind", "supports_tools", "auto_inject", "semantic_recall", "section", "query", "offset", "limit") }) { "unknown_field" }
            fun text(key: String, default: String? = null): String =
                a[key]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("invalid_field") }
                    ?: default ?: error("required_field")
            fun boolean(key: String, default: Boolean) = a[key]?.let {
                require(it is JsonPrimitive && !it.isString) { "invalid_field" }; it.booleanOrNull ?: error("invalid_field")
            } ?: default
            AiConfigurationRules.requireOwner(snapshot(), owner)
            val action = text("action")
            require(action == "inspect" || a.keys.none { it in setOf("section", "query", "offset", "limit") }) { "unknown_field" }
            if (action == "inspect") configurationInspection(snapshot(), owner, a)
            else if (action == "workspace_status") {
                require(a.keys == setOf("action")) { "unknown_field" }
                workspaceStatus()
            }
            else if (action == "workspace_prepare") {
                require(a.keys == setOf("action")) { "unknown_field" }
                prepareWorkspace()
            }
            else if (action in setOf("check", "check_status", "st_atlas_connect", "st_atlas_status"))
                network(action, text("kind", ""), text(if (action == "st_atlas_connect") "provider_id" else "id", ""))
            else {
                var saved: Settings? = null
                check(update { latest ->
                    val next = when (action) {
                        "register_model" -> AiConfigurationRules.registerModel(latest, owner, text("provider_id"), text("model"),
                            text("name", ""), text("model_type", "chat"), boolean("supports_tools", true))
                        "select_model" -> AiConfigurationRules.selectModel(latest, owner, text("role"), text("id"))
                        "select_tts" -> AiConfigurationRules.selectTts(latest, owner, text("id"))
                        "update_tts" -> AiConfigurationRules.updateTts(latest, owner, text("id"),
                            if ("model" in a) text("model") else null, if ("voice" in a) text("voice") else null)
                        "tts_from_provider" -> AiConfigurationRules.ttsFromProvider(latest, owner, text("provider_id"),
                            text("model"), text("voice"), text("name", "AI 配置的语音"))
                        "memory" -> AiConfigurationRules.setMemory(latest, owner, text("mode"),
                            boolean("auto_inject", AiConfigurationRules.requireOwner(latest, owner).orbisMemoryAutoInject),
                            if ("semantic_recall" in a) boolean("semantic_recall", false) else null)
                        else -> error("unknown_action")
                    }
                    saved = next
                    next
                }) { "save_failed" }
                configurationMutationReceipt(checkNotNull(saved), owner, action, a)
            }
        } } catch (e: CancellationException) { throw e
        } catch (e: Exception) { buildJsonObject {
            put("ok", false); put("status", "not_saved")
            // Only our closed error vocabulary, never provider responses, URLs, keys or stack traces.
            val code = e.message.orEmpty()
            put("reason", code.takeIf { it in setOf("assistant_unavailable", "unknown_role", "model_missing", "provider_disabled",
                "model_type_mismatch", "invalid_model", "invalid_name", "provider_missing", "model_limit", "tts_missing",
                "openai_provider_required", "provider_not_reusable", "single_key_required", "invalid_memory_mode", "missing_change",
                "invalid_tts_value", "tts_has_no_model_field", "system_tts_uses_system_voice", "unknown_field", "invalid_field",
                "required_field", "unknown_action", "save_failed", "changed_in_settings", "workspace_missing",
                "workspace_in_use", "workspace_binding_changed", "workspace_preparation_unavailable",
                "invalid_section", "invalid_page", "invalid_query", "invalid_catalog_filter") } ?: "invalid_configuration")
            put("help", when (code) {
                "invalid_section" -> "inspect的section为current/providers/models/tts/mcp。"
                "invalid_page" -> "offset为非负整数，limit为1至20的整数。"
                "invalid_query" -> "query最多120字符，不含控制字符。"
                "invalid_catalog_filter" -> "provider_id筛选仅用于inspect section=models；current不接收目录筛选或分页。"
                else -> "inspect核对当前设置，section分目录查ID；完整参数查orbis_help(topic=tool,tool_name=orbis_configure)。"
            })
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
