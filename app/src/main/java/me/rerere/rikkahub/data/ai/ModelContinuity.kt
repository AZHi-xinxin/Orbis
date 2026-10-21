package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ConfiguredModelReceipt
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Advisory only: no IO, locks, timers, tool calls, queue or user-facing errors. */
internal object ModelContinuity {
    fun safeModelId(value: String): String? = value.takeIf {
        it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._:/@+-]+")) &&
            !it.contains("://") && !it.startsWith("sk-", true) && !it.contains("token", true) && !it.contains("api_key", true)
    }
    fun previous(messages: List<UIMessage>, current: ConfiguredModelReceipt): ConfiguredModelReceipt? =
        messages.asReversed().firstNotNullOfOrNull { message -> message.configuredModelReceipt?.takeIf {
            message.role == MessageRole.ASSISTANT && it.assistantId == current.assistantId && it.conversationId == current.conversationId
        } }
    fun notice(current: ConfiguredModelReceipt?, previous: ConfiguredModelReceipt?): String? {
        if(current == null || safeModelId(current.modelId) == null) return null
        val comparable = previous?.takeIf { it.assistantId == current.assistantId && it.conversationId == current.conversationId }
        val change = when {
            comparable == null -> "unknown"
            comparable.providerId == current.providerId && comparable.modelId == current.modelId -> "same"
            else -> "changed"
        }
        return "[Orbis 客户端模型信息：仅说明本次请求配置，不保证服务商实际底层模型；不是用户话语，也不要求回复或核对。]" + buildJsonObject {
            put("configured_model", current.modelId); put("interface", current.providerLabel)
            put("compared_with_last_successful_reply", change)
        }.toString()
    }
    /** Append only to the fresh request copy, never change saved user text or historical prefix. */
    fun project(messages: List<UIMessage>, notice: String?): List<UIMessage> {
        if(notice == null) return messages
        val index = messages.indexOfLast { it.role == MessageRole.USER }
        if(index < 0) return messages
        return messages.mapIndexed { i, message -> if(i != index) message else message.copy(parts = message.parts + UIMessagePart.Text(notice)) }
    }
    fun attachSuccess(messages: List<UIMessage>, receipt: ConfiguredModelReceipt?, terminal: Boolean): List<UIMessage> {
        if(receipt == null || !terminal || messages.lastOrNull()?.role != MessageRole.ASSISTANT) return messages
        return messages.dropLast(1) + messages.last().copy(configuredModelReceipt = receipt)
    }
}
