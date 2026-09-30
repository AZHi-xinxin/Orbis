package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Host-only cancellation of one exact voice turn, not permission to resume an old queue. */
internal class VoiceBargeInCancellation(val messageId: Uuid, val callId: String) : CancellationException("voice_turn_interrupted_by_user") {
    @Volatile var partialSafelySaved = false
}

internal fun Throwable.isSavedVoiceInterruption(): Boolean = this is VoiceBargeInCancellation && partialSafelySaved

internal fun canAcknowledgeVoiceInterruption(partialSaved: Boolean, queuePaused: Boolean,
    recoveryBlocked: Boolean, unknownToolIds: Set<String>): Boolean =
    partialSaved && !queuePaused && !recoveryBlocked && unknownToolIds.isEmpty()
