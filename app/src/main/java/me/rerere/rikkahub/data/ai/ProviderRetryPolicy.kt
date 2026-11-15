package me.rerere.rikkahub.data.ai

import java.io.IOException

/** Retry the current provider step; its partial output is replaced, never appended. */
internal fun canAutomaticallyRetryProvider(
    error: Throwable,
    enabled: Boolean,
    retriesSoFar: Int,
    retryLimit: Int,
    receivedAnyChunk: Boolean = false,
): Boolean = enabled && error is IOException && retriesSoFar in 0 until retryLimit
