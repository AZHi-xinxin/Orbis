package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Allows this run's assistant/tool tail to grow, but rejects a new human turn or branch swap. */
internal fun isZipCreationScopeCurrent(
    assistantId: Uuid,
    conversationId: Uuid,
    lastUserId: Uuid?,
    sourceMessageIds: Set<Uuid>,
    liveConversation: Conversation?,
): Boolean {
    if (liveConversation == null || liveConversation.id != conversationId || liveConversation.assistantId != assistantId) return false
    val messages = liveConversation.messageNodes.map { it.messages.getOrNull(it.selectIndex) ?: return false }
    return messages.lastOrNull { it.role == MessageRole.USER }?.id == lastUserId &&
        messages.map { it.id }.toSet().containsAll(sourceMessageIds)
}
