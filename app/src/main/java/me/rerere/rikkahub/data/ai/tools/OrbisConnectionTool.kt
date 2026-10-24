package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.integration.*

internal fun connectionProposalTool(store: SettingsStore, owner: String,
    onSetupNeeded: (ConnectionProposal) -> Unit = {}) = Tool(
    name = "orbis_propose_connection",
    description = "填写自己的原生连接。action=propose：kind=tts_openai/model_openai/mcp_http/mcp_sse，name、endpoint(完整HTTP(S)，无密钥/查询参数)、auth=none/bearer；model_openai需model，tts_openai需model/voice。没有密钥的配置直接保存，MCP默认关闭，只需人类在AI连接建议页开启；需要密钥时人类只在该页填key，可选择以后允许；需要补填或开启时，宿主会在发起请求的聊天打开对应配置页。已授权同地址可继续调整。不要传密钥或脚本。action=status返回当前助手配置状态、原生ID及MCP是否开启。saved不是连接成功。用orbis_configure选择模型/音色或测试连接；备份使用orbis_backup。pending无需等待，继续正常回复。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("propose", "status").map(::JsonPrimitive))) })
        listOf("kind", "name", "endpoint", "model", "voice", "auth").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
    }, required = listOf("action")) }, needsApproval = { false }, execute = { args ->
        val result = try { withTimeout(3000) { withContext(Dispatchers.IO) {
            val a = args.jsonObject
            require(a.keys.all { it in setOf("action", "kind", "name", "endpoint", "model", "voice", "auth") })
            fun string(key: String, default: String = "") = a[key]?.let { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_field")
            } ?: default
            val action = string("action")
            require(action in setOf("propose", "status"))
            check(!store.settingsFlow.value.init && store.settingsFlow.value.assistants.any { it.id.toString() == owner })
            var receipt = store.settingsFlow.value.connectionProposals
            if(action == "propose") {
                val draft = ConnectionDraft(string("kind"), string("name"), string("endpoint"), string("model"), string("voice"), string("auth", "bearer")).normalized()
                val before = receipt.firstOrNull { it.id == draft.id(owner) }
                check(store.update { ConnectionProposalRules.stageKeyless(it, owner, draft).also { updated -> receipt = updated.connectionProposals } })
                val proposal = receipt.single { it.owner == owner && it.id == draft.id(owner) }
                val needsAttention = proposal.status == "pending" || proposal.status == "changed_in_settings" ||
                    (proposal.draft.kind.startsWith("mcp_") && store.settingsFlow.value.mcpServers
                        .any { it.id.toString() == proposal.id && !it.commonOptions.enable })
                if (needsAttention && (before == null || before.draft != proposal.draft || before.status != proposal.status))
                    onSetupNeeded(proposal)
            }
            buildJsonObject {
                put("ok", true)
                put("proposals", JsonArray(receipt.filter { it.owner == owner }.map { p -> buildJsonObject {
                    put("id", p.id); put("name", p.draft.name); put("kind", p.draft.kind); put("status", p.status)
                    put("always_allowed", p.alwaysAllow); put("verified", false)
                    if (p.draft.kind == "model_openai") put("model_id", ConnectionProposalRules.connectionModelId(p.id).toString())
                    if (p.draft.kind.startsWith("mcp_")) put("enabled", store.settingsFlow.value.mcpServers
                        .firstOrNull { it.id.toString() == p.id }?.commonOptions?.enable == true)
                } }))
                put("note", "配置建议已处理；pending仅在设置页待确认，不用等候。已保存也不代表连接测试成功。可以继续当前回复。")
            }
        } } } catch(e: TimeoutCancellationException) { buildJsonObject { put("ok", false); put("status", "save_unconfirmed"); put("note", "本次保存未及时确认；不要重复创建，稍后在原生配置页查看，继续正常对话。") } }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { buildJsonObject {
            put("ok", false); put("status", "not_saved")
            put("help", "propose需kind(tts_openai/model_openai/mcp_http/mcp_sse)、name、endpoint、auth(none/bearer)；模型连接需model，TTS另需voice。不要传密钥、脚本或带查询参数的地址。原配置保留，可继续回复。")
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
