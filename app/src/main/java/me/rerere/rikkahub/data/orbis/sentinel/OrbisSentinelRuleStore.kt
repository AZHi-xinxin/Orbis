package me.rerere.rikkahub.data.orbis.sentinel

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure rule/configuration and outbox ledger. No timers, device access, network, or model calls.
 * [write] must atomically replace durable storage; success is checked by reading it back before
 * publication. All instances sharing storage must share [lock] (the default is process-global).
 * Every operation rereads storage, including after a write threw after committing.
 * The caller decides whether a trigger has been observed; this store enforces enablement,
 * cooldown, rearming, and one pending identity. It cannot guarantee downstream exactly-once
 * execution: delivery must use the event inbox's source/event-ID deduplication.
 */
class OrbisSentinelRuleStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val lock: Any = LOCK,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var seenPersisted = false
    private val mutable = MutableStateFlow(synchronized(lock) { load() })
    val state = mutable.asStateFlow()

    fun refresh(): OrbisSentinelState = synchronized(lock) { current() }

    fun get(id: String): OrbisSentinelRule? = synchronized(lock) {
        validateId(id)
        current().rules.firstOrNull { it.id == id }
    }

    fun list(binding: OrbisSentinelBinding? = null, enabledOnly: Boolean = false): List<OrbisSentinelRule> = synchronized(lock) {
        binding?.let(::validateBinding)
        current().rules.filter { (binding == null || it.binding == binding) && (!enabledOnly || it.enabled) }
    }

    fun executions(binding: OrbisSentinelBinding? = null, ruleId: String? = null): List<OrbisSentinelExecution> = synchronized(lock) {
        binding?.let(::validateBinding)
        ruleId?.let(::validateId)
        current().executions.filter { (binding == null || it.binding == binding) && (ruleId == null || it.ruleId == ruleId) }
    }

    /** Human settings UI only; never expose this through the AI rule-tool dispatcher. */
    fun setHumanEnabled(enabled: Boolean, now: Long): OrbisSentinelState = synchronized(lock) {
        validateTime(now)
        val before = current()
        if (before.enabled == enabled) return@synchronized before
        check(before.masterGeneration < Long.MAX_VALUE) { "sentinel_master_generation_exhausted" }
        val pending = if (enabled) emptySet() else before.rules.mapNotNull { it.pendingEventId }.toSet()
        commitIfChanged(before, before.copy(enabled = enabled,
            masterGeneration = before.masterGeneration + 1,
            masterChangedAtMs = now, resumedAtMs = if (enabled) now else before.resumedAtMs,
            rules = before.rules.map { rule ->
                if (rule.pendingEventId in pending) rule.copy(pendingBlocked = true, lastError = "human_disabled") else rule
            },
            executions = before.executions.map { execution ->
                if (execution.eventId in pending) execution.copy(status = OrbisSentinelExecutionStatus.BLOCKED,
                    updatedAtMs = now, detail = "human_disabled") else execution
            },
        ))
    }

    fun create(rule: OrbisSentinelRule): OrbisSentinelRule = synchronized(lock) {
        validateRule(rule)
        require(rule.pendingEventId == null && rule.lastFiredAtMs == null && rule.armed && rule.lastError == null) {
            "sentinel_new_rule_has_runtime_state"
        }
        require(rule.policy == OrbisSentinelPolicyState()) { "sentinel_new_rule_has_runtime_state" }
        val before = current()
        require(before.rules.none { it.id == rule.id }) { "sentinel_rule_already_exists" }
        commit(before.copy(rules = before.rules + rule))
        rule
    }

    /** Binding and runtime fields cannot be changed by a configuration edit. */
    fun update(
        id: String,
        expected: OrbisSentinelBinding,
        now: Long,
        transform: (OrbisSentinelRule) -> OrbisSentinelRule,
    ): OrbisSentinelRule = synchronized(lock) {
        validateTime(now)
        val before = current()
        val old = owned(before, id, expected)
        require(old.pendingEventId == null) { "sentinel_event_pending" }
        val candidate = transform(old)
        require(candidate.id == old.id && candidate.binding == old.binding && candidate.createdAtMs == old.createdAtMs) {
            "sentinel_identity_changed"
        }
        require(candidate.armed == old.armed && candidate.lastFiredAtMs == old.lastFiredAtMs &&
            candidate.scheduleSinceMs == old.scheduleSinceMs &&
            candidate.pendingEventId == old.pendingEventId && candidate.pendingSinceMs == old.pendingSinceMs &&
            candidate.pendingMasterGeneration == old.pendingMasterGeneration &&
            candidate.pendingBlocked == old.pendingBlocked &&
            candidate.pendingText == old.pendingText && candidate.lastError == old.lastError && candidate.policy == old.policy) {
            "sentinel_runtime_state_changed"
        }
        if (candidate == old) return@synchronized old
        if (candidate.enabled && candidate.type == OrbisSentinelType.ONCE && old.lastFiredAtMs != null) {
            require(candidate.dueAtMs != null && candidate.dueAtMs > old.lastFiredAtMs) {
                "sentinel_once_requires_new_due_time"
            }
        }
        val updated = candidate.copy(updatedAtMs = now, scheduleSinceMs = now, armed = true, lastError = null,
            policy = OrbisSentinelPolicyState(lastHumanMessageMs = old.policy.lastHumanMessageMs))
        validateRule(updated)
        replace(before, updated)
        updated
    }

    fun pause(id: String, expected: OrbisSentinelBinding, now: Long): OrbisSentinelRule =
        setRuleEnabled(id, expected, now, false)

    /** Resuming does not cancel/reassign pending work or clear the last accepted occurrence. */
    fun resume(id: String, expected: OrbisSentinelBinding, now: Long): OrbisSentinelRule =
        setRuleEnabled(id, expected, now, true)

    private fun setRuleEnabled(id: String, expected: OrbisSentinelBinding, now: Long, enabled: Boolean): OrbisSentinelRule = synchronized(lock) {
        validateTime(now)
        val before = current()
        val old = owned(before, id, expected)
        if (enabled && old.type == OrbisSentinelType.ONCE && old.lastFiredAtMs != null) {
            require(checkNotNull(old.dueAtMs) > old.lastFiredAtMs) { "sentinel_once_requires_new_due_time" }
        }
        if (old.enabled == enabled) return@synchronized old
        val updated = old.copy(enabled = enabled, updatedAtMs = now, scheduleSinceMs = now,
            armed = if (enabled && old.pendingEventId == null) true else old.armed,
            pendingBlocked = old.pendingBlocked || (!enabled && old.pendingEventId != null),
            lastError = if (!enabled && old.pendingEventId != null) "rule_paused" else old.lastError)
        val executions = if (!enabled && old.pendingEventId != null) before.executions.map {
            if (it.eventId == old.pendingEventId) it.copy(status = OrbisSentinelExecutionStatus.BLOCKED,
                updatedAtMs = now, detail = "rule_paused") else it
        } else before.executions
        replace(before.copy(executions = executions), updated)
        updated
    }

    /** Removes this rule only. Already accepted event-inbox receipts are not deleted. */
    fun delete(id: String, expected: OrbisSentinelBinding): OrbisSentinelRule? = synchronized(lock) {
        validateId(id)
        validateBinding(expected)
        val before = current()
        val old = before.rules.firstOrNull { it.id == id } ?: return@synchronized null
        require(old.binding == expected) { "sentinel_target_mismatch" }
        commit(before.copy(rules = before.rules.filterNot { it.id == id }, executions = before.executions.map {
            if (it.eventId == old.pendingEventId) it.copy(status = OrbisSentinelExecutionStatus.CANCELLED, detail = "rule_deleted") else it
        }))
        old
    }

    /**
     * Call only after evaluating the trigger. A pending reservation is returned unchanged even if
     * the caller suggests a new ID; replay=true means recover that old outbox, not a second fire.
     * [expectedUpdatedAtMs] can reject an observation made against an older rule configuration.
     * [expectedMasterGeneration] rejects observations spanning a human stop/resume, including
     * an initial generation of zero or both switches occurring within one millisecond.
     */
    fun reserveFire(
        id: String,
        eventId: String,
        now: Long,
        expectedUpdatedAtMs: Long? = null,
        expectedMasterGeneration: Long? = null,
    ): OrbisSentinelReservation? = synchronized(lock) {
        validateTime(now)
        validateEventId(eventId)
        val before = current()
        val old = before.rules.firstOrNull { it.id == id } ?: return@synchronized null
        if (!before.enabled || !old.enabled) return@synchronized null
        if (expectedMasterGeneration != null && before.masterGeneration != expectedMasterGeneration) return@synchronized null
        if (old.pendingBlocked) return@synchronized null
        if (old.pendingEventId != null) return@synchronized OrbisSentinelReservation(old, replay = true)
        if (expectedUpdatedAtMs != null && old.updatedAtMs != expectedUpdatedAtMs) return@synchronized null
        if (!old.armed) return@synchronized null
        if (old.type == OrbisSentinelType.ONCE &&
            checkNotNull(old.dueAtMs) < maxOf(old.scheduleSinceMs, before.resumedAtMs ?: 0)) return@synchronized null
        if (old.lastFiredAtMs != null && (now < old.lastFiredAtMs || now - old.lastFiredAtMs < old.cooldownMs)) {
            return@synchronized null
        }
        reserve(before, old, eventId, now)
    }

    /** Atomic policy decision + outbox claim. Rejected lotteries are durable too; do not pre-roll. */
    fun evaluateAndReserve(
        id: String,
        eventId: String,
        facts: OrbisSentinelObservation,
        expectedUpdatedAtMs: Long? = null,
        expectedMasterGeneration: Long? = null,
        randomBounded: (Long) -> Long = { java.util.concurrent.ThreadLocalRandom.current().nextLong(it) },
    ): OrbisSentinelReservation? = synchronized(lock) {
        validateTime(facts.nowMs); validateEventId(eventId)
        val now = facts.nowMs
        val before = current()
        val old = before.rules.firstOrNull { it.id == id } ?: return@synchronized null
        if (!before.enabled || !old.enabled || old.pendingBlocked ||
            (expectedMasterGeneration != null && expectedMasterGeneration != before.masterGeneration)) return@synchronized null
        val external = old.type in setOf(OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH)
        if (old.pendingEventId != null) {
            if (external && (facts.eventKey == null || facts.eventKey != old.policy.lastOccurrenceKey)) return@synchronized null
            return@synchronized OrbisSentinelReservation(old, true)
        }
        if (expectedUpdatedAtMs != null && expectedUpdatedAtMs != old.updatedAtMs) return@synchronized null
        if (before.executions.any { it.eventId == eventId }) return@synchronized null
        fun draw(bound: Long): Long = randomBounded(bound).also { require(it in 0 until bound) { "invalid_sentinel_random_draw" } }
        var working = old
        if (old.type == OrbisSentinelType.AGREEMENT && facts.lastHumanMessageMs != null &&
            facts.lastHumanMessageMs <= now && facts.lastHumanMessageMs > (old.policy.lastHumanMessageMs ?: -1L)) {
            working = working.copy(policy = working.policy.copy(lastHumanMessageMs = facts.lastHumanMessageMs, consecutiveCount = 0))
        }
        if (working.type == OrbisSentinelType.RITUAL) {
            val slot = sentinelDailySlot(now, checkNotNull(working.dailyAtLocal), working.dailyWindowEndLocal, working.timezone)
            val zone = sentinelZone(working.timezone).id
            if (working.policy.dailyDate != slot.date || working.policy.dailyTimezone != zone) {
                val chosen = slot.startMs + if (working.dailyWindowEndLocal == null) 0 else draw(slot.endMs - slot.startMs)
                working = working.copy(policy = working.policy.copy(dailyDate = slot.date, dailyTimezone = zone, dailyScheduledAtMs = chosen))
            }
        }
        val condition = sentinelCondition(working, facts, before.resumedAtMs)
        if (condition == OrbisSentinelCondition.RESET && working.type != OrbisSentinelType.ONCE) working = working.copy(armed = true)
        fun saveIfChanged() { if (working != old) replace(before, working.copy(updatedAtMs = now)) }
        if (condition != OrbisSentinelCondition.DUE || !working.armed ||
            (working.lastFiredAtMs != null && (now < working.lastFiredAtMs!! || now - working.lastFiredAtMs!! < working.cooldownMs))) {
            saveIfChanged(); return@synchronized null
        }
        if (working.type in setOf(OrbisSentinelType.AGREEMENT, OrbisSentinelType.LOW_BATTERY)) {
            working = working.copy(policy = working.policy.copy(lastEvaluatedAtMs = now))
            if (draw(100) >= working.probabilityPercent) {
                if (working.type == OrbisSentinelType.LOW_BATTERY) working = working.copy(armed = false)
                saveIfChanged(); return@synchronized null
            }
        }
        working = when (working.type) {
            OrbisSentinelType.RITUAL -> working.copy(policy = working.policy.copy(lastOccurrenceKey = working.policy.dailyDate))
            OrbisSentinelType.AGREEMENT -> working.copy(policy = working.policy.copy(pendingOrdinal =
                (working.policy.consecutiveCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            OrbisSentinelType.SCREEN_OBSERVATION -> working.copy(policy = working.policy.copy(lastEvaluatedAtMs = now))
            OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH -> working.copy(policy = working.policy.copy(lastOccurrenceKey = facts.eventKey))
            else -> working
        }
        reserve(before, working, eventId, now)
    }

    private fun reserve(before: OrbisSentinelState, old: OrbisSentinelRule, eventId: String, now: Long): OrbisSentinelReservation {
        require(before.executions.none { it.eventId == eventId }) { "sentinel_event_id_conflict" }
        val updated = old.copy(pendingEventId = eventId, pendingSinceMs = now, pendingText = null,
            pendingMasterGeneration = before.masterGeneration,
            updatedAtMs = now, lastError = null)
        val execution = OrbisSentinelExecution(eventId, old.id, old.assistantId, old.conversationId,
            old.action, old.notificationLevel, now)
        replace(before.copy(executions = before.executions + execution), updated)
        return OrbisSentinelReservation(updated, replay = false)
    }

    /** Freeze the complete outgoing event text before invoking the durable inbox. */
    fun stageEventText(id: String, eventId: String, text: String): OrbisSentinelRule = synchronized(lock) {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "invalid_sentinel_event_text" }
        val before = current()
        val old = pending(before, id, eventId)
        require(before.enabled && old.enabled && !old.pendingBlocked) { "sentinel_event_blocked" }
        if (old.pendingText != null) {
            require(old.pendingText == text) { "sentinel_event_text_conflict" }
            return@synchronized old
        }
        val updated = old.copy(pendingText = text)
        replace(before.copy(executions = before.executions.map {
            if (it.eventId == eventId) it.copy(status = OrbisSentinelExecutionStatus.STAGED) else it
        }), updated)
        updated
    }

    /** Acknowledge only after an event inbox has durably accepted this exact event ID. */
    fun completeFire(id: String, eventId: String, now: Long): OrbisSentinelRule = synchronized(lock) {
        validateTime(now)
        val before = current()
        val existing = before.rules.firstOrNull { it.id == id }
        if (existing != null && existing.pendingEventId == null && before.executions.any {
                it.ruleId == id && it.eventId == eventId && it.status == OrbisSentinelExecutionStatus.ACCEPTED
            }) return@synchronized existing
        val old = pending(before, id, eventId)
        require(old.pendingText != null) { "sentinel_event_not_staged" }
        require(now >= checkNotNull(old.pendingSinceMs)) { "invalid_sentinel_completion_time" }
        val once = old.type == OrbisSentinelType.ONCE
        val updated = old.copy(
            enabled = old.enabled && !once,
            armed = !once && old.type != OrbisSentinelType.LOW_BATTERY &&
                (old.type in SENTINEL_PERIODIC_TYPES || old.rearm == OrbisSentinelRearm.AFTER_COOLDOWN),
            lastFiredAtMs = now, pendingEventId = null, pendingSinceMs = null, pendingText = null,
            pendingMasterGeneration = null,
            pendingBlocked = false, updatedAtMs = now, lastError = null,
            policy = old.policy.copy(consecutiveCount = if (old.type == OrbisSentinelType.AGREEMENT)
                old.policy.pendingOrdinal ?: old.policy.consecutiveCount else old.policy.consecutiveCount, pendingOrdinal = null),
        )
        replace(before.copy(executions = before.executions.map {
            if (it.eventId == eventId) it.copy(status = OrbisSentinelExecutionStatus.ACCEPTED, updatedAtMs = now, detail = null) else it
        }), updated)
        updated
    }

    /** Explicit reconciliation of an unaccepted/cancelled outbox; never an automatic replay. */
    fun abandonPending(id: String, eventId: String, now: Long, reason: String): OrbisSentinelRule = synchronized(lock) {
        validateTime(now)
        require(ERROR_CODE.matches(reason)) { "invalid_sentinel_error_code" }
        val before = current()
        val old = pending(before, id, eventId)
        val updated = old.copy(pendingEventId = null, pendingSinceMs = null, pendingText = null,
            pendingMasterGeneration = null,
            pendingBlocked = false, armed = old.type in SENTINEL_PERIODIC_TYPES && old.type != OrbisSentinelType.INTERVAL,
            updatedAtMs = now, lastError = reason,
            policy = old.policy.copy(pendingOrdinal = null))
        replace(before.copy(executions = before.executions.map {
            if (it.eventId == eventId) it.copy(status = OrbisSentinelExecutionStatus.CANCELLED,
                updatedAtMs = now, detail = reason) else it
        }), updated)
        updated
    }

    /** Record a bounded reason code without losing the identity needed to reconcile a retry. */
    fun markPendingError(id: String, eventId: String, reason: String): OrbisSentinelRule = synchronized(lock) {
        require(ERROR_CODE.matches(reason)) { "invalid_sentinel_error_code" }
        val before = current()
        val old = pending(before, id, eventId)
        val updated = old.copy(lastError = reason)
        if (updated != old) replace(before.copy(executions = before.executions.map {
            if (it.eventId == eventId) it.copy(detail = reason) else it
        }), updated)
        updated
    }

    /** Runtime calls this only after observing the monitored condition reset. */
    fun rearm(id: String, now: Long): OrbisSentinelRule? = synchronized(lock) {
        validateTime(now)
        val before = current()
        val old = before.rules.firstOrNull { it.id == id } ?: return@synchronized null
        if (old.pendingEventId != null || old.type == OrbisSentinelType.ONCE || old.armed) return@synchronized old
        val updated = old.copy(armed = true, updatedAtMs = now)
        replace(before, updated)
        updated
    }

    private fun owned(value: OrbisSentinelState, id: String, expected: OrbisSentinelBinding): OrbisSentinelRule {
        validateId(id)
        validateBinding(expected)
        return checkNotNull(value.rules.firstOrNull { it.id == id }) { "sentinel_rule_missing" }.also {
            require(it.binding == expected) { "sentinel_target_mismatch" }
        }
    }

    private fun pending(value: OrbisSentinelState, id: String, eventId: String): OrbisSentinelRule {
        validateId(id)
        validateEventId(eventId)
        return checkNotNull(value.rules.firstOrNull { it.id == id }) { "sentinel_rule_missing" }.also {
            require(it.pendingEventId == eventId) { "sentinel_pending_event_mismatch" }
        }
    }

    private fun replace(before: OrbisSentinelState, rule: OrbisSentinelRule) {
        commit(before.copy(rules = before.rules.map { if (it.id == rule.id) rule else it }))
    }

    private fun current(): OrbisSentinelState = load().also { mutable.value = it }

    private fun load(): OrbisSentinelState {
        val encoded = read() ?: run {
            check(!seenPersisted) { "sentinel_store_disappeared" }
            return OrbisSentinelState()
        }
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "sentinel_store_too_large" }
        return json.decodeFromString<OrbisSentinelState>(encoded).also {
            validateState(it)
            seenPersisted = true
        }
    }

    private fun commitIfChanged(before: OrbisSentinelState, after: OrbisSentinelState): OrbisSentinelState {
        if (after != before) commit(after)
        return after
    }

    private fun commit(value: OrbisSentinelState) {
        validateState(value)
        val encoded = json.encodeToString(value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "sentinel_store_too_large" }
        write(encoded)
        if (read() != encoded) throw IOException("sentinel_store_verify_failed")
        seenPersisted = true
        mutable.value = value
    }

    private fun validateState(value: OrbisSentinelState) {
        require(value.version == 1) { "sentinel_store_version_unsupported" }
        require(value.masterGeneration >= 0) { "invalid_sentinel_master_generation" }
        value.masterChangedAtMs?.let(::validateTime)
        value.resumedAtMs?.let(::validateTime)
        require(value.rules.map { it.id }.distinct().size == value.rules.size) { "sentinel_duplicate_rule" }
        val pendingIds = value.rules.mapNotNull { it.pendingEventId }
        require(pendingIds.distinct().size == pendingIds.size) { "sentinel_duplicate_pending_event" }
        require(value.executions.map { it.eventId }.distinct().size == value.executions.size) { "sentinel_duplicate_execution" }
        value.executions.forEach {
            validateEventId(it.eventId); validateId(it.ruleId); validateBinding(it.binding)
            validateTime(it.createdAtMs); validateTime(it.updatedAtMs)
            require(it.detail == null || ERROR_CODE.matches(it.detail)) { "invalid_sentinel_error_code" }
        }
        value.rules.forEach(::validateRule)
        value.rules.filter { it.pendingEventId != null }.forEach { rule ->
            val receipt = value.executions.singleOrNull { it.eventId == rule.pendingEventId }
            require(receipt != null && receipt.ruleId == rule.id && receipt.binding == rule.binding &&
                receipt.action == rule.action && receipt.notificationLevel == rule.notificationLevel &&
                receipt.status in setOf(OrbisSentinelExecutionStatus.PENDING, OrbisSentinelExecutionStatus.STAGED,
                    OrbisSentinelExecutionStatus.BLOCKED)) { "sentinel_pending_receipt_mismatch" }
        }
    }

    private fun validateRule(rule: OrbisSentinelRule) {
        validateId(rule.id)
        validateBinding(rule.binding)
        require(rule.name.length <= 120) { "invalid_sentinel_name" }
        require((rule.type in SENTINEL_SYSTEM_TYPES || rule.prompt.isNotBlank()) &&
            rule.prompt.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "invalid_sentinel_prompt" }
        validateNativeConfiguration(rule)
        validateTime(rule.createdAtMs)
        validateTime(rule.updatedAtMs)
        validateTime(rule.scheduleSinceMs)
        rule.lastFiredAtMs?.let(::validateTime)
        require(rule.cooldownMs in 0..MAX_TIME) { "invalid_sentinel_cooldown" }
        when (rule.type) {
            OrbisSentinelType.ONCE -> require(rule.dueAtMs != null && rule.dueAtMs in 1..MAX_TIME &&
                rule.intervalMs == null && rule.thresholdMs == null && rule.appPackage == null) { "invalid_sentinel_once" }
            OrbisSentinelType.INTERVAL -> require(rule.intervalMs != null && rule.intervalMs in 1..MAX_TIME &&
                rule.dueAtMs == null && rule.thresholdMs == null && rule.appPackage == null) { "invalid_sentinel_interval" }
            OrbisSentinelType.CHAT_IDLE, OrbisSentinelType.CHAT_LEFT -> require(
                rule.thresholdMs != null && rule.thresholdMs in 1..MAX_TIME && rule.intervalMs == null &&
                    rule.dueAtMs == null && rule.appPackage == null) { "invalid_sentinel_chat_condition" }
            OrbisSentinelType.APP_USAGE -> require(rule.thresholdMs != null && rule.thresholdMs in 1..MAX_TIME &&
                rule.intervalMs == null && rule.dueAtMs == null && rule.appPackage != null &&
                PACKAGE.matches(rule.appPackage)) { "invalid_sentinel_app_condition" }
            OrbisSentinelType.RITUAL, OrbisSentinelType.LOW_BATTERY, OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH -> require(
                rule.intervalMs == null && rule.thresholdMs == null && rule.dueAtMs == null && rule.appPackage == null) { "invalid_sentinel_native_shape" }
            OrbisSentinelType.SCREEN_OBSERVATION -> require(rule.intervalMs != null && rule.thresholdMs == null &&
                rule.dueAtMs == null && rule.appPackage == null) { "invalid_sentinel_native_shape" }
            OrbisSentinelType.AGREEMENT, OrbisSentinelType.NIGHT_USAGE, OrbisSentinelType.SCREEN_ON -> require(
                rule.thresholdMs != null && rule.thresholdMs in 1..MAX_TIME && rule.intervalMs == null &&
                    rule.dueAtMs == null && rule.appPackage == null) { "invalid_sentinel_native_shape" }
        }
        require((rule.pendingEventId == null) == (rule.pendingSinceMs == null)) { "invalid_sentinel_pending_state" }
        require(rule.pendingMasterGeneration == null || (rule.pendingEventId != null && rule.pendingMasterGeneration >= 0)) {
            "invalid_sentinel_pending_generation"
        }
        require(!rule.pendingBlocked || rule.pendingEventId != null) { "invalid_sentinel_pending_state" }
        rule.pendingEventId?.let(::validateEventId)
        rule.pendingSinceMs?.let(::validateTime)
        require(rule.pendingText == null || (rule.pendingEventId != null && rule.pendingText.isNotBlank() &&
            rule.pendingText.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES)) { "invalid_sentinel_pending_text" }
        require(rule.lastError == null || ERROR_CODE.matches(rule.lastError)) { "invalid_sentinel_error_code" }
        rule.policy.dailyScheduledAtMs?.let(::validateTime)
        rule.policy.lastEvaluatedAtMs?.let(::validateTime)
        rule.policy.lastHumanMessageMs?.let(::validateTime)
        require(rule.policy.consecutiveCount >= 0 && (rule.policy.pendingOrdinal == null ||
            (rule.policy.pendingOrdinal > 0 && rule.pendingEventId != null))) { "invalid_sentinel_policy_state" }
        require((rule.policy.dailyDate == null) == (rule.policy.dailyScheduledAtMs == null)) { "invalid_sentinel_policy_state" }
        rule.policy.dailyDate?.let { require(runCatching { java.time.LocalDate.parse(it) }.isSuccess) { "invalid_sentinel_policy_state" } }
        rule.policy.dailyTimezone?.let { require(runCatching { java.time.ZoneId.of(it) }.isSuccess) { "invalid_sentinel_policy_state" } }
        require(rule.policy.lastOccurrenceKey == null || rule.policy.lastOccurrenceKey.length <= 256) { "invalid_sentinel_policy_state" }
    }

    private fun validateBinding(binding: OrbisSentinelBinding) {
        listOf(binding.assistantId, binding.conversationId).forEach { id ->
            require(runCatching { UUID.fromString(id).toString().equals(id, ignoreCase = true) }.getOrDefault(false)) {
                "invalid_sentinel_binding"
            }
        }
    }

    private fun validateId(id: String) {
        require(ID.matches(id)) { "invalid_sentinel_rule_id" }
    }

    private fun validateEventId(id: String) {
        require(EVENT_ID.matches(id)) { "invalid_sentinel_event_id" }
    }

    private fun validateTime(value: Long) {
        require(value in 0..MAX_TIME) { "invalid_sentinel_time" }
    }

    companion object {
        val LOCK = Any()
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TIME = 253402300799999L
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        private val EVENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,179}")
        private val ERROR_CODE = Regex("[A-Za-z][A-Za-z0-9_]{0,95}")
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
