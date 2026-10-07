package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.QueuePauseStatus

/**
 * The route is independent of ephemeral ended-request handles. A later failed video frame can
 * leave such handles in the same process; requiring a restart to use current-lane recovery
 * strands that live process indefinitely. Ordinary human/local pauses still avoid this route.
 * Selection is not authorization: the caller verifies captured owners and durable scope, then
 * requires a fresh remote IDLE proof and the existing final local/durable admission checks.
 */
internal fun shouldUseGatewayThreadRecovery(
    freshStatus: FreshHumanRecoveryStatus,
    gatewayStatus: QueuePauseStatus,
    automaticHoldReason: String?,
): Boolean = freshStatus == FreshHumanRecoveryStatus.ACTIVE ||
    gatewayStatus == QueuePauseStatus.PAUSED ||
    gatewayStatus == QueuePauseStatus.UNPAUSED && automaticHoldReason == "gateway_terminal_unconfirmed"

internal enum class ThreadRecoveryState { IDLE, OWNED_BUSY, BUSY, UNSUPPORTED, UNCONFIRMED, OWNER_CHANGED }
internal class ThreadRecoveryProbe<T>(val state: ThreadRecoveryState, val request: T? = null) {
    override fun toString() = "ThreadRecoveryProbe(state=$state)"
}

/**
 * Explicit recovery only. Reattach only the server's real exact handle, stop only its delivered
 * wait, then independently re-probe thread idle. NOT_CURRENT is never thread-idle evidence.
 * The caller keeps local admission closed and performs its durable release with a final CAS.
 */
internal suspend fun <T> recoverCurrentGatewayThread(
    probeThread: suspend () -> ThreadRecoveryProbe<T>,
    probeRequest: suspend (T) -> GatewayStopProbe,
    stopRequest: suspend (T) -> Boolean,
    stillOwner: () -> Boolean,
    timeoutMs: Long = 15_000,
): ThreadRecoveryState {
    require(timeoutMs > 0)
    if (!stillOwner()) return ThreadRecoveryState.OWNER_CHANGED
    return try {
        withTimeoutOrNull(timeoutMs) {
            val first = probeThread()
            if (!stillOwner()) return@withTimeoutOrNull ThreadRecoveryState.OWNER_CHANGED
            when (first.state) {
                ThreadRecoveryState.IDLE -> ThreadRecoveryState.IDLE
                ThreadRecoveryState.OWNED_BUSY -> {
                    val request = first.request ?: return@withTimeoutOrNull ThreadRecoveryState.UNCONFIRMED
                    val exact = probeRequest(request)
                    if (!stillOwner()) return@withTimeoutOrNull ThreadRecoveryState.OWNER_CHANGED
                    when (exact) {
                        GatewayStopProbe.CAN_STOP -> {
                            if (!stopRequest(request)) return@withTimeoutOrNull ThreadRecoveryState.UNCONFIRMED
                            if (!stillOwner()) return@withTimeoutOrNull ThreadRecoveryState.OWNER_CHANGED
                        }
                        GatewayStopProbe.NOT_CURRENT -> Unit
                        GatewayStopProbe.UNSUPPORTED -> return@withTimeoutOrNull ThreadRecoveryState.UNSUPPORTED
                        GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING ->
                            return@withTimeoutOrNull ThreadRecoveryState.BUSY
                    }
                    val after = probeThread()
                    if (!stillOwner()) ThreadRecoveryState.OWNER_CHANGED
                    else if (after.state == ThreadRecoveryState.IDLE) ThreadRecoveryState.IDLE
                    else if (after.state == ThreadRecoveryState.OWNED_BUSY) ThreadRecoveryState.BUSY
                    else after.state
                }
                else -> first.state
            }
        } ?: ThreadRecoveryState.UNCONFIRMED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { ThreadRecoveryState.UNCONFIRMED }
}

/** Actual durable release ordering, invoked under the session and history locks after idle proof. */
internal fun commitRecoveredGatewayLane(
    stillOwner: () -> Boolean,
    establishTransportGuard: () -> Unit,
    preservePreviousInputs: () -> Unit,
    reconcileAutomaticHold: () -> Unit,
    clearScope: () -> Unit,
    clearFreshRestriction: () -> Unit,
    resumeHumanQueue: () -> Unit,
    clearTransportGuard: () -> Unit,
    releaseMemory: () -> Unit,
): Boolean {
    if (!stillOwner()) return false
    establishTransportGuard()
    preservePreviousInputs()
    reconcileAutomaticHold()
    clearScope()
    clearFreshRestriction()
    resumeHumanQueue()
    clearTransportGuard()
    releaseMemory()
    return true
}
