package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus

/** Explicit human acknowledgement, not remote-idle evidence or permission to retry old work. */
internal fun mayRecoverFutureAutomaticWakes(
    freshStatus: FreshHumanRecoveryStatus,
    automaticHoldReason: String?,
    localReady: Boolean,
    transportClear: Boolean,
    activeCall: Boolean,
): Boolean = localReady && transportClear && !activeCall &&
    freshStatus in setOf(FreshHumanRecoveryStatus.NONE, FreshHumanRecoveryStatus.DETACHED) &&
    automaticHoldReason in setOf(null, "unknown_tool_result", "legacy_unknown_tool_result")

/**
 * Call under the local admission barrier and history/inbox locks. The independent durable guard
 * remains closed across every partial commit and process restart. No dispatch, model, remote
 * stop, tool execution or human queue resume belongs in this transaction.
 */
internal fun commitFutureAutomaticWakeRecovery(
    stillOwner: () -> Boolean,
    establishGuard: () -> Unit,
    preservePreviousInputs: () -> Unit,
    suppressPreviousEvents: () -> Unit,
    verifyPreviousEvents: () -> Boolean,
    clearFreshRestriction: () -> Unit,
    acknowledgeAutomaticHold: () -> Unit,
    releaseGuard: () -> Unit,
): Boolean {
    if (!stillOwner()) return false
    establishGuard()
    preservePreviousInputs()
    suppressPreviousEvents()
    check(verifyPreviousEvents()) { "future_wake_suppression_not_durable" }
    if (!stillOwner()) return false
    clearFreshRestriction()
    acknowledgeAutomaticHold()
    releaseGuard()
    return true
}
