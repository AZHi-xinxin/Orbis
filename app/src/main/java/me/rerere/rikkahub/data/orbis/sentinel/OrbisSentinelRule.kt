package me.rerere.rikkahub.data.orbis.sentinel

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrbisSentinelType {
    @SerialName("once") ONCE,
    @SerialName("interval") INTERVAL,
    @SerialName("chat_idle") CHAT_IDLE,
    @SerialName("chat_left") CHAT_LEFT,
    @SerialName("app_usage") APP_USAGE,
    @SerialName("ritual") RITUAL,
    @SerialName("agreement") AGREEMENT,
    @SerialName("screen_observation") SCREEN_OBSERVATION,
    @SerialName("night_usage") NIGHT_USAGE,
    @SerialName("screen_on") SCREEN_ON,
    @SerialName("low_battery") LOW_BATTERY,
    @SerialName("geofence") GEOFENCE,
    @SerialName("touch") TOUCH,
}

@Serializable
enum class OrbisSentinelAction {
    @SerialName("wake") WAKE,
    @SerialName("device_context") DEVICE_CONTEXT,
    @SerialName("screenshot") SCREENSHOT,
}

/** Presentation preference only. The store never changes notification channels or hardware. */
@Serializable
enum class OrbisSentinelNotificationLevel {
    @SerialName("light") LIGHT,
    @SerialName("strong") STRONG,
}

@Serializable
enum class OrbisSentinelRearm {
    /** The runtime must observe the monitored condition reset before another occurrence. */
    @SerialName("after_reset") AFTER_RESET,
    /** A still-true monitored condition may fire again after cooldown. */
    @SerialName("after_cooldown") AFTER_COOLDOWN,
}

@Serializable
data class OrbisSentinelBinding(val assistantId: String, val conversationId: String)

@Serializable
data class OrbisSentinelRule(
    val id: String = UUID.randomUUID().toString(),
    val assistantId: String,
    val conversationId: String,
    val type: OrbisSentinelType,
    /** Authored text is preserved byte-for-byte, including surrounding whitespace. */
    val prompt: String = "",
    val enabled: Boolean = false,
    val name: String = "",
    val action: OrbisSentinelAction = OrbisSentinelAction.WAKE,
    val notificationLevel: OrbisSentinelNotificationLevel = OrbisSentinelNotificationLevel.LIGHT,
    val intervalMs: Long? = null,
    val thresholdMs: Long? = null,
    val dueAtMs: Long? = null,
    val appPackage: String? = null,
    val cooldownMs: Long = 0,
    val rearm: OrbisSentinelRearm = OrbisSentinelRearm.AFTER_RESET,
    val dailyAtLocal: String? = null,
    val dailyWindowEndLocal: String? = null,
    /** Null tracks the phone's current timezone; an IANA zone pins the daily clock. */
    val timezone: String? = null,
    val probabilityPercent: Int = 100,
    val quietStartLocal: String? = null,
    val quietEndLocal: String? = null,
    val windowStartLocal: String? = null,
    val windowEndLocal: String? = null,
    val escalationAfter: Int? = null,
    val escalationPrompt: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val scheduleSinceMs: Long = createdAtMs,
    val armed: Boolean = true,
    /** Means accepted by the durable event inbox, not that the AI has replied. */
    val lastFiredAtMs: Long? = null,
    /** Durable outbox identity; retry this exact identity instead of creating another event. */
    val pendingEventId: String? = null,
    val pendingSinceMs: Long? = null,
    /** Keep the reservation's generation, not the generation at eventual delivery. */
    val pendingMasterGeneration: Long? = null,
    /** Master/rule pause blocks replay even after it is enabled again; reconcile explicitly. */
    val pendingBlocked: Boolean = false,
    /** Frozen event text makes retries of device/screenshot observations idempotent. */
    val pendingText: String? = null,
    val lastError: String? = null,
    /** Scheduler-owned durable decisions. Never accepted from a configuration edit. */
    val policy: OrbisSentinelPolicyState = OrbisSentinelPolicyState(),
) {
    val binding: OrbisSentinelBinding get() = OrbisSentinelBinding(assistantId, conversationId)
}

@Serializable
data class OrbisSentinelPolicyState(
    val dailyDate: String? = null,
    val dailyTimezone: String? = null,
    val dailyScheduledAtMs: Long? = null,
    val lastOccurrenceKey: String? = null,
    val lastEvaluatedAtMs: Long? = null,
    val lastHumanMessageMs: Long? = null,
    val consecutiveCount: Int = 0,
    /** Freeze the ordinal at reservation, so retries cannot change the event label. */
    val pendingOrdinal: Int? = null,
)

@Serializable
data class OrbisSentinelState(
    val version: Int = 1,
    /** Human-only master. True preserves the existing external-event behavior on upgrade.
     * Empty rules and disabled-by-default new rules do not start any new observation. */
    val enabled: Boolean = true,
    /** Monotonic human-switch generation, including two changes in the same millisecond. */
    val masterGeneration: Long = 0,
    val masterChangedAtMs: Long? = null,
    /** Runtime must not count interval/idle/app-use time before the most recent human resume. */
    val resumedAtMs: Long? = null,
    val rules: List<OrbisSentinelRule> = emptyList(),
    val executions: List<OrbisSentinelExecution> = emptyList(),
)

data class OrbisSentinelReservation(val rule: OrbisSentinelRule, val replay: Boolean)

@Serializable
enum class OrbisSentinelExecutionStatus {
    @SerialName("pending") PENDING,
    @SerialName("staged") STAGED,
    @SerialName("blocked") BLOCKED,
    @SerialName("accepted") ACCEPTED,
    @SerialName("cancelled") CANCELLED,
}

/** Content-free local execution receipt; original accepted event text belongs to the inbox. */
@Serializable
data class OrbisSentinelExecution(
    val eventId: String,
    val ruleId: String,
    val assistantId: String,
    val conversationId: String,
    val action: OrbisSentinelAction,
    val notificationLevel: OrbisSentinelNotificationLevel,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val status: OrbisSentinelExecutionStatus = OrbisSentinelExecutionStatus.PENDING,
    val detail: String? = null,
) {
    val binding: OrbisSentinelBinding get() = OrbisSentinelBinding(assistantId, conversationId)
}
