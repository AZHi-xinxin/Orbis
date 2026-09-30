package me.rerere.rikkahub.data.orbis.sentinel.lc

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class LcAlertDeliveryState { RESERVED, ACCEPTED, DUPLICATE, UNKNOWN, REJECTED }

enum class LcAlertDecisionKind { RESERVED, DUPLICATE, SUPPRESSED, REJECTED }

@Serializable
data class LcAlertReservation(
    val eventId: String,
    val rawEventId: String,
    val ruleId: String,
    val targetKey: String,
    val payloadHash: String,
    val text: String,
    val occurredAtMs: Long,
    val reservedAtMs: Long,
    val state: LcAlertDeliveryState = LcAlertDeliveryState.RESERVED,
    val updatedAtMs: Long = reservedAtMs,
) {
    val source: String get() = "lc_sentinel"
}

data class LcAlertDecision(
    val kind: LcAlertDecisionKind,
    val code: String,
    val reservation: LcAlertReservation? = null,
)

/** Milliseconds throughout. The legacy JSON's seconds must be converted by its import adapter. */
@Serializable
data class LcAlertThrottleState(
    val seen: Map<String, Long> = emptyMap(),
    val cooldowns: Map<String, Long> = emptyMap(),
    val hourly: List<Long> = emptyList(),
)

@Serializable
data class LcAlertDecisionState(
    val version: Int = 1,
    val reservations: List<LcAlertReservation> = emptyList(),
    val throttles: Map<String, LcAlertThrottleState> = emptyMap(),
)

/**
 * Durable local rule decisions, never a scheduler or sender. [write] must atomically replace the
 * file; its result is read back before returning a dispatchable RESERVED decision. Every operation
 * rereads storage, including after a writer committed and then threw. All instances sharing storage
 * must share [lock]; the default serializes every instance in this process.
 *
 * ONLY a newly returned RESERVED decision authorizes a first dispatch. DUPLICATE, including a
 * crash-left RESERVED or UNKNOWN record, is receipt-only: never create another ID, replay a model
 * request, or fall back to VPS. A caller may reconcile the existing downstream receipt via [mark].
 * Throttle entries expire per configuration; delivery identities are retained so expiry cannot
 * turn an old attempted event into new model work. No payloads, prompts or credentials are logged.
 */
class LcAlertDecisionStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val lock: Any = LOCK,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var hadStorage = false

    init { synchronized(lock) { load() } }

    fun snapshot(): LcAlertDecisionState = synchronized(lock) { load() }
    fun receipt(eventId: String): LcAlertReservation? = synchronized(lock) {
        load().reservations.firstOrNull { it.eventId == eventId }
    }

    fun reserve(
        payloadJson: String,
        ruleId: String,
        targetKey: String,
        config: LcAlertPolicyConfig,
        nowMs: Long,
    ): LcAlertDecision = synchronized(lock) {
        validateScope(ruleId, targetKey)
        validateLcPolicyConfig(config)
        require(nowMs >= 0) { "lc_invalid_clock" }
        if (!config.enabled) return@synchronized decision(LcAlertDecisionKind.SUPPRESSED, "disabled")
        val validated = validateLcAlert(payloadJson, config, nowMs)
        val event = validated.event ?: return@synchronized decision(LcAlertDecisionKind.REJECTED, checkNotNull(validated.error))
        val before = load()
        val eventId = lcAlertStableEventId(ruleId, event.eventId, config.legacyIdentity)
        val hash = lcAlertPayloadHash(event)
        before.reservations.firstOrNull { it.eventId == eventId }?.let { existing ->
            if (existing.targetKey != targetKey || existing.payloadHash != hash) {
                return@synchronized decision(LcAlertDecisionKind.REJECTED, "event_identity_conflict")
            }
            return@synchronized LcAlertDecision(LcAlertDecisionKind.DUPLICATE, "already_reserved", existing)
        }
        lcAlertConditionMismatch(event, config)?.let {
            return@synchronized decision(LcAlertDecisionKind.SUPPRESSED, it)
        }
        val text = lcAlertText(event, config)
            ?: return@synchronized decision(LcAlertDecisionKind.REJECTED, "prompt_not_configured")
        if (text.isBlank() || text.toByteArray().size > 64 * 1024) {
            return@synchronized decision(LcAlertDecisionKind.REJECTED, "invalid_formatted_text")
        }
        val scope = throttleScope(ruleId, targetKey, config)
        val previous = before.throttles[scope] ?: LcAlertThrottleState()
        val throttle = previous.copy(
            seen = previous.seen.filterValues { withinWindow(it, nowMs, config.dedupMs) },
            hourly = previous.hourly.filter { withinWindow(it, nowMs, HOUR_MS) },
        )
        if (event.eventId in throttle.seen) return@synchronized decision(LcAlertDecisionKind.DUPLICATE, "seen_before_migration")
        val last = throttle.cooldowns[event.cooldownKey]
        if (event.type != "manual_test" && last != null && withinWindow(last, nowMs, config.cooldownMs)) {
            return@synchronized decision(LcAlertDecisionKind.SUPPRESSED, "cooldown")
        }
        if (config.maxPerHour > 0 && throttle.hourly.size >= config.maxPerHour) {
            return@synchronized decision(LcAlertDecisionKind.SUPPRESSED, "rate_limited")
        }
        val reserved = LcAlertReservation(eventId, event.eventId, ruleId, targetKey, hash, text, event.occurredAtMs, nowMs)
        val updated = throttle.copy(
            seen = throttle.seen + (event.eventId to nowMs),
            cooldowns = throttle.cooldowns + (event.cooldownKey to nowMs),
            hourly = throttle.hourly + nowMs,
        )
        commit(before.copy(reservations = before.reservations + reserved, throttles = before.throttles + (scope to updated)))
        LcAlertDecision(LcAlertDecisionKind.RESERVED, "reserved", reserved)
    }

    /** A matching durable downstream acknowledgement may upgrade UNKNOWN, never vice versa. */
    fun mark(
        reservation: LcAlertReservation,
        state: LcAlertDeliveryState,
        nowMs: Long,
    ): LcAlertReservation = synchronized(lock) {
        require(state != LcAlertDeliveryState.RESERVED && nowMs >= 0) { "lc_invalid_delivery_transition" }
        val before = load()
        val existing = before.reservations.firstOrNull { it.eventId == reservation.eventId }
            ?: error("lc_reservation_missing")
        require(existing.copy(state = reservation.state, updatedAtMs = reservation.updatedAtMs) == reservation) {
            "lc_reservation_identity_changed"
        }
        if (existing.state in setOf(LcAlertDeliveryState.ACCEPTED, LcAlertDeliveryState.DUPLICATE) || existing.state == state) {
            return@synchronized existing
        }
        val updated = existing.copy(state = state, updatedAtMs = maxOf(nowMs, existing.updatedAtMs))
        commit(before.copy(reservations = before.reservations.map { if (it.eventId == updated.eventId) updated else it }))
        updated
    }

    /** Seed an untouched migration scope; never overwrite live post-cutover counters. */
    fun importThrottle(
        ruleId: String,
        targetKey: String,
        config: LcAlertPolicyConfig,
        imported: LcAlertThrottleState,
    ) = synchronized(lock) {
        validateScope(ruleId, targetKey)
        validateLcPolicyConfig(config)
        validateThrottle(imported)
        val before = load()
        val scope = throttleScope(ruleId, targetKey, config)
        val existing = before.throttles[scope]
        if (existing == imported) return@synchronized
        require(existing == null) { "lc_throttle_already_initialized" }
        commit(before.copy(throttles = before.throttles + (scope to imported)))
    }

    private fun load(): LcAlertDecisionState {
        val raw = read()
        if (raw == null) {
            check(!hadStorage) { "lc_store_disappeared" }
            return LcAlertDecisionState()
        }
        val value = try { json.decodeFromString<LcAlertDecisionState>(raw) }
        catch (_: Exception) { throw IOException("lc_store_unreadable") }
        validateState(value)
        hadStorage = true
        return value
    }

    private fun commit(value: LcAlertDecisionState) {
        validateState(value)
        val encoded = json.encodeToString(value)
        write(encoded)
        if (read() != encoded) throw IOException("lc_store_write_not_verified")
        hadStorage = true
    }

    private fun validateState(value: LcAlertDecisionState) {
        require(value.version == 1) { "lc_store_version_unsupported" }
        require(value.reservations.map { it.eventId }.distinct().size == value.reservations.size) { "lc_store_duplicate_identity" }
        value.reservations.forEach {
            validateScope(it.ruleId, it.targetKey)
            require(it.eventId.matches(Regex("lc:[0-9a-f]{64}")) && it.payloadHash.matches(Regex("[0-9a-f]{64}")) &&
                it.text.isNotBlank() && it.occurredAtMs >= 0 && it.reservedAtMs >= 0 && it.updatedAtMs >= it.reservedAtMs) {
                "lc_store_invalid_reservation"
            }
        }
        value.throttles.values.forEach(::validateThrottle)
    }

    private fun validateThrottle(value: LcAlertThrottleState) {
        require(value.seen.values.all { it >= 0 } && value.cooldowns.values.all { it >= 0 } && value.hourly.all { it >= 0 }) {
            "lc_invalid_imported_throttle"
        }
    }

    private fun validateScope(ruleId: String, targetKey: String) {
        require(ruleId.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "lc_invalid_rule_id" }
        require(targetKey.isNotBlank() && targetKey.length <= 256 && targetKey.none { it.isISOControl() }) { "lc_invalid_target" }
    }

    private fun throttleScope(ruleId: String, targetKey: String, config: LcAlertPolicyConfig): String =
        lcAlertSha256("$targetKey\n" + (config.throttleGroup?.let { "group:$it" } ?: "rule:$ruleId"))

    private fun decision(kind: LcAlertDecisionKind, code: String) = LcAlertDecision(kind, code)
    private fun withinWindow(timestamp: Long, now: Long, width: Long): Boolean =
        width > 0 && (timestamp > now || now - timestamp < width)

    private companion object {
        val LOCK = Any()
        const val HOUR_MS = 3_600_000L
    }
}
