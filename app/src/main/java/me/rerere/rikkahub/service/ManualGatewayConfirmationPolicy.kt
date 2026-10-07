package me.rerere.rikkahub.service

/** Exact, RAM-only receipts from the user's stop/check action. Never a resume or tool receipt. */
internal class ManualGatewayConfirmation<T : Any>(
    val unconfirmed: List<T>,
    val settled: Boolean,
) {
    override fun toString(): String =
        "ManualGatewayConfirmation(unconfirmedCount=${unconfirmed.size}, settled=$settled)"
}

/**
 * Manual status/stop uses an exact request even if its original response did not advertise the
 * automatic-finish protocol. Pass only requests that actually returned NOT_CURRENT or RETIRED,
 * not a summary count or an unsupported/pending response. Match by object identity: copied IDs,
 * a newer owner, changed settings or an empty lost owner cannot manufacture a receipt.
 *
 * The caller revalidates owner/session/settings and local-job exclusion before applying this
 * result. Durable pause release, unknown-tool handling and dispatch still belong to recovery.
 */
internal fun <T : Any> reconcileManualGatewayConfirmations(
    ownedRequests: List<T>,
    unconfirmed: List<T>,
    confirmedRequests: List<T>,
    ownerMatches: Boolean,
): ManualGatewayConfirmation<T> {
    if (!ownerMatches || ownedRequests.isEmpty() || unconfirmed.isEmpty() ||
        unconfirmed.any { request -> ownedRequests.none { it === request } }) {
        return ManualGatewayConfirmation(unconfirmed.toList(), settled = false)
    }
    val remaining = unconfirmed.filter { request -> confirmedRequests.none { it === request } }
    return ManualGatewayConfirmation(remaining, settled = remaining.isEmpty())
}
