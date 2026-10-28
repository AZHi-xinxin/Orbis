package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Only these monotonic display flags are excluded from immutable payload proofs. */
fun MessageNode.withoutRecallFlags(): MessageNode =
    if (messages.none { it.orbisRecalled || it.orbisQuoteRecalled }) this
    else copy(messages = messages.map { it.copy(orbisRecalled = false, orbisQuoteRecalled = false) })

fun MessageNode.containsRecallFlagsOf(other: MessageNode): Boolean =
    messages.size == other.messages.size && messages.zip(other.messages).all { (stored, expected) ->
        stored.id == expected.id && (!expected.orbisRecalled || stored.orbisRecalled) &&
            (!expected.orbisQuoteRecalled || stored.orbisQuoteRecalled)
    }

/** Most recent completed assistant replies only; humans and tool-only steps do not count. */
fun Conversation.recallableAiReplies(): List<UIMessage> = currentMessages.filter {
    it.role == MessageRole.ASSISTANT && it.orbisEvent == null &&
        it.parts.any { part -> part is UIMessagePart.Text && part.text.isNotBlank() } &&
        it.getTools().none { tool -> !tool.isExecuted }
}.takeLast(20).reversed()

fun Conversation.withMessageRecall(messageId: Uuid, role: MessageRole): Conversation {
    check(role == MessageRole.USER || role == MessageRole.ASSISTANT)
    val target = currentMessages.singleOrNull { it.id == messageId } ?: error("recall_message_missing")
    check(target.role == role && target.orbisEvent == null) { "recall_owner_mismatch" }
    return copy(messageNodes = messageNodes.map { node -> node.copy(messages = node.messages.map { message ->
        when {
            message.id == messageId -> message.copy(orbisRecalled = true)
            message.orbisQuote?.sourceConversationId == id && message.orbisQuote?.sourceMessageId == messageId ->
                message.copy(orbisQuoteRecalled = true)
            else -> message
        }
    }) })
}

/** A stale generation/translation/save can add content, but cannot undo a recall. */
fun preserveMessageRecalls(incoming: MessageNode, committed: MessageNode): MessageNode {
    if (incoming.id != committed.id || incoming === committed) return incoming
    val known = committed.messages.filter { it.orbisRecalled || it.orbisQuoteRecalled }.associateBy { it.id }
    if (known.isEmpty()) return incoming
    return incoming.copy(messages = incoming.messages.map { candidate ->
        val previous = known[candidate.id] ?: return@map candidate
        if (previous.orbisRecalled) previous else candidate.copy(orbisQuoteRecalled = true)
    })
}

fun withCommittedMessageRecalls(snapshot: Conversation, current: Conversation): Conversation {
    if (snapshot.id != current.id || snapshot.assistantId != current.assistantId || snapshot === current) return snapshot
    val known = current.messageNodes.associateBy { it.id }
    return snapshot.copy(messageNodes = snapshot.messageNodes.map { node ->
        known[node.id]?.let { preserveMessageRecalls(node, it) } ?: node
    })
}
