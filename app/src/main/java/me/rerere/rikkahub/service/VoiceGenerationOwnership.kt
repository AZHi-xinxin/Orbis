package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import kotlin.uuid.Uuid

/** A context summary belongs to the conversation, never to the call that requested it.
 * Existing historical fields are left intact here; repairing an old mislabel requires the
 * independent journal's exact-prefix proof and a Room compare-and-set, not a streaming edit.
 */
internal fun generationVoiceOwnership(
    message: UIMessage, baselineIds: Set<Uuid>, callId: String?, callKind: String?,
): UIMessage = if (callId != null && message.id !in baselineIds && !message.isCompactionSummary()) {
    message.copy(orbisVoiceCallId = callId, orbisVoiceCallKind = callKind)
} else message
