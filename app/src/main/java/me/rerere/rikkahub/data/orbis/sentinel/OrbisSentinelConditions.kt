package me.rerere.rikkahub.data.orbis.sentinel

/** Observed facts, not conclusions invented by the scheduler. Null means unavailable. */
data class OrbisSentinelObservation(
    val nowMs: Long,
    val lastHumanMessageMs: Long? = null,
    val chatLeftAtMs: Long? = null,
    val appPackage: String? = null,
    val appContinuousMs: Long? = null,
    val lastConversationActivityMs: Long? = null,
    val screenOnMs: Long? = null,
    val nonChatUsageMs: Long? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val eventKey: String? = null,
    val eventObservedAtMs: Long? = null,
)

enum class OrbisSentinelCondition { DUE, WAITING, RESET, UNKNOWN }

fun sentinelCondition(rule: OrbisSentinelRule, facts: OrbisSentinelObservation, resumedAtMs: Long? = null): OrbisSentinelCondition {
    val now = facts.nowMs
    val baseline = maxOf(rule.scheduleSinceMs, resumedAtMs ?: 0L)
    fun elapsed(since: Long?, threshold: Long): OrbisSentinelCondition = when {
        since == null -> OrbisSentinelCondition.UNKNOWN
        now < since -> OrbisSentinelCondition.UNKNOWN
        now - maxOf(since, baseline) >= threshold -> OrbisSentinelCondition.DUE
        else -> OrbisSentinelCondition.WAITING
    }
    return when (rule.type) {
        OrbisSentinelType.ONCE -> when {
            rule.dueAtMs == null -> OrbisSentinelCondition.UNKNOWN
            rule.dueAtMs < baseline -> OrbisSentinelCondition.RESET
            now >= rule.dueAtMs -> OrbisSentinelCondition.DUE
            else -> OrbisSentinelCondition.WAITING
        }
        OrbisSentinelType.INTERVAL -> elapsed(maxOf(rule.lastFiredAtMs ?: baseline, baseline), checkNotNull(rule.intervalMs))
        OrbisSentinelType.CHAT_IDLE -> elapsed(facts.lastHumanMessageMs, checkNotNull(rule.thresholdMs))
        OrbisSentinelType.CHAT_LEFT -> if (facts.chatLeftAtMs == null) OrbisSentinelCondition.RESET
            else elapsed(facts.chatLeftAtMs, checkNotNull(rule.thresholdMs))
        OrbisSentinelType.APP_USAGE -> when {
            facts.appPackage == null || facts.appContinuousMs == null -> OrbisSentinelCondition.UNKNOWN
            facts.appPackage != rule.appPackage -> OrbisSentinelCondition.RESET
            minOf(facts.appContinuousMs, (now - baseline).coerceAtLeast(0)) >= checkNotNull(rule.thresholdMs) -> OrbisSentinelCondition.DUE
            else -> OrbisSentinelCondition.WAITING
        }
        OrbisSentinelType.RITUAL -> ritualCondition(rule, facts, baseline)
        OrbisSentinelType.AGREEMENT -> when {
            sentinelInsideLocalWindow(now, rule.quietStartLocal, rule.quietEndLocal, rule.timezone) -> OrbisSentinelCondition.WAITING
            facts.lastConversationActivityMs == null -> OrbisSentinelCondition.UNKNOWN
            else -> elapsed(maxOf(facts.lastConversationActivityMs, rule.policy.lastEvaluatedAtMs ?: baseline), checkNotNull(rule.thresholdMs))
        }
        OrbisSentinelType.SCREEN_OBSERVATION -> elapsed(maxOf(rule.lastFiredAtMs ?: baseline,
            rule.policy.lastEvaluatedAtMs ?: baseline, baseline), checkNotNull(rule.intervalMs))
        OrbisSentinelType.NIGHT_USAGE -> when {
            !sentinelInsideLocalWindow(now, rule.windowStartLocal, rule.windowEndLocal, rule.timezone) -> OrbisSentinelCondition.RESET
            facts.nonChatUsageMs == null -> OrbisSentinelCondition.UNKNOWN
            facts.nonChatUsageMs == 0L -> OrbisSentinelCondition.RESET
            minOf(facts.nonChatUsageMs, (now - maxOf(baseline,
                sentinelWindowStartAt(now, checkNotNull(rule.windowStartLocal), checkNotNull(rule.windowEndLocal), rule.timezone))).coerceAtLeast(0)) >= checkNotNull(rule.thresholdMs) -> OrbisSentinelCondition.DUE
            else -> OrbisSentinelCondition.WAITING
        }
        OrbisSentinelType.SCREEN_ON -> when {
            facts.screenOnMs == null -> OrbisSentinelCondition.UNKNOWN
            facts.screenOnMs == 0L -> OrbisSentinelCondition.RESET
            minOf(facts.screenOnMs, (now - baseline).coerceAtLeast(0)) >= checkNotNull(rule.thresholdMs) -> OrbisSentinelCondition.DUE
            else -> OrbisSentinelCondition.WAITING
        }
        OrbisSentinelType.LOW_BATTERY -> when {
            facts.batteryPercent == null || facts.batteryPercent !in 0..100 || facts.isCharging == null -> OrbisSentinelCondition.UNKNOWN
            facts.isCharging || facts.batteryPercent > SENTINEL_LOW_BATTERY_PERCENT -> OrbisSentinelCondition.RESET
            else -> OrbisSentinelCondition.DUE
        }
        OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH -> when {
            facts.eventKey.isNullOrBlank() || facts.eventObservedAtMs == null -> OrbisSentinelCondition.UNKNOWN
            facts.eventObservedAtMs > now -> OrbisSentinelCondition.UNKNOWN
            facts.eventObservedAtMs < baseline || facts.eventKey == rule.policy.lastOccurrenceKey -> OrbisSentinelCondition.WAITING
            else -> OrbisSentinelCondition.DUE
        }
    }
}
