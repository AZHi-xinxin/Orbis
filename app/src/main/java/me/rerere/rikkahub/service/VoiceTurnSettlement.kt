package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** Per-send evidence. A controller cancellation is not a generic permission to clear a hold. */
internal class VoiceTurnSettlement {
    private var generationEntered = false
    private var generationReturned = false
    var independentFailureSaved: Boolean = false
        private set

    /** Called immediately before the generation's own protected dispatch/cleanup region. */
    fun enterGeneration() { generationEntered = true }
    fun returnedFromGeneration() { generationReturned = true }
    fun savedIndependentFailure() { independentFailureSaved = true }

    /**
     * Cancellation before dispatch or after the inner handler returns has no in-flight producer.
     * The caller must still durably settle local writes and check all existing safety holds.
     * A cancellation inside generation belongs to that handler; never bypass its failed proof.
     */
    suspend fun acknowledgeOuterCancellation(
        error: Throwable,
        exactVoiceOwner: Boolean,
        persistAndCheck: suspend () -> Boolean,
    ) {
        if (error !is VoiceBargeInCancellation || error.partialSafelySaved || !exactVoiceOwner ||
            generationEntered && !generationReturned) return
        withContext(NonCancellable) {
            error.partialSafelySaved = try { persistAndCheck() } catch (_: Exception) { false }
        }
    }
}

/** New speech waits for its cancelled predecessor's persistence, never retries that input. */
internal suspend fun awaitCancelledVoiceGeneration(job: Job?) {
    if (job != null && job.isCancelled && !job.isCompleted) {
        withTimeout(8_000L) { job.join() }
    }
}
