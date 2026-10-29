package me.rerere.rikkahub.data.orbis.boundary

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** No inference from missing calls or natural-language promises: only this request's actual receipts. */
internal fun boundaryRecoveryResult(messages: List<UIMessage>, requestId: Uuid,
    currentPause: ConversationBoundary?): String {
    val start = messages.indexOfFirst { it.id == requestId }
    if (start < 0) return "申请记录暂不可用，请查看聊天中的错误提示。"
    val replies = messages.drop(start + 1).filter { it.role == MessageRole.ASSISTANT }
    val decision = replies.flatMap { it.getTools() }.mapNotNull { call ->
        if (call.toolName != BOUNDARY_TOOL || !call.isExecuted) return@mapNotNull null
        val args = runCatching { Json.parseToJsonElement(call.input).jsonObject }.getOrNull()
        val output = call.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
            runCatching { Json.parseToJsonElement(it.text).jsonObject }.getOrNull()
        }
        if ((output?.get("ok") as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
        val action = (args?.get("action") as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("resume", "pause") }
            ?: return@mapNotNull null
        action to ((output?.get("operation_scope") as? JsonPrimitive)?.contentOrNull
            ?: (args?.get("scope") as? JsonPrimitive)?.contentOrNull ?: "conversation")
    }.lastOrNull()
    return when {
        decision?.first == "resume" && currentPause == null -> "AI 已主动恢复对话；答复和工具结果已保存在本对话。"
        decision?.first == "pause" && currentPause != null && decision.second == currentPause.scope -> "AI 已明确继续暂停；请查看本对话中的答复和工具结果。"
        decision?.first == "resume" && decision.second == "assistant" && currentPause?.scope == "conversation" -> "AI 已恢复全助手；此窗口仍保留单独的暂停约定，请查看本对话中的答复和工具结果。"
        replies.isNotEmpty() -> "AI 已在本对话答复，尚未通过工具作出新的暂停或恢复决定。"
        else -> "本次未获得 AI 答复，请查看聊天中的错误提示。"
    }
}
