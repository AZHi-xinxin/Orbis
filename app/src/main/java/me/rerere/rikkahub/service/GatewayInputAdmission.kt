package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException

/** Shared real launch boundary: a permit selected before IO must still own this exact input after IO. */
internal suspend fun <T> withGatewayInputAdmission(
    input: QueuedMessage?,
    awaitHistory: suspend () -> Unit,
    isBlocked: (QueuedMessage?) -> Boolean,
    block: suspend () -> T,
): T {
    awaitHistory()
    if (isBlocked(input)) throw CancellationException("gateway_stop_check_in_progress")
    return block()
}
