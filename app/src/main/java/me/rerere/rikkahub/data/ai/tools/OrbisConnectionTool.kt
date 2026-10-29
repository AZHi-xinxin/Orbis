package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.integration.*

internal fun connectionProposalTool(store: SettingsStore, owner: String,
    onSetupNeeded: (ConnectionProposal?, Boolean) -> Unit = { _, _ -> }) =
    createConnectionProposalTool(owner, { store.settingsFlow.value }, { transform -> store.update(transform) }, verified = { p -> AiConfigurationChecks.proposalVerified(store.settingsFlow.value, p) },
        onSetupDismissed = { id -> connectionSetupQueue.closeSetup(owner, id) }, onSetupNeeded = onSetupNeeded)

internal fun createConnectionProposalTool(owner: String, snapshot: () -> Settings,
    update: suspend ((Settings) -> Settings) -> Boolean,
    verified: (ConnectionProposal) -> Boolean = { false },
    onSetupDismissed: (String?) -> Unit = {},
    onSetupNeeded: (ConnectionProposal?, Boolean) -> Unit = { _, _ -> }) = Tool(
    name = "orbis_propose_connection",
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_propose_connection"),
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("propose", "status", "open_setup", "show_guide", "rename", "complete", "dismiss_setup").map(::JsonPrimitive))) })
        listOf("id", "kind", "name", "endpoint", "model", "voice", "auth", "credential_file").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        listOf("supports_tools", "supports_images").forEach { key -> put(key, buildJsonObject { put("type", "boolean") }) }
    }, required = listOf("action")) }, needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content == "rename" }, execute = { args ->
        val result = try { withTimeout(3000) { withContext(Dispatchers.IO) {
            val a = args.jsonObject
            require(a.keys.all { it in setOf("action", "id", "kind", "name", "endpoint", "model", "voice", "auth", "supports_tools", "supports_images", "credential_file") })
            fun string(key: String, default: String = "") = a[key]?.let { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_field")
            } ?: default
            fun flag(key: String): Boolean? = a[key]?.let { value ->
                (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("invalid_field")
            }
            val action = string("action")
            require(action in setOf("propose", "status", "open_setup", "show_guide", "rename", "complete", "dismiss_setup"))
            check(!snapshot().init && snapshot().assistants.any { it.id.toString() == owner })
            var receipt = snapshot().connectionProposals
            if (action == "dismiss_setup") {
                require(a.keys.all { it in setOf("action", "id") })
                val id = if ("id" in a) string("id").also { require(it.isNotBlank()) } else null
                check(update { ConnectionProposalRules.dismissSetup(it, owner, id).also { next -> receipt = next.connectionProposals } })
                onSetupDismissed(id)
            }
            if (action == "rename") {
                require(a.keys == setOf("action", "id", "name"))
                check(update { ConnectionProposalRules.renameMcp(it, owner, string("id"), string("name")).also { next -> receipt = next.connectionProposals } })
            }
            if (action == "complete") {
                require(a.keys == setOf("action", "id"))
                check(update { ConnectionProposalRules.completeSetup(it, owner, string("id"), verified).also { next -> receipt = next.connectionProposals } })
            }
            if(action == "propose") {
                val draft = ConnectionDraft(string("kind"), string("name"), string("endpoint"), string("model"), string("voice"), string("auth", "bearer"), flag("supports_tools"), flag("supports_images"), string("credential_file")).normalized()
                val before = receipt.firstOrNull { it.id == draft.id(owner) }
                check(update { ConnectionProposalRules.stageKeyless(it, owner, draft).also { updated -> receipt = updated.connectionProposals } })
                val proposal = receipt.single { it.owner == owner && it.id == draft.id(owner) }
                val needsAttention = proposal.status == "pending" || proposal.status == "changed_in_settings" ||
                    (proposal.draft.kind.startsWith("mcp_") && snapshot().mcpServers
                        .any { it.id.toString() == proposal.id && !it.commonOptions.enable })
                if (needsAttention && (before == null || before.draft != proposal.draft || before.status != proposal.status))
                    onSetupNeeded(proposal, action == "show_guide" || (action == "propose" && proposal.draft.credentialFile.isNotBlank()))
            }
            if (action in setOf("open_setup", "show_guide")) {
                require(a.keys.all { it in setOf("action", "id") })
                val scoped = receipt.filter { it.owner == owner && it.status != "rejected" }
                val proposal = if (string("id").isNotBlank()) scoped.single { it.id == string("id") }
                    else scoped.firstOrNull { it.status == "pending" } ?: scoped.lastOrNull()
                check(proposal != null || action == "show_guide") { "proposal_missing" }
                if (proposal != null && (proposal.setupCompleted || proposal.setupDismissed)) check(update { current ->
                    val now = current.connectionProposals.single { it.id == proposal.id && it.owner == owner }
                    ConnectionProposalRules.replace(current, now.copy(setupCompleted = false, setupDismissed = false)).also { next -> receipt = next.connectionProposals }
                })
                onSetupNeeded(proposal?.copy(setupCompleted = false, setupDismissed = false), action == "show_guide")
            }
            buildJsonObject {
                put("ok", true)
                put("ui_requested", action in setOf("open_setup", "show_guide"))
                put("ui_dismiss_requested", action == "dismiss_setup")
                put("human_entry", "有待办时：当前聊天输入框上方 → 连接设置；关闭提示后：右下角北斗导航 → 工具娱乐 → AI 连接建议，或用 open_setup 重开")
                put("proposals", JsonArray(receipt.filter { it.owner == owner }.map { p -> buildJsonObject {
                    put("id", p.id); put("name", p.draft.name); put("kind", p.draft.kind); put("status", p.status)
                    put("always_allowed", p.alwaysAllow); put("verified", verified(p)); put("setup_completed", p.setupCompleted)
                    put("setup_dismissed", p.setupDismissed); put("setup_prompt_pending", p.setupPromptPending)
                    if (p.draft.kind == "model_openai") put("model_id", ConnectionProposalRules.connectionModelId(p.id).toString())
                    if (p.draft.kind.startsWith("mcp_")) put("enabled", snapshot().mcpServers
                        .firstOrNull { it.id.toString() == p.id }?.commonOptions?.enable == true)
                } }))
                put("completion_help", "确认连通：check/check_status 后 verified=true 才能 complete(id)。只关闭提示（包括已删测试服务、离线或未验证）：dismiss_setup(id)；省略 id 关闭本助手全部连接提示，不影响其他助手。它不删除/停用连接、不切模型、不保存输入密钥，也不表示验证成功。后台设置保留，open_setup 可再打开。")
                put("note", "配置建议已处理；pending仅在设置页待确认，不用等候。已保存也不代表连接测试成功。可以继续当前回复。")
            }
        } } } catch(e: TimeoutCancellationException) { buildJsonObject { put("ok", false); put("status", "save_unconfirmed"); put("note", "本次保存未及时确认；不要重复创建，稍后在原生配置页查看，继续正常对话。") } }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { buildJsonObject {
            put("ok", false); put("status", "not_saved")
            put("help", "只关弹窗请用dismiss_setup，可省略id关闭本助手全部提示，不需要验证；id若提供必须属于本助手。complete需本助手已保存、启用且通过当前配置检查的id；rename需本助手创建的MCP的id/name。propose需kind(tts_openai/model_openai/mcp_http/mcp_sse)、name、endpoint、auth(none/bearer)；模型连接需model，TTS另需voice。不要传密钥、脚本或带查询参数的地址。原配置保留，可继续回复。")
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
