package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A broken synthesis/playback segment does not fail model generation, ASR, or screen capture. */
internal suspend fun runScreenShareSpeechAttempt(
    timeoutMillis: Long = 180_000L,
    stop: () -> Unit,
    play: suspend () -> Boolean,
): Boolean = try {
    (withTimeoutOrNull(timeoutMillis) { play() } ?: false).also { if (!it) runCatching(stop) }
} catch (cancelled: CancellationException) {
    runCatching(stop); throw cancelled
} catch (_: Exception) {
    runCatching(stop); false
}
