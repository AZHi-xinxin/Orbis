package me.rerere.rikkahub.service

/**
 * Preserves old input before granting the next explicit human action. The caller has already
 * stopped local producers and saved their history. No remote status is read or implied here.
 * Callbacks must be synchronous and must not dispatch input or release this queue themselves.
 */
internal fun commitDismissedPauseForFreshInput(
    queue: MessageQueue,
    authorizeFreshInput: () -> Unit,
    replaceGate: () -> Unit,
) = synchronized(queue) {
    queue.holdAllInputsForFreshRecovery()
    try {
        queue.pause()
        authorizeFreshInput()
        replaceGate()
        queue.resume()
        check(!queue.state.value.paused) { "fresh_input_queue_resume_not_persisted" }
    } catch (failure: Throwable) {
        // pause changes RAM before touching storage, so even a failed rollback cannot replay.
        try {
            queue.pause()
        } catch (pauseFailure: Throwable) {
            if (pauseFailure !== failure) failure.addSuppressed(pauseFailure)
        }
        throw failure
    }
}
