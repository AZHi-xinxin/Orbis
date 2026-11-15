package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.mcp.mcpModelName
import me.rerere.rikkahub.data.ai.mcp.matchesMcpModelName
import me.rerere.rikkahub.data.ai.mcp.requireUniqueMcpModelNames

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ApprovalToolCatalog
import me.rerere.ai.ui.ApprovalToolDefinition
import me.rerere.ai.ui.UIMessage

/** A UI approval starts a new collector but continues the same model tool chain. */
internal class ApprovalCatalogScope(
    private val assistantId: String,
    private val conversationId: String?,
    private val providerId: String,
    private val modelId: String,
    private val nativeModelId: String,
) {
    fun capture(messages: List<UIMessage>, tools: List<Tool>): ApprovalToolCatalog = ApprovalToolCatalog(
        assistantId, conversationId, providerId, modelId, nativeModelId,
        messages.last().id.toString(), lastUserId(messages),
        tools.map { ApprovalToolDefinition(it.name, it.description, it.parameters(), it.hostApproval) },
    )

    fun restore(messages: List<UIMessage>, current: List<Tool>): List<Tool> {
        val last = messages.lastOrNull() ?: return current
        if (last.role != MessageRole.ASSISTANT) return current // A fresh human message gets today's catalog.
        val saved = last.orbisApprovalToolCatalog ?: return current // Compatibility with old approval cards.
        check(saved.assistantId == assistantId && saved.conversationId == conversationId &&
            saved.providerId == providerId && saved.modelId == modelId && saved.nativeModelId == nativeModelId &&
            saved.messageId == last.id.toString() && saved.userMessageId == lastUserId(messages)) {
            "这次工具审批所属的 AI、会话或模型已变化，请重新发送消息；未执行旧工具。"
        }
        requireUniqueMcpModelNames(saved.tools.map { it.name })
        val byName = current.associateBy { it.name }
        return saved.tools.map { old ->
            val name = mcpModelName(old.name)
            val live = byName[name]?.takeIf { it.matchesMcpModelName(old.name) }
            // An old advertised schema must never dispatch a different implementation/target.
            val sameDefinition = live != null && live.description == old.description &&
                live.parameters() == old.parameters && live.hostApproval?.stableId == old.hostApproval?.stableId &&
                live.hostApproval?.revision == old.hostApproval?.revision
            if (sameDefinition) checkNotNull(live).copy(parameters = { old.parameters })
            else Tool(name = name, description = old.description, parameters = { old.parameters },
                hostApproval = old.hostApproval, isSelected = { false }, isApprovalCurrent = { false },
                execute = { error("审批后此工具已移除或发生变化，未执行；请在新消息中重试。") })
        }
    }

    private fun lastUserId(messages: List<UIMessage>) =
        messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
}
