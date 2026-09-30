package me.rerere.rikkahub.data.orbis.sentinel

private const val TOUCH_MAX_FORWARD_CLOCK_SKEW_MS = 5_000L

/**
 * External touch senders have a different wall clock from the phone. Tolerate only a small
 * forward skew, and never evaluate an event later than its first local receipt. Keep the
 * original sender time in ingress for identity/deduplication; do not apply this to geofences.
 * Past events retain their age so rule creation and human/rule resume baselines still apply.
 */
internal fun sentinelTouchObservedAtMs(occurredAtMs: Long, receivedAtMs: Long): Long? {
    if (occurredAtMs <= 0 || receivedAtMs <= 0) return null
    // Both values are positive, so this ordered subtraction cannot overflow.
    if (occurredAtMs > receivedAtMs && occurredAtMs - receivedAtMs > TOUCH_MAX_FORWARD_CLOCK_SKEW_MS) return null
    return minOf(occurredAtMs, receivedAtMs)
}
