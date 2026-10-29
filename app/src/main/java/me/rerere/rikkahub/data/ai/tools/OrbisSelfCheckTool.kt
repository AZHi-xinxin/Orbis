package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.diagnostics.*

internal fun createOrbisSelfCheckTool(enabled: () -> Boolean, check: suspend (String) -> SelfCheckReport) = Tool(
    name = "orbis_self_check",
    description = "按需自检当前助手工具/授权/模型/主题/MCP/工作区/表情包/系统权限，返回实测配置、最近5条故障代码和具体修复路径。focus=overview/tools/model/theme/mcp/workspace/stickers/permissions。只读本机，不读聊天或密钥、不联网。已授权修复按next提示使用现有工具，未授权由人类打开，不能把未查明当正常。模型无法调用时，人类到当前助手设置→本地工具→自检。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("focus", buildJsonObject { put("type", "string"); put("enum", JsonArray(selfCheckFocus.map(::JsonPrimitive))) })
    }) },
    isApprovalCurrent = enabled,
    execute = { args ->
        val result = try {
            check(enabled()) { "self_check_disabled" }
            val obj = args as? JsonObject ?: error("self_check_invalid_parameters")
            require(obj.keys.all { it == "focus" }) { "self_check_invalid_parameters" }
            val focus = if ("focus" !in obj) "overview" else (obj["focus"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(focus in selfCheckFocus) { "self_check_invalid_parameters" }
            val report = check(checkNotNull(focus))
            check(enabled()) { "self_check_disabled" }
            buildJsonObject { put("ok", true); put("report", Json.encodeToJsonElement(report))
                put("recent_guidance", buildJsonObject { report.recent.forEach { put(it.code, diagnosticCodes.getValue(it.code)) } }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { buildJsonObject { put("ok", false); put("code", e.message?.takeIf {
            it in setOf("self_check_disabled", "self_check_invalid_parameters", "assistant_unavailable") } ?: "self_check_unavailable")
            put("next", "人类可在当前助手设置→本地工具→自检打开本机检查。") } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
