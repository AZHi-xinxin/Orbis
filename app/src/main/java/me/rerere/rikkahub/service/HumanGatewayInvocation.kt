package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.OrbisGatewayTerminalReason

internal data class HumanGatewayOutcome(
    val completed: Boolean,
    val pendingApproval: GatewayToolBatchIdentity? = null,
    val stopped: OrbisGatewayTerminalReason? = null,
)

/** The independent human lane needs the same exact-request retirement as ordinary generation.
 * Do not borrow the conversation-wide request ledger: it may already contain a newer owner.
 * Cleanup runs after the producer/tool stack unwinds, and never replays a request or tool.
 */
internal suspend fun <T> withHumanGatewayInvocation(
    tracker: GatewayInvocationTracker<T>,
    conversationId: String,
    resumedApproval: GatewayToolBatchIdentity?,
    settle: suspend (List<T>, OrbisGatewayTerminalReason) -> Unit,
    generate: suspend ((T) -> Unit) -> HumanGatewayOutcome,
): Boolean {
    val invocation = tracker.begin(conversationId, resumedApproval)
    val outcome = try {
        generate { tracker.remember(invocation, it) }
    } catch (failure: Throwable) {
        val requests = tracker.requests(invocation)
        tracker.finish(invocation)
        // The caller supplies the bounded, non-cancellable existing terminal protocol.
        // A cleanup exception must not replace the original generation failure/cancellation.
        try {
            settle(requests, if (failure is CancellationException)
                OrbisGatewayTerminalReason.CANCELLED else OrbisGatewayTerminalReason.FAILED)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    val reason = outcome.stopped ?: if (!outcome.completed && outcome.pendingApproval == null)
        OrbisGatewayTerminalReason.FAILED else null
    if (reason != null) {
        val requests = tracker.requests(invocation)
        tracker.finish(invocation)
        settle(requests, reason)
    } else {
        outcome.pendingApproval?.let { tracker.retainForApproval(invocation, it) }
        tracker.finish(invocation)
    }
    return outcome.completed
}
