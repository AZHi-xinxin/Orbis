package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

/** Display projection only. Never use this list for persistence, model input, export or deletion. */
internal data class OrbisTimelineEntry(
    val nodes: List<MessageNode>,
    val firstSourceIndex: Int,
    val callId: String? = null,
    /** A call may span independent messages. Never infer these positions from an offset. */
    val sourceIndices: List<Int> = nodes.indices.map { firstSourceIndex + it },
) {
    val key get() = nodes.first().id
}

internal fun orbisCallTimeline(nodes: List<MessageNode>, enabled: Boolean = true): List<OrbisTimelineEntry> {
    data class Group(val callId: String?, val nodes: MutableList<MessageNode>, val indices: MutableList<Int>)
    val result = mutableListOf<Group>()
    val calls = mutableMapOf<String, Group>()
    nodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        // Ownership metadata is written by the host. Plain text resembling CALL_MODE is not ownership.
        // An approval/question must remain actionable; folding must not silently approve/hide it.
        val callId = message.orbisVoiceCallId?.takeIf {
            enabled && it.isNotBlank() && message.orbisVoiceCallKind != null &&
                message.parts.none { part -> part is UIMessagePart.Tool && part.isPending }
        }
        val existing = callId?.let(calls::get)
        if (existing != null) {
            existing.nodes.add(node)
            existing.indices.add(index)
        } else {
            val group = Group(callId, mutableListOf(node), mutableListOf(index))
            result += group
            if (callId != null) calls[callId] = group
        }
    }
    // Anchor each call at its first foldable source node. Independent messages and actionable
    // approvals retain their own positions relative to each other, with no duplicate call cards.
    return result.map { OrbisTimelineEntry(it.nodes.toList(), it.indices.first(), it.callId, it.indices.toList()) }
}

internal fun orbisTimelineIndex(nodes: List<MessageNode>, sourceIndex: Int, enabled: Boolean = true): Int {
    if (sourceIndex < 0 || nodes.isEmpty()) return 0
    val entries = orbisCallTimeline(nodes, enabled)
    val bounded = sourceIndex.coerceAtMost(nodes.lastIndex)
    return entries.indexOfFirst { bounded in it.sourceIndices }.coerceAtLeast(0)
}
