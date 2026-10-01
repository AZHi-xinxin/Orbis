package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

/** Display projection only. Never use this list for persistence, model input, export or deletion. */
internal data class OrbisTimelineEntry(
    val nodes: List<MessageNode>,
    val firstSourceIndex: Int,
    val callId: String? = null,
) {
    val key get() = nodes.first().id
}

internal fun orbisCallTimeline(nodes: List<MessageNode>, enabled: Boolean = true): List<OrbisTimelineEntry> {
    val result = mutableListOf<OrbisTimelineEntry>()
    var groupedNodes: MutableList<MessageNode>? = null
    nodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        // Ownership metadata is written by the host. Plain text resembling CALL_MODE is not ownership.
        // An approval/question must remain actionable; folding must not silently approve/hide it.
        val callId = message.orbisVoiceCallId?.takeIf {
            enabled && it.isNotBlank() && message.orbisVoiceCallKind != null &&
                message.parts.none { part -> part is UIMessagePart.Tool && part.isPending }
        }
        val previous = result.lastOrNull()
        if (callId != null && previous?.callId == callId) {
            checkNotNull(groupedNodes).add(node)
        } else {
            groupedNodes = mutableListOf(node)
            result += OrbisTimelineEntry(checkNotNull(groupedNodes), index, callId)
        }
    }
    return result.map { it.copy(nodes = it.nodes.toList()) }
}

internal fun orbisTimelineIndex(nodes: List<MessageNode>, sourceIndex: Int, enabled: Boolean = true): Int {
    if (sourceIndex < 0) return 0
    val entries = orbisCallTimeline(nodes, enabled)
    return entries.indexOfLast { it.firstSourceIndex <= sourceIndex }.coerceAtLeast(0)
}
