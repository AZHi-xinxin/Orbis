package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.orbis.QueuePauseStatus

/** No gateway requests are sent for an ordinary ended provider invocation. This proves only
 * local readiness, never that an unobserved remote server is idle. Known ST evidence wins. */
internal fun localOnlyQueueRecoveryAllowed(
    hasEndedOwner: Boolean,
    observedGatewayPeer: Boolean,
    existingGatewayHold: Boolean,
): Boolean = hasEndedOwner && !observedGatewayPeer && !existingGatewayHold

/** An ordinary persisted queue pause is not evidence of an abandoned gateway owner. Call only
 * after local jobs/checkpoints/tools are settled; strict hold reads must complete successfully. */
internal fun mayRecoverWithoutGatewayOwner(
    sessionGatewayBlocked: Boolean,
    durableGatewayStatus: QueuePauseStatus,
    observedGatewayPeer: Boolean,
    legacyAutomaticHoldReason: String?,
): Boolean = !sessionGatewayBlocked && durableGatewayStatus == QueuePauseStatus.UNPAUSED &&
    !observedGatewayPeer && legacyAutomaticHoldReason != "gateway_terminal_unconfirmed"
