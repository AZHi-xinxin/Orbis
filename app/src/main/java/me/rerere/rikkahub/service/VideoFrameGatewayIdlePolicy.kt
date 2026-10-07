package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.util.OrbisGatewayThreadState

internal const val VIDEO_FRAME_IDLE_PROBE_TIMEOUT_MS = 2_000L
internal const val VOICE_INTERRUPTION_IDLE_PROBE_TIMEOUT_MS = 6_000L

/** A failed disposable camera observation may prove idle, never stop or replay any request. */
internal suspend fun mayAvoidNewVideoFrameGatewayHold(
    automaticFinishAttempted: Boolean,
    hasCapturedRequests: Boolean,
    knownGatewayPeer: Boolean,
    hasUnsettledPredecessor: Boolean,
    capturedScopeMatches: Boolean,
    stillSafeFailedFrame: () -> Boolean,
    probeIdle: suspend () -> OrbisGatewayThreadState,
    waitForCancelledProducer: Boolean = false,
): Boolean {
    if (automaticFinishAttempted || !hasCapturedRequests || !knownGatewayPeer ||
        hasUnsettledPredecessor || !capturedScopeMatches || !stillSafeFailedFrame()) return false
    return try {
        val state = withTimeoutOrNull(if (waitForCancelledProducer) VOICE_INTERRUPTION_IDLE_PROBE_TIMEOUT_MS
            else VIDEO_FRAME_IDLE_PROBE_TIMEOUT_MS) {
            var current = probeIdle()
            // The cancelled HTTP stream and the server disconnect watcher settle independently.
            // Busy is never evidence of idle; only repeat this read within the same safety scope.
            while (waitForCancelledProducer && current in setOf(OrbisGatewayThreadState.OWNED_BUSY,
                    OrbisGatewayThreadState.BUSY) && stillSafeFailedFrame()) {
                delay(250)
                if (!stillSafeFailedFrame()) break
                current = probeIdle()
            }
            current
        }
        state == OrbisGatewayThreadState.IDLE && stillSafeFailedFrame()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }
}
