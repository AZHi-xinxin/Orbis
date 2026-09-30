package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** A presentation-only request, never a replacement message or conversation snapshot. */
data class OrbisEventPresentationEdit(
    val nodeId: Uuid,
    val messageId: Uuid,
    val expected: OrbisEventMetadata,
    val originalText: String,
    val read: Boolean,
    val collapsed: Boolean,
)

/** Include every provenance field (including occurredAt and future added fields). */
internal fun OrbisEventMetadata.sameEventIdentity(other: OrbisEventMetadata): Boolean =
    copy(read = false, collapsed = true) == other.copy(read = false, collapsed = true)

internal fun UIMessage.matchesEventPresentation(edit: OrbisEventPresentationEdit): Boolean =
    id == edit.messageId && role == MessageRole.USER &&
        parts == listOf(UIMessagePart.Text(edit.originalText)) &&
        orbisEvent?.sameEventIdentity(edit.expected) == true

internal fun UIMessage.withEventPresentation(edit: OrbisEventPresentationEdit): UIMessage {
    check(matchesEventPresentation(edit)) { "event_message_missing_or_changed" }
    val existing = requireNotNull(orbisEvent)
    return copy(orbisEvent = existing.copy(read = existing.read || edit.read, collapsed = edit.collapsed))
}

/** Preserve only committed UI flags for the exact same event and payload; never resurrect nodes. */
internal fun preserveEventPresentation(incoming: MessageNode, committed: MessageNode): MessageNode {
    if (incoming.id != committed.id) return incoming
    if (incoming === committed || incoming.messages === committed.messages ||
        committed.messages.none { it.orbisEvent != null }) return incoming
    val known = committed.messages.associateBy { it.id }
    val merged = incoming.messages.mapPreservingIdentity { message ->
        val previous = known[message.id]
        if (message === previous) return@mapPreservingIdentity message
        val metadata = message.orbisEvent
        val stored = previous?.orbisEvent
        if (metadata != null && stored != null && previous != null && message.role == previous.role &&
            message.parts == previous.parts && metadata.sameEventIdentity(stored)) {
            if (metadata.read == stored.read && metadata.collapsed == stored.collapsed) message
            else message.copy(orbisEvent = metadata.copy(read = stored.read, collapsed = stored.collapsed))
        } else message
    }
    return if (merged === incoming.messages) incoming else incoming.copy(messages = merged)
}

internal fun withCommittedEventPresentation(snapshot: Conversation, current: Conversation): Conversation {
    if (snapshot.id != current.id || snapshot.assistantId != current.assistantId) return snapshot
    if (snapshot === current || snapshot.messageNodes === current.messageNodes ||
        current.messageNodes.none { node -> node.messages.any { it.orbisEvent != null } }) return snapshot
    val known = current.messageNodes.associateBy { it.id }
    val merged = snapshot.messageNodes.mapPreservingIdentity { node ->
        known[node.id]?.let { preserveEventPresentation(node, it) } ?: node
    }
    return if (merged === snapshot.messageNodes) snapshot else snapshot.copy(messageNodes = merged)
}

internal fun Conversation.applyEventPresentation(
    owner: Uuid,
    edit: OrbisEventPresentationEdit,
): Conversation {
    check(assistantId == owner) { "event_target_missing_or_changed" }
    val node = messageNodes.singleOrNull { it.id == edit.nodeId }
        ?: error("event_message_missing_or_changed")
    val message = node.messages.singleOrNull { it.id == edit.messageId }
        ?: error("event_message_missing_or_changed")
    val updated = message.withEventPresentation(edit)
    return copy(messageNodes = messageNodes.map { current ->
        if (current.id == node.id) current.copy(messages = current.messages.map {
            if (it.id == message.id) updated else it
        }) else current
    })
}
