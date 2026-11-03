package me.rerere.rikkahub.data.sync.importer

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** Preserve each existing section verbatim; long tool loops become consecutive inert records.
 * Never split an alternative branch, truncate a section, or relax the live SQLite node budget.
 */
internal fun partitionOperitMessage(message: UIMessage, nodeId: Uuid,
    segmentId: (String, Int) -> Uuid, checkCancelled: () -> Unit): List<MessageNode> {
    try {
        MessageNodeBudget.measureNode(listOf(message))
        return listOf(MessageNode(id = nodeId, messages = listOf(message)))
    } catch (_: MessageNodeCapacityException) { }
    val envelope = MessageNodeBudget.measureNode(listOf(message.copy(parts = emptyList())))
    val groups = mutableListOf<List<UIMessagePart>>()
    var parts = mutableListOf<UIMessagePart>()
    var bytes = envelope
    for (part in message.parts) {
        checkCancelled()
        // A single oversized part still fails, with no partial commit of this conversation.
        val partBytes = MessageNodeBudget.measureNode(listOf(message.copy(parts = listOf(part)))) - envelope
        if (bytes + partBytes + (if (parts.isEmpty()) 0 else 1) > MessageNodeBudget.MAX_NODE_BYTES) {
            check(parts.isNotEmpty())
            groups += parts
            parts = mutableListOf()
            bytes = envelope
        }
        bytes += partBytes + if (parts.isEmpty()) 0 else 1
        parts += part
    }
    if (parts.isNotEmpty()) groups += parts
    return groups.mapIndexed { index, group ->
        checkCancelled()
        val segment = message.copy(id = if (index == 0) message.id else segmentId("message-segment", index), parts = group)
        MessageNodeBudget.measureNode(listOf(segment))
        MessageNode(id = if (index == 0) nodeId else segmentId("node-segment", index), messages = listOf(segment))
    }
}
