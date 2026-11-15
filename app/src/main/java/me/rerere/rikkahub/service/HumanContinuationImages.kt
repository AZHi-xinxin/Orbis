package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.files.hasInlineImageParts
import me.rerere.rikkahub.data.model.Conversation

/** Normalize only content which the validated recovery copy has not yet committed to Room.
 * Durable nodes, all message IDs, alternatives, text, tool calls and approval receipts stay intact.
 * The caller checkpoints the new references only after their files have been durably written.
 */
internal suspend fun localizeUnstoredContinuationImages(
    recovered: HumanContinuationJournal.Recovery,
    persisted: Conversation,
    localize: suspend (List<UIMessagePart>) -> List<UIMessagePart>,
): HumanContinuationJournal.Recovery {
    check(recovered.snapshot.id == persisted.id && recovered.snapshot.assistantId == persisted.assistantId &&
        recovered.snapshot.compactionEpoch == persisted.compactionEpoch)
    val durable = persisted.messageNodes.associateBy { it.id }
    val nodes = recovered.snapshot.messageNodes.map { node ->
        val committed = durable[node.id]
        if (node == committed || node.messages.none { it.parts.hasInlineImageParts() }) node
        else {
            val savedMessages = committed?.messages.orEmpty().associateBy { it.id }
            node.copy(messages = node.messages.map { message ->
                if (message.parts == savedMessages[message.id]?.parts) message
                else message.copy(parts = localize(message.parts))
            })
        }
    }
    return if (nodes == recovered.snapshot.messageNodes) recovered
        else recovered.copy(snapshot = recovered.snapshot.copy(messageNodes = nodes))
}
