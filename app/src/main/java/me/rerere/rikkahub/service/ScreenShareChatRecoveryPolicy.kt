package me.rerere.rikkahub.service

/** Read-only admission facts. A red overlay indicator must not itself resume or replay a turn. */
internal data class ScreenShareChatAdmission(
    val ready: Boolean = true,
    val ownerMatches: Boolean = true,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val queuePaused: Boolean = false,
    val pendingTools: Boolean = false,
    val recoveryBlocked: Boolean = false,
    val gatewayBlocked: Boolean = false,
) {
    val blockReason: String? get() = when {
        !ownerMatches -> "owner_changed"
        !ready -> "not_ready"
        saving -> "saving"
        busy -> "busy"
        recoveryBlocked -> "recovery_blocked"
        pendingTools -> "pending_tools"
        queuePaused -> "queue_paused"
        gatewayBlocked -> "gateway_blocked"
        else -> null
    }

    // Explicit overlay retry may dismiss an ended text-only pause for NEW input only.
    // It may not cancel a healthy generation or settle an unknown tool/checkpoint.
    val mayDismissForFreshInput: Boolean get() = ready && ownerMatches && !busy && !saving &&
        !pendingTools && !recoveryBlocked && queuePaused
}
