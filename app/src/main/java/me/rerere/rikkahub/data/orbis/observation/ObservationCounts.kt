package me.rerere.rikkahub.data.orbis.observation

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisMessageBatchOperation
import me.rerere.rikkahub.data.model.OrbisMessageBatchPreview
import me.rerere.rikkahub.data.orbis.memory.latestMemoryHuman
import kotlin.uuid.Uuid

/** Every human/assistant alternative counts; protocol SYSTEM/TOOL rows do not. */
internal fun observedMessageCount(messages: Iterable<UIMessage>): Long =
    messages.sumOf { if (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) 1L else 0L }

/** Call only after the exact preview has committed. Archiving is not deletion. */
internal fun observedBatchDeletedCount(before: Conversation, preview: OrbisMessageBatchPreview): Long {
    if (before.isConsultation || preview.operation != OrbisMessageBatchOperation.DELETE) return 0L
    return before.messageNodes.filter { it.id in preview.affectedNodeIds }.sumOf { observedMessageCount(it.messages) }
}

/** The writer uses precisely the same human-anchor policy as the injected report. */
internal fun observedRegenerationHuman(conversation: Conversation, messageId: Uuid): UIMessage? {
    if (conversation.isConsultation) return null
    val at = conversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (at < 0) return null
    return latestMemoryHuman(conversation.messageNodes.take(at + 1).map { it.currentMessage })
}
