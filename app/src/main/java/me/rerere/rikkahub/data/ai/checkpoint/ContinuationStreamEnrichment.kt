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
internal fun isProvenContinuationTextAdvance(before: MessageNode, after: MessageNode,
    allowUnchangedExecutedTools: Boolean = false): Boolean {
    if (before.id != after.id || before.selectIndex != after.selectIndex ||
        before.messages.size != after.messages.size) return false
    return before.messages.zip(after.messages).all { (a, b) ->
        if (a == b) true else {
            if (a.id != before.currentMessage.id || a.role != MessageRole.ASSISTANT || a.finishedAt != null ||
                a.isSynthetic || a.orbisEvent != null || a.deletedToolRecords.isNotEmpty() ||
                a.parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning &&
                    !(allowUnchangedExecutedTools && it is UIMessagePart.Tool && it.isExecuted) } ||
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
                part is UIMessagePart.Text || part is UIMessagePart.Reasoning &&
                    part.finishedAt?.let { it >= part.createdAt } != false
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
        if (message.parts.size !in before.parts.size..after.parts.size ||
            message.finishedAt != before.finishedAt && message.finishedAt != after.finishedAt ||
            message.usage != before.usage && message.usage != after.usage ||
            message.copy(parts = before.parts, finishedAt = before.finishedAt, usage = before.usage,
                orbisRecalled = before.orbisRecalled, orbisQuoteRecalled = before.orbisQuoteRecalled) != before)
            return null
        val parts = after.parts.mapIndexed { index, saved ->
            val old = before.parts.getOrNull(index)
            val part = message.parts.getOrNull(index)
            if (old == null) {
                // Only the journal's exact appended parts are admitted. A locally stopped
                // reasoning timer may already be closed; never reopen or replace that timer.
                when {
                    part == null || part == saved -> saved
                    part is UIMessagePart.Reasoning && saved is UIMessagePart.Reasoning &&
                        part.copy(finishedAt = saved.finishedAt) == saved &&
                        part.finishedAt?.let { it >= part.createdAt } == true -> part
                    else -> return null
                }
            } else when {
                old == saved && part == old -> part
                old is UIMessagePart.Reasoning && saved is UIMessagePart.Reasoning && part is UIMessagePart.Reasoning -> {
                    if (part.copy(reasoning = old.reasoning, finishedAt = old.finishedAt) != old ||
                        part.finishedAt?.let { it < part.createdAt } == true) return null
                    when {
                        saved.reasoning.startsWith(part.reasoning) && part.reasoning.startsWith(old.reasoning) ->
                            part.copy(reasoning = saved.reasoning, finishedAt = part.finishedAt ?: saved.finishedAt)
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
        message.copy(parts = parts, finishedAt = message.finishedAt ?: after.finishedAt, usage = after.usage)
    }

    companion object {
        fun create(before: Conversation, after: Conversation): ContinuationStreamEnrichment? {
            if (before.id != after.id || before.assistantId != after.assistantId ||
                before.compactionEpoch != after.compactionEpoch || before.consultation != after.consultation ||
                before.messageNodes.size != after.messageNodes.size) return null
            val changes = mutableMapOf<kotlin.uuid.Uuid, Pair<UIMessage, UIMessage>>()
            for ((left, right) in before.messageNodes.zip(after.messageNodes)) {
                if (!isProvenContinuationTextAdvance(left, right, allowUnchangedExecutedTools = true)) return null
                for ((a, b) in left.messages.zip(right.messages)) {
                    if (a == b) continue
                    changes[a.id] = a to b
                }
            }
            return if (changes.isEmpty()) null else ContinuationStreamEnrichment(before, after, changes)
        }
    }
}
