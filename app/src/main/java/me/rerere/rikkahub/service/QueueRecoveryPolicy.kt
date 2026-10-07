package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class QueueRecoveryPhase { IDLE, RUNNING, SUCCESS, PENDING, FAILURE }

data class QueueRecoveryState(
    val phase: QueueRecoveryPhase = QueueRecoveryPhase.IDLE,
    val message: String = "",
    val retainedMessageCount: Int = 0,
    val heldCallMessageCount: Int = 0,
    val needsReview: Boolean = false,
) {
    val isRunning: Boolean get() = phase == QueueRecoveryPhase.RUNNING
}

typealias QueueRecoveryResult = QueueRecoveryState

/** Consume presentation only; a stale acknowledgement must not hide a newer recovery result. */
internal fun <K> consumeQueueRecoveryNotice(
    notices: Map<K, QueueRecoveryState>,
    conversationId: K,
    expected: QueueRecoveryState,
): Map<K, QueueRecoveryState> {
    val current = notices[conversationId] ?: return notices
    if (current !== expected || current.phase == QueueRecoveryPhase.IDLE || current.isRunning) return notices
    // This map contains no queue, transport or tool-safety authority. Dismissal never resumes work.
    return notices - conversationId
}

/** Evaluate under the session lock and revalidate after every suspending recovery boundary. */
internal data class QueueRecoveryAdmission(
    val sessionMatches: Boolean,
    val ownerMatches: Boolean,
    val unfinishedJobs: Boolean,
    val submitting: Boolean,
    val manualWrite: Boolean,
    val checkpointBlocked: Boolean,
    val pendingTools: Boolean,
) {
    val ready: Boolean get() = sessionMatches && ownerMatches && !unfinishedJobs &&
        !submitting && !manualWrite && !checkpointBlocked && !pendingTools
}

internal enum class GatewayRecoveryDisposition { SAFE, PENDING, FAILURE }

/** Ephemeral exact-request evidence, never a tool-success receipt or permission to replay. */
internal data class GatewayRecoverySummary<T>(
    val disposition: GatewayRecoveryDisposition,
    val unconfirmed: List<T>,
    val checked: Int = 0,
    val retired: Int = 0,
    val pending: Int = 0,
    val uncertain: Boolean = false,
    val capabilityEvidence: List<T> = emptyList(),
) {
    val safe: Boolean get() = disposition == GatewayRecoveryDisposition.SAFE

    // Generic request handles can contain private routing/authentication details.
    override fun toString(): String =
        "GatewayRecoverySummary(disposition=$disposition, checked=$checked, retired=$retired, " +
            "pending=$pending, uncertain=$uncertain, unconfirmedCount=${unconfirmed.size})"
}

/**
 * The caller must first prove that every local worker belonging to this invocation has ended,
 * and prevent a successor from starting until it has consumed this result. This policy never
 * cancels work, resumes queues, executes tools, or retries a mutation within one check.
 *
 * [requests] and optional [capabilityEvidence] must belong to the same captured ended invocation;
 * never substitute the broad manual-stop ledger, current settings, or another invocation. A
 * capability proves only the captured scope; a fresh status still authorizes each exact finish.
 * For a later explicit check, pass [GatewayRecoverySummary.unconfirmed] together with its
 * [GatewayRecoverySummary.capabilityEvidence]. A resolved old request may remain the only proof
 * for a continuation whose successful response headers were lost. Do not persist these handles.
 *
 * [knownDirect] must positively recognize a captured trusted direct-provider destination. Missing
 * capability alone never implies direct transport. An advertised request always follows the
 * gateway path, even if its destination would otherwise be classified as direct.
 *
 * SAFE means every supplied nonempty request was positively direct, NOT_CURRENT, or exactly
 * RETIRED. It does not resolve unknown external side effects, approve a pending tool, or prove
 * storage durability. Checked counts local direct confirmations and remote probe attempts.
 *
 * Only an explicit human recovery may opt into [explicitProbeWithoutCapability]. Its exact
 * remembered request may be status-checked even if response headers were lost, but retirement
 * then uses [manualStop] with that status's fresh permit, NEVER automatic finish without proof.
 */
internal suspend fun <T> recoverEndedGatewayRequests(
    requests: List<T>,
    advertised: (T) -> Boolean,
    sameScope: (T, T) -> Boolean,
    probe: suspend (T) -> GatewayStopProbe,
    finish: suspend (request: T, capabilityEvidence: T) -> Boolean,
    timeoutMs: Long = 15_000,
    capabilityEvidence: List<T> = emptyList(),
    knownDirect: (T) -> Boolean = { false },
    explicitProbeWithoutCapability: Boolean = false,
    manualStop: (suspend (T) -> Boolean)? = null,
): GatewayRecoverySummary<T> {
    require(timeoutMs > 0) { "Recovery timeout must be positive" }
    require(!explicitProbeWithoutCapability || manualStop != null)
    currentCoroutineContext().ensureActive()
    val snapshot = requests.toList()
    val proofs = mutableListOf<T>()
    var failed = false
    var uncertain = snapshot.isEmpty()
    for (candidate in capabilityEvidence.toList() + snapshot) {
        try {
            if (advertised(candidate) && candidate !in proofs) proofs += candidate
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            uncertain = true
        }
    }
    val confirmed = BooleanArray(snapshot.size)
    var checked = 0
    var retired = 0
    val completed = withTimeoutOrNull(timeoutMs) {
        // Preserve overflow as unconfirmed; never report a bounded prefix as the whole result.
        for ((index, request) in snapshot.take(16).withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                if (!advertised(request) && knownDirect(request)) {
                    checked++
                    confirmed[index] = true
                    continue
                }
                val proof = proofs.firstOrNull { sameScope(request, it) }
                if (proof == null && !explicitProbeWithoutCapability) {
                    uncertain = true
                    continue
                }
                checked++
                when (probe(request)) {
                    GatewayStopProbe.NOT_CURRENT -> confirmed[index] = true
                    GatewayStopProbe.CAN_STOP -> if (if (proof != null) finish(request, proof)
                        else checkNotNull(manualStop).invoke(request)) {
                        confirmed[index] = true
                        retired++
                    }
                    GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING -> Unit
                    GatewayStopProbe.UNSUPPORTED -> uncertain = true
                }
            } catch (cancelled: CancellationException) {
                // withTimeoutOrNull handles only our deadline; genuine outer cancellation escapes.
                throw cancelled
            } catch (_: Exception) {
                failed = true
                uncertain = true
                // A failed request must not hide another exact old wait; inspect the next item.
            }
        }
        true
    } == true
    currentCoroutineContext().ensureActive()
    if (!completed || snapshot.size > 16) uncertain = true
    val unresolved = snapshot.filterIndexed { index, _ -> !confirmed[index] }
    val disposition = when {
        failed -> GatewayRecoveryDisposition.FAILURE
        snapshot.isNotEmpty() && unresolved.isEmpty() && !uncertain -> GatewayRecoveryDisposition.SAFE
        else -> GatewayRecoveryDisposition.PENDING
    }
    // A timeout at the end or an earlier evidence-read failure can leave positive per-item
    // receipts but an uncertain aggregate. Keep a verification set: passing an empty subset on
    // the next explicit check cannot prove safety and would otherwise create a permanent hold.
    val unconfirmed = if (disposition != GatewayRecoveryDisposition.SAFE && unresolved.isEmpty())
        snapshot else unresolved
    return GatewayRecoverySummary(
        disposition = disposition,
        unconfirmed = unconfirmed,
        checked = checked,
        retired = retired,
        pending = unconfirmed.size,
        uncertain = uncertain,
        capabilityEvidence = proofs.toList(),
    )
}
