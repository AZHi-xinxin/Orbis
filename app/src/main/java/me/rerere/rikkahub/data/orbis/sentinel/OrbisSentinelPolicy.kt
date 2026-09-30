package me.rerere.rikkahub.data.orbis.sentinel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val SENTINEL_LOW_BATTERY_PERCENT = 20
val SENTINEL_PROBABILITIES = setOf(10, 30, 50, 70, 90, 100)
val SENTINEL_SYSTEM_TYPES = setOf(OrbisSentinelType.SCREEN_OBSERVATION, OrbisSentinelType.NIGHT_USAGE,
    OrbisSentinelType.SCREEN_ON, OrbisSentinelType.LOW_BATTERY, OrbisSentinelType.GEOFENCE)
val SENTINEL_PERIODIC_TYPES = setOf(OrbisSentinelType.INTERVAL, OrbisSentinelType.RITUAL,
    OrbisSentinelType.AGREEMENT, OrbisSentinelType.SCREEN_OBSERVATION, OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH)

fun sentinelZone(timezone: String?): ZoneId = timezone?.let(ZoneId::of) ?: ZoneId.systemDefault()

/** 24:00 is permitted only as an end boundary, never as a start/fixed daily time. */
fun sentinelMinute(value: String, allowEndOfDay: Boolean = false): Int {
    require(Regex("[0-9]{2}:[0-9]{2}").matches(value)) { "invalid_sentinel_local_time" }
    if (allowEndOfDay && value == "24:00") return 1440
    val hour = value.substring(0, 2).toInt(); val minute = value.substring(3).toInt()
    require(hour in 0..23 && minute in 0..59) { "invalid_sentinel_local_time" }
    return hour * 60 + minute
}

/** Validate only the v27 configuration; legacy shape validation remains in the store. */
fun validateNativeConfiguration(rule: OrbisSentinelRule) {
    rule.timezone?.let { require(it.isNotBlank() && runCatching { ZoneId.of(it) }.isSuccess) { "invalid_sentinel_timezone" } }
    require(rule.probabilityPercent in SENTINEL_PROBABILITIES) { "invalid_sentinel_probability" }
    fun window(start: String?, end: String?, required: Boolean = false) {
        require((start == null) == (end == null) && (!required || start != null)) { "invalid_sentinel_time_window" }
        if (start != null && end != null) require(sentinelMinute(start) != sentinelMinute(end, true)) { "invalid_sentinel_time_window" }
    }
    window(rule.quietStartLocal, rule.quietEndLocal)
    window(rule.windowStartLocal, rule.windowEndLocal, rule.type == OrbisSentinelType.NIGHT_USAGE)
    if (rule.type in SENTINEL_SYSTEM_TYPES) {
        require(rule.prompt.isEmpty()) { "sentinel_system_prompt_not_customizable" }
        require(rule.action == OrbisSentinelAction.WAKE) { "sentinel_system_action_not_customizable" }
    }
    if (rule.type == OrbisSentinelType.RITUAL) {
        require(rule.dailyAtLocal != null) { "invalid_sentinel_ritual" }
        val start = sentinelMinute(rule.dailyAtLocal)
        rule.dailyWindowEndLocal?.let { require(sentinelMinute(it, true) != start) { "invalid_sentinel_time_window" } }
    } else require(rule.dailyAtLocal == null && rule.dailyWindowEndLocal == null) { "sentinel_daily_fields_not_applicable" }
    require(rule.type == OrbisSentinelType.AGREEMENT || rule.quietStartLocal == null) { "sentinel_quiet_fields_not_applicable" }
    require(rule.type == OrbisSentinelType.NIGHT_USAGE || rule.windowStartLocal == null) { "sentinel_window_fields_not_applicable" }
    require(rule.type in setOf(OrbisSentinelType.AGREEMENT, OrbisSentinelType.LOW_BATTERY) || rule.probabilityPercent == 100) { "sentinel_probability_not_applicable" }
    when (rule.type) {
        OrbisSentinelType.AGREEMENT -> require(rule.thresholdMs != null && rule.thresholdMs in 600_000L..86_400_000L) { "invalid_sentinel_agreement_interval" }
        OrbisSentinelType.SCREEN_OBSERVATION -> require(rule.intervalMs != null && rule.intervalMs in 1_800_000L..10_800_000L && rule.intervalMs % 1_800_000L == 0L) { "invalid_sentinel_observation_interval" }
        else -> Unit
    }
    require((rule.escalationAfter == null) == (rule.escalationPrompt == null)) { "invalid_sentinel_escalation" }
    if (rule.escalationAfter != null) require(rule.type == OrbisSentinelType.AGREEMENT && rule.escalationAfter >= 1 &&
        rule.escalationPrompt!!.isNotBlank() && rule.escalationPrompt.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "invalid_sentinel_escalation" }
}

internal data class SentinelDailySlot(val date: String, val startMs: Long, val endMs: Long)

private fun localEpoch(date: LocalDate, minute: Int, zone: ZoneId): Long =
    date.plusDays((minute / 1440).toLong()).atStartOfDay().plusMinutes((minute % 1440).toLong()).atZone(zone).toInstant().toEpochMilli()

internal fun sentinelDailySlot(nowMs: Long, startText: String, endText: String?, timezone: String?): SentinelDailySlot {
    val zone = sentinelZone(timezone)
    val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val start = sentinelMinute(startText)
    val end = endText?.let { sentinelMinute(it, true).let { minute -> if (minute <= start) minute + 1440 else minute } } ?: 1440
    fun on(day: LocalDate) = SentinelDailySlot(day.toString(), localEpoch(day, start, zone), localEpoch(day, end, zone))
    val today = on(date)
    val yesterday = on(date.minusDays(1))
    return if (endText != null && nowMs < today.startMs && nowMs >= yesterday.startMs && nowMs < yesterday.endMs) yesterday else today
}

fun sentinelInsideLocalWindow(nowMs: Long, start: String?, end: String?, timezone: String?): Boolean {
    if (start == null || end == null) return false
    val slot = sentinelDailySlot(nowMs, start, end, timezone)
    return nowMs >= slot.startMs && nowMs < slot.endMs
}

internal fun sentinelWindowStartAt(nowMs: Long, start: String, end: String, timezone: String?): Long =
    sentinelDailySlot(nowMs, start, end, timezone).startMs

internal fun ritualCondition(rule: OrbisSentinelRule, facts: OrbisSentinelObservation, baseline: Long): OrbisSentinelCondition {
    val slot = sentinelDailySlot(facts.nowMs, checkNotNull(rule.dailyAtLocal), rule.dailyWindowEndLocal, rule.timezone)
    if (rule.policy.lastOccurrenceKey == slot.date) return OrbisSentinelCondition.WAITING
    val due = if (rule.dailyWindowEndLocal == null) slot.startMs else
        rule.policy.dailyScheduledAtMs?.takeIf { rule.policy.dailyDate == slot.date } ?: return OrbisSentinelCondition.WAITING
    return if (due >= baseline && facts.nowMs >= due && facts.nowMs < slot.endMs) OrbisSentinelCondition.DUE else OrbisSentinelCondition.WAITING
}

/** UI/read-only hint only; conditions and master generation are always rechecked before firing. */
fun sentinelNextDueAtMs(rule: OrbisSentinelRule, resumedAtMs: Long? = null): Long? {
    if (!rule.enabled || rule.pendingEventId != null) return null
    val baseline = maxOf(rule.scheduleSinceMs, resumedAtMs ?: 0)
    return when (rule.type) {
        OrbisSentinelType.ONCE -> rule.dueAtMs?.takeIf { it >= baseline }
        OrbisSentinelType.RITUAL -> rule.policy.dailyScheduledAtMs?.takeIf {
            it >= baseline && rule.policy.lastOccurrenceKey != rule.policy.dailyDate
        }
        OrbisSentinelType.INTERVAL, OrbisSentinelType.SCREEN_OBSERVATION -> maxOf(baseline, rule.lastFiredAtMs ?: 0,
            rule.policy.lastEvaluatedAtMs ?: 0) + checkNotNull(rule.intervalMs)
        else -> null
    }
}

/** Human-authored system facts and AI-authored prompt are never rewritten into one another. */
fun sentinelWakeText(rule: OrbisSentinelRule, systemFacts: String? = null,
    emittedAtMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val ordinal = rule.policy.pendingOrdinal ?: (rule.policy.consecutiveCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val label = when (rule.type) {
        OrbisSentinelType.RITUAL -> rule.name.ifBlank { "仪式" }.removeSuffix("唤醒")
        OrbisSentinelType.AGREEMENT -> "第${ordinal}次约定"
        OrbisSentinelType.SCREEN_OBSERVATION -> "屏幕观察"
        OrbisSentinelType.NIGHT_USAGE -> "夜间"
        OrbisSentinelType.SCREEN_ON -> "亮屏提醒"
        OrbisSentinelType.LOW_BATTERY -> "电量提醒"
        OrbisSentinelType.GEOFENCE -> "位置播报"
        OrbisSentinelType.TOUCH -> "触摸"
        OrbisSentinelType.ONCE -> "自我"
        else -> rule.name.ifBlank { "自定义" }.removeSuffix("唤醒")
    }
    val body = if (rule.type in SENTINEL_SYSTEM_TYPES) {
        require(!systemFacts.isNullOrBlank()) { "sentinel_system_facts_required" }; systemFacts
    } else rule.prompt + if (rule.type == OrbisSentinelType.AGREEMENT && rule.escalationAfter != null && ordinal > rule.escalationAfter)
        "\n" + checkNotNull(rule.escalationPrompt) else ""
    val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX '['VV']'")
        .format(Instant.ofEpochMilli(emittedAtMs).atZone(zone))
    return "【${label}唤醒】$body\n[系统时间：$timestamp]"
}
