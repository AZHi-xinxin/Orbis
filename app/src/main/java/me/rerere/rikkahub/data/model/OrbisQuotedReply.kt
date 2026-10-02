package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.ai.ui.UIMessagePart

/** Only the selected ordinary message in this already-open conversation can be quoted. */
fun Conversation.quoteMessage(nodeId: kotlin.uuid.Uuid, messageId: kotlin.uuid.Uuid): OrbisMessageQuote {
    check(!isConsultation) { "咨询室消息不能在普通聊天中引用。" }
    val message = messageNodes.singleOrNull { it.id == nodeId }?.currentMessage
        ?: error("原消息已不存在，请重新选择引用。")
    check(message.id == messageId) { "原消息的选中回答已改变，请重新选择引用。" }
    check(message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
        message.orbisEvent == null && message.orbisVoiceCallKind !in setOf("begin", "opening", "archive", "summary", "ended_notice")) {
        "只能引用自己或 AI 的普通正文。"
    }
    val body = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    check(body.isNotBlank()) { "这条消息没有可引用的正文；思考和工具参数不会作为引用发送。" }
    check(body.length <= OrbisMessageQuote.MAX_BODY_CHARS) { "正文过长，无法整条引用；请选取需要的正文后复制到输入框。" }
    return OrbisMessageQuote(id, nodeId, message.id, message.role, body, message.createdAt.toString())
}

/** Validate before accepting the queued item, never reread a different window at dispatch. */
fun Conversation.requireCurrentQuote(quote: OrbisMessageQuote) {
    check(quote.isValid() && quote.sourceConversationId == id) { "引用不属于当前窗口，请重新选择。" }
    check(quoteMessage(quote.sourceNodeId, quote.sourceMessageId) == quote) {
        "引用来源已改变，未发送；请重新选择引用。"
    }
}
