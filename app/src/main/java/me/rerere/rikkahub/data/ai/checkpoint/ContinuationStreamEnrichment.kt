package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/** An exact journal-issued Room hash is a prerequisite at the caller. This additionally
 * limits recovery to unfinished, selected assistant prose moving forward. It is NOT a
 * general prefix merge: other branches, human edits, tool receipts and ownership stay exact.
 */
internal fun isProvenContinuationTextAdvance(before: MessageNode, after: MessageNode): Boolean {
    if (before.id != after.id || before.selectIndex != after.selectIndex ||
        before.messages.size != after.messages.size) return false
    return before.messages.zip(after.messages).all { (a, b) ->
        if (a == b) true else {
            if (a.id != before.currentMessage.id || a.role != MessageRole.ASSISTANT || a.finishedAt != null ||
                a.isSynthetic || a.orbisEvent != null || a.deletedToolRecords.isNotEmpty() ||
                a.parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning } ||
                b.parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning } ||
                b.parts.size < a.parts.size || b.finishedAt?.let { it < a.createdAt } == true ||
                a.copy(parts = b.parts, finishedAt = b.finishedAt, usage = b.usage) != b) return false
            a.parts.zip(b.parts).all { (old, next) ->
                old == next || when {
                    old is UIMessagePart.Text && next is UIMessagePart.Text ->
                        next.text.startsWith(old.text) && old.copy(text = next.text) == next
                    old is UIMessagePart.Reasoning && next is UIMessagePart.Reasoning ->
                        old.finishedAt == null && next.reasoning.startsWith(old.reasoning) &&
                            next.finishedAt?.let { it < old.createdAt } != true &&
                            old.copy(reasoning = next.reasoning, finishedAt = next.finishedAt) == next
                    else -> false
                }
            } && b.parts.drop(a.parts.size).all { part ->
                part !is UIMessagePart.Reasoning || part.finishedAt?.let { it >= part.createdAt } != false
            }
        }
    }
}

/** The disk checkpoint may finish one chunk after a human captures the visible baseline.
 * This only adds that exact, already durable text suffix. It cannot merge divergent edits,
 * grant/replay a tool, replace a receipt, or drop any later human turn/alternative.
 */
internal class ContinuationStreamEnrichment private constructor(
    val baseline: Conversation,
    val provenBaseline: Conversation,
    private val changes: Map<kotlin.uuid.Uuid, Pair<UIMessage, UIMessage>>,
) {
    fun project(snapshot: Conversation): Conversation? {
        if (snapshot.id != baseline.id || snapshot.assistantId != baseline.assistantId ||
            snapshot.compactionEpoch != baseline.compactionEpoch) return null
        return snapshot.copy(messageNodes = snapshot.messageNodes.map { node ->
            val messages = projectMessages(node.messages) ?: return null
            if (messages == node.messages) node else node.copy(messages = messages)
        })
    }

    fun projectMessages(messages: List<UIMessage>): List<UIMessage>? = messages.map { message ->
        val (before, after) = changes[message.id] ?: return@map message
        if (message.role != before.role || message.parts.size != before.parts.size) return null
        val parts = message.parts.mapIndexed { index, part ->
            val old = before.parts[index]; val saved = after.parts[index]
            if (old == saved) part else when {
                old is UIMessagePart.Reasoning && saved is UIMessagePart.Reasoning && part is UIMessagePart.Reasoning -> {
                    if (part.copy(reasoning = old.reasoning, finishedAt = old.finishedAt) != old) return null
                    when {
                        saved.reasoning.startsWith(part.reasoning) && part.reasoning.startsWith(old.reasoning) ->
                            part.copy(reasoning = saved.reasoning)
                        part.reasoning.startsWith(saved.reasoning) -> part
                        else -> return null
                    }
                }
                old is UIMessagePart.Text && saved is UIMessagePart.Text && part is UIMessagePart.Text -> {
                    if (part.copy(text = old.text) != old) return null
                    when {
                        saved.text.startsWith(part.text) && part.text.startsWith(old.text) -> part.copy(text = saved.text)
                        part.text.startsWith(saved.text) -> part
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        if (parts == message.parts) message else message.copy(parts = parts)
    }

    companion object {
        fun create(before: Conversation, after: Conversation): ContinuationStreamEnrichment? {
            if (before.id != after.id || before.assistantId != after.assistantId ||
                before.compactionEpoch != after.compactionEpoch || before.consultation != after.consultation ||
                before.messageNodes.size != after.messageNodes.size) return null
            val changes = mutableMapOf<kotlin.uuid.Uuid, Pair<UIMessage, UIMessage>>()
            for ((left, right) in before.messageNodes.zip(after.messageNodes)) {
                if (left.id != right.id || left.selectIndex != right.selectIndex || left.messages.size != right.messages.size) return null
                for ((a, b) in left.messages.zip(right.messages)) {
                    if (a == b) continue
                    if (a.role != MessageRole.ASSISTANT || a.isSynthetic || a.orbisEvent != null) return null
                    if (a.copy(parts = b.parts) != b || a.parts.size != b.parts.size) return null
                    for ((x, y) in a.parts.zip(b.parts)) {
                        if (x == y) continue
                        val extends = when {
                            x is UIMessagePart.Reasoning && y is UIMessagePart.Reasoning ->
                                x.finishedAt == null && x.copy(reasoning = y.reasoning) == y && y.reasoning.startsWith(x.reasoning)
                            x is UIMessagePart.Text && y is UIMessagePart.Text ->
                                a.finishedAt == null && x.copy(text = y.text) == y && y.text.startsWith(x.text)
                            else -> false
                        }
                        if (!extends) return null
                    }
                    changes[a.id] = a to b
                }
            }
            return if (changes.isEmpty()) null else ContinuationStreamEnrichment(before, after, changes)
        }
    }
}
