package me.rerere.rikkahub.data.ai

import java.io.IOException

/** Receiving even a metadata/tool-only chunk means the provider has started this attempt. */
internal fun canAutomaticallyRetryProvider(
    error: Throwable,
    enabled: Boolean,
    retriesSoFar: Int,
    retryLimit: Int,
    receivedAnyChunk: Boolean = false,
): Boolean = enabled && !receivedAnyChunk && error is IOException && retriesSoFar in 0 until retryLimit
