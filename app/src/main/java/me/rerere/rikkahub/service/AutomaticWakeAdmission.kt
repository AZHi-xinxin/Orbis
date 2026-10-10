package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import me.rerere.rikkahub.data.orbis.GatewayRecoveryScopeStatus

/** A reason, not a waiting list. Never use a human pause as an automatic master switch. */
internal data class AutomaticWakeReadiness(
    val resetting: Boolean = false,
    val localRecoveryBlocked: Boolean = false,
    val gatewayRecoveryBlocked: Boolean = false,
    val freshStatus: FreshHumanRecoveryStatus = FreshHumanRecoveryStatus.NONE,
    val pauseStorageReady: Boolean = true,
    val queueStatus: QueuePauseStatus = QueuePauseStatus.UNPAUSED,
    val recoveryGuard: QueuePauseStatus = QueuePauseStatus.UNPAUSED,
    val automaticHold: QueuePauseStatus = QueuePauseStatus.UNPAUSED,
    val automaticHoldReason: String? = null,
) {
    fun restriction(): String? = when {
        resetting -> "wake_recovery_in_progress"
        localRecoveryBlocked -> "wake_local_recovery_unconfirmed"
        !pauseStorageReady || queueStatus == QueuePauseStatus.UNAVAILABLE ||
            recoveryGuard == QueuePauseStatus.UNAVAILABLE || automaticHold == QueuePauseStatus.UNAVAILABLE ->
            "wake_storage_unavailable"
        gatewayRecoveryBlocked -> "wake_gateway_unconfirmed"
        freshStatus == FreshHumanRecoveryStatus.OWNER_CHANGED -> "wake_owner_changed"
        freshStatus == FreshHumanRecoveryStatus.UNAVAILABLE -> "wake_storage_unavailable"
        freshStatus != FreshHumanRecoveryStatus.NONE -> "wake_fresh_input_only"
        recoveryGuard != QueuePauseStatus.UNPAUSED -> "wake_recovery_commit_unconfirmed"
        automaticHold != QueuePauseStatus.UNPAUSED -> when (automaticHoldReason) {
            "unknown_tool_result", "legacy_unknown_tool_result" -> "wake_unknown_tool_result"
            "gateway_terminal_unconfirmed" -> "wake_gateway_unconfirmed"
            "event_receipt_not_saved", "partial_snapshot_not_saved" -> "wake_receipt_unconfirmed"
            else -> "wake_local_recovery_unconfirmed"
        }
        else -> null
    }
}

internal fun automaticWakeAdmissionReason(
    restriction: String?, busy: Boolean, saving: Boolean, pendingApproval: Boolean,
    readyHumanInput: Boolean,
): String? = restriction ?: when {
    saving -> "wake_save_in_progress"
    busy -> "wake_reply_in_progress"
    pendingApproval -> "wake_tool_pending"
    readyHumanInput -> "wake_human_input_first"
    else -> null
}

/** Called while holding the conversation admission lock. No enqueue/retry callback exists. */
internal fun <T> dispatchAutomaticWakeOnce(
    reason: String?, skip: (String) -> Unit, dispatch: () -> T,
): T? {
    if (reason != null) {
        skip(reason)
        return null
    }
    return dispatch()
}

/** Auto repair requires a recorded matching destination. Legacy scope needs a human click. */
internal fun mayRefreshAutomaticWakeTransport(
    scope: GatewayRecoveryScopeStatus, explicitHuman: Boolean, localReady: Boolean,
    freshStatus: FreshHumanRecoveryStatus, automaticReason: String?,
): Boolean = localReady && (scope == GatewayRecoveryScopeStatus.MATCH ||
    explicitHuman && scope == GatewayRecoveryScopeStatus.LEGACY) &&
    freshStatus in setOf(FreshHumanRecoveryStatus.NONE, FreshHumanRecoveryStatus.ACTIVE, FreshHumanRecoveryStatus.DETACHED) &&
    automaticReason in setOf(null, "gateway_terminal_unconfirmed", "unknown_tool_result", "legacy_unknown_tool_result")
