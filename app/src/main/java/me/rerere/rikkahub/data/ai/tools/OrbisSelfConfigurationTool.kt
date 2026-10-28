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
    description = """管理自己的原生配置，无需人类替你填非秘密字段。inspect列提供方/模型/TTS/MCP的ID、当前选择及记忆模式，不返回密钥。
register_model(provider_id,model,name可选,model_type=chat/image/embedding,supports_tools可选)：在已有API连接登记型号，复用原凭据。新地址用orbis_propose_connection：model_openai、tts_openai、mcp_http或mcp_sse，人类只填必要key；无key的MCP自动保存关闭，人类只需开启。
select_model(role=chat/fast/translation/ocr/compression/image/voice_archive/voice_archive_fallback,id)：选择现有型号。chat仅当前助手；其余工具型号为应用共享设置。select_tts(id)选择全局TTS。update_tts(id,model和/或voice)调整已有原生TTS，保留key/地址/语音台；voice对应音色/参考音色/speaker，豆包model为resourceId。tts_from_provider(provider_id,model,voice,name)从同地址的单key OpenAI兼容API建立TTS，不传key。
memory(mode=light/standard/independent,auto_inject=true/false,semantic_recall可选)：当前助手本地记忆；semantic_recall省略保留原值，不删除或唤醒暂停记忆。
check(kind=model/tts/mcp,id)：后台发极短真实合成测试，可能产生少量费用；没有私人聊天、播放或通话操作。check_status读本助手测试回执。queued仅排队，继续回复，无需轮询等候。失败回执可据此修正配置再试；检测不等于完整功能验收。
st_atlas_connect(provider_id)：用已配置ST的API向同服务器申请只读星图，要求该ST支持星图接入协议。已有同源待确认授权原样重试，不重新登记。st_atlas_status读结果。ST作为聊天API可用model_openai；ST MCP用连接建议。仅本地记忆无需ST。
workspace_status：只读检查本助手工作区、Rootfs实际存在和Shell状态，返回人类逐步按钮路径；安装ST前先查，不需要审批。指南按需查询orbis_help(topic=guide,chapter=st_install)及workspace_setup，不自动注入全文。
workspace_prepare：创建并绑定当前助手的默认本机文件工作区；已有绑定则复用，重建缺失目录不会恢复旧文件，不下载Linux。成功后可在本轮用workspace_write_file写/workspace/内的配置，并用orbis_backup(inspect/bind/check/save_now/status/restore_latest)备份或恢复；先inspect获得哈希，不要读取并复述凭据文件。配置修改/付费检查首次可选以后允许；不提交密钥，不修改别的助手的对话模型。""",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(
            "inspect", "register_model", "select_model", "select_tts", "update_tts", "tts_from_provider",
            "memory", "check", "check_status", "st_atlas_connect", "st_atlas_status", "workspace_prepare", "workspace_status").map(::JsonPrimitive))) })
        listOf("id", "provider_id", "model", "name", "model_type", "role", "voice", "mode", "kind").forEach {
            put(it, buildJsonObject { put("type", "string") })
        }
        listOf("supports_tools", "auto_inject", "semantic_recall").forEach { put(it, buildJsonObject { put("type", "boolean") }) }
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("inspect", "check_status", "st_atlas_status", "workspace_status") },
    hostApproval = HostToolApproval("orbis:configure:$owner", "native-configuration-v2",
        "允许此助手管理原生模型/语音/记忆配置、准备自己的本机工作区及发起短连接测试（可记住；工具模型和TTS为应用共享）"),
    isApprovalCurrent = { runCatching { AiConfigurationRules.requireOwner(snapshot(), owner) }.isSuccess },
    execute = { args ->
        val result = try { withContext(Dispatchers.IO) {
            val a = args.jsonObject
            require(a.keys.all { it in setOf("action", "id", "provider_id", "model", "name", "model_type", "role", "voice", "mode", "kind", "supports_tools", "auto_inject", "semantic_recall") }) { "unknown_field" }
            fun text(key: String, default: String? = null): String =
                a[key]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("invalid_field") }
                    ?: default ?: error("required_field")
            fun boolean(key: String, default: Boolean) = a[key]?.let {
                require(it is JsonPrimitive && !it.isString) { "invalid_field" }; it.booleanOrNull ?: error("invalid_field")
            } ?: default
            AiConfigurationRules.requireOwner(snapshot(), owner)
            val action = text("action")
            if (action == "inspect") AiConfigurationRules.inspect(snapshot(), owner)
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
                buildJsonObject {
                    put("ok", true); put("status", "saved"); put("connection_verified", false)
                    put("configuration", AiConfigurationRules.inspect(checkNotNull(saved), owner))
                }
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
                "workspace_in_use", "workspace_binding_changed", "workspace_preparation_unavailable") } ?: "invalid_configuration")
            put("help", "先inspect核对当前ID和设置；新服务用orbis_propose_connection，人类在原生页填必要key。")
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
