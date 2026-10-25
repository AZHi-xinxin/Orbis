package me.rerere.rikkahub.data.orbis.body

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import java.security.MessageDigest

/** One atomic owner document: state, rule versions, receipts and audit commit together or not at all. */
class BodyRepository(private val storage: ActivityStorage, private val now: () -> BodyClock) {
    private val mutable = MutableStateFlow(storage.read()?.let { BodyRules.json.decodeFromString<BodyState>(it).also(BodyRules::validate) } ?: BodyState())
    val state = mutable.asStateFlow()
    init {
        // Start the new clock at upgrade/open, never retroactively reinterpret a legacy gap.
        // Persist once so closing/reopening before another message still keeps this origin.
        val old = mutable.value
        if (old.running && old.timeline == null) {
            val clock = now()
            commit(old.copy(clock = clock, timeline = BodyTime.anchor(old, clock.wallMs),
                undo = old.undo?.copy(clock = clock, timeline = BodyTime.anchor(old, clock.wallMs))))
        }
    }
    @Synchronized fun snapshot(): BodyState = mutable.value
    @Synchronized fun viewSnapshot(): BodyState = settle(snapshot(), now())
    private fun settle(old: BodyState, clock: BodyClock) = BodyTime.settle(old, clock)
    private fun commit(next: BodyState): BodyState {
        BodyRules.validate(next)
        val text = BodyRules.json.encodeToString(next)
        require(text.toByteArray().size <= BodyRules.MAX_BYTES) { "body_storage_limit" }
        storage.write(text) // Publish to observers only AFTER verified durable persistence.
        mutable.value = next
        return next
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun duplicate(key: String, payload: String): Boolean {
        require(key.isNotBlank() && key.length <= 512) { "body_operation_missing" }
        val before = mutable.value.receipts[hash(key)] ?: return false
        require(before.substringBefore(':') == hash(payload)) { "body_replay_conflict" }
        return true
    }
    private fun receipt(state: BodyState, key: String, payload: String): Map<String, String> {
        require(state.receipts.size < 16000) { "body_receipt_limit" }
        return state.receipts + (hash(key) to "${hash(payload)}:${state.revision}")
    }
    @Synchronized fun receiptSeq(key: String): Long? = snapshot().receipts[hash(key)]?.substringAfter(':', "")?.toLongOrNull()
    private fun logged(old: BodyState, conversation: String, event: String, strength: Int = 0, gap: Long = 0,
        concentration: Double? = null, at: Long = old.clock.wallMs): BodyState {
        check(old.revision < Long.MAX_VALUE) { "body_revision_limit" }
        val seq = old.revision + 1
        val def = old.versions.lastOrNull()
        val logs = old.logs + BodyLog(seq, at, def?.version ?: 0, conversation, event, strength, gap,
            def?.let { BodyRules.values(old.snapshot, conversation, it.definition) } ?: emptyMap(), concentration)
        return old.copy(revision = seq, logs = logs.takeLast(2000),
            checkpointSeq = if (logs.size > 2000) logs.first().seq else old.checkpointSeq)
    }
    @Synchronized fun setRunning(running: Boolean) {
        val old = snapshot()
        if(old.running == running) return
        require(!running || old.versions.lastOrNull()?.definition?.dimensions?.isNotEmpty() == true) { "body_definition_empty" }
        val settled = settle(old, now())
        // A paused/disabled interval is never converted into an absence penalty on restart.
        commit(logged(BodyTime.reset(settled).copy(running = running, lastHumanAt = 0, undo = null,
            resumedProcess = if(running) settled.clock.process else ""), "host", if(running) "host_start" else "host_pause"))
    }
    @Synchronized fun define(definition: BodyDefinition, expected: Long, key: String) {
        BodyRules.validate(definition)
        val payload = "define:$expected:" + BodyRules.json.encodeToString(definition)
        if(duplicate(key, payload)) return
        val old = snapshot(); require(!old.running) { "body_pause_before_definition" }
        require(old.revision == expected) { "body_revision_changed" }
        val locked = BodyRules.privateIds(old)
        require(definition.dimensions.none { it.id in locked && !it.isPrivate }) { "body_private_downgrade_forbidden" }
        val next = logged(settle(old, now()).copy(draft = definition, undo = null,
            privateDimensionIds = locked + definition.dimensions.filter { it.isPrivate }.map { it.id }), "host", "host_draft")
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    /** Runtime belongs to the assistant; the human module toggle may still force a stop. */
    @Synchronized fun setRunningFromTool(conversation: String, running: Boolean, expected: Long, key: String) {
        require(BodyRules.id(conversation)) { "body_conversation_invalid" }
        val payload = "running:$conversation:$running:$expected"; if (duplicate(key, payload)) return
        val old = snapshot()
        require(old.revision == expected) { "body_revision_changed" }
        if (running && old.auto.pausedByCooling)
            require(now().wallMs >= old.auto.cooldownUntil) { "body_cooling_active" }
        require(!running || old.versions.lastOrNull()?.definition?.dimensions?.isNotEmpty() == true) { "body_definition_empty" }
        val settled = settle(old, now())
        val next = logged((if (old.running == running) settled else BodyTime.reset(settled)).copy(running = running, lastHumanAt = if (old.running == running) old.lastHumanAt else 0,
            undo = null, auto = if (running) settled.auto.copy(pausedByCooling = false) else settled.auto,
            resumedProcess = if (running) settled.clock.process else ""), conversation,
            if (running) "host_start" else "host_pause")
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun activate(expected: Long, key: String) {
        val payload = "activate:$expected"; if(duplicate(key, payload)) return
        val old = snapshot(); require(!old.running) { "body_pause_before_definition" }
        require(old.revision == expected && old.draft != null) { "body_revision_changed" }
        require(old.versions.size < 64) { "body_version_limit" }
        val def = old.draft!!; BodyRules.validate(def)
        val prior = old.versions.lastOrNull()?.definition
        val retired = old.versions.flatMap { it.definition.dimensions }.map { it.id }.toSet() - prior?.dimensions.orEmpty().map { it.id }.toSet()
        require(def.dimensions.none { it.id in retired }) { "body_retired_dimension_requires_new_id" }
        // Changing an existing field's scope/range would teleport or silently clamp its state.
        // Use a NEW field ID for that migration; unchanged IDs remain continuous.
        prior?.dimensions?.forEach { d -> def.dimensions.find { it.id == d.id }?.let { n ->
            require(n.scope == d.scope && n.min == d.min && n.max == d.max) { "body_dimension_migration_requires_new_id" }
        } }
        val clock = now(); val settled = settle(old, clock)
        val next = logged(settled.copy(versions = old.versions + BodyVersion(old.versions.size + 1, clock.wallMs, def),
            draft = null, undo = null, snapshot = BodyRules.materialize(settled.snapshot, def, 0)), "host", "host_activate")
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun event(conversation: String, event: String, strength: Int, visible: Boolean, key: String, expectedVersion: Int? = null) {
        require(strength in 0..3) { "body_strength_invalid" }
        val payload = "event:$conversation:$event:$strength:$visible:$expectedVersion"; if(duplicate(key, payload)) return
        val old = snapshot()
        require(old.versions.lastOrNull()?.definition?.triggerMode != "auto") { "body_manual_event_disabled" }
        require(old.running) { "body_not_running" }
        require(expectedVersion == null || expectedVersion == old.versions.last().version) { "body_version_changed" }
        val version = old.versions.last(); val rule = version.definition.rules.find { it.event == event } ?: error("body_unknown_event")
        val settled = settle(old, now())
        val updated = BodyRules.apply(settled.snapshot, conversation, version.definition, rule.deltas.mapValues { it.value * (1 + strength * 0.25) })
        val current = updated.sessions.getValue(conversation)
        val next = logged(settled.copy(snapshot = updated.copy(sessions = updated.sessions + (conversation to current.copy(visible = visible)))), conversation, event, strength)
        commit(next.copy(receipts = receipt(next, key, payload), undo = BodyUndo(next.revision, version.version, conversation, settled.snapshot, settled.clock, settled.timeline)))
    }
    /** A new host-identified human message only. Never called for a page render, AI event or model retry. */
    @Synchronized fun human(conversation: String, messageId: String, text: String, keywordsEnabled: Boolean) {
        require(BodyRules.id(conversation) && BodyRules.id(messageId)) { "body_conversation_invalid" }
        // Message IDs are host UUIDs, shared when an existing message is forked into a branch.
        // Deduplicate per owner, not per window, to avoid incrementing persistent state twice.
        val key = "human:$messageId"
        // Edits/regeneration of the same message must NOT replay triggers; the body is not a receipt fingerprint.
        val payload = key; if(duplicate(key, payload)) return
        val old = snapshot()
        val definition = old.versions.lastOrNull()?.definition ?: return
        if (definition.triggerMode == "auto") {
            humanAuto(old, definition, conversation, text, key, payload)
            return
        }
        if(!old.running) return
        val settled = settle(old, now()); val def = old.versions.last().definition
        val gap = BodyTime.gap(old, conversation, settled.clock.wallMs)
        var value = settled.snapshot
        if(gap > def.absence.graceMinutes * 60_000L) {
            def.rules.find { it.event == def.absence.returnEvent }?.let { value = BodyRules.apply(value, conversation, def, it.deltas) }
        }
        // Plain substring matching, opt-in; no regex, network, inferred emotion or stored human text.
        if(keywordsEnabled) def.rules.filter { rule -> rule.keywords.any { text.take(32000).contains(it, ignoreCase = true) } }
            .take(8).forEach { value = BodyRules.apply(value, conversation, def, it.deltas) }
        // Materialize the session even if this input has no matching event.
        value = BodyRules.apply(value, conversation, def, emptyMap())
        val next = logged(BodyTime.human(settled.copy(snapshot = value, lastHumanAt = maxOf(old.lastHumanAt, settled.clock.wallMs), undo = null,
            resumedProcess = settled.clock.process), conversation),
            conversation, "host_human", gap = gap)
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    private fun humanAuto(old: BodyState, definition: BodyDefinition, conversation: String, text: String,
        key: String, payload: String) {
        val clock = now(); val time = maxOf(old.clock.wallMs, clock.wallMs)
        val decision = BodyAutoTrigger.evaluate(definition, old.auto, conversation, text, time, old.running)
        var next = settle(old, clock).copy(auto = decision.auto, undo = null)
        if (!old.running) {
            // Consume new messages while paused, never catch up with them after resuming.
            next = logged(next, conversation, "host_human", at = time)
        } else {
            val gap = BodyTime.gap(old, conversation, time)
            var value = next.snapshot
            if (gap > definition.absence.graceMinutes * 60_000L && !(decision.cooled && definition.cooling.pauseAuto)) {
                // Cooling affects only the configured event set; it does not freeze every dimension.
                definition.rules.find { it.event == definition.absence.returnEvent }?.takeUnless {
                    time < decision.auto.cooldownUntil && it.event in decision.auto.blockedEvents
                }?.let { rule ->
                    val marks = next.auto.marks.filter { it.conversation == conversation && it.event == rule.event && time - it.at < BodyAutoTrigger.windowMs(definition) }
                    if (marks.size < definition.autoPolicy.maxEventsPerWindow && next.auto.marks.size < 4096) {
                        val concentration = minOf(1.0, definition.autoPolicy.maxConcentration)
                        value = BodyRules.apply(value, conversation, definition, rule.deltas.mapValues { it.value * concentration })
                        next = logged(next.copy(snapshot = value, auto = next.auto.copy(marks = next.auto.marks +
                            BodyAutoMark(conversation, rule.event, hash("return:$key"), time))), conversation, rule.event, strength = 1, concentration = concentration, at = time)
                    }
                }
            }
            value = BodyRules.apply(value, conversation, definition, emptyMap())
            value = value.copy(sessions = value.sessions + (conversation to value.sessions.getValue(conversation).copy(visible = true)))
            next = next.copy(snapshot = value)
            for (event in decision.events) {
                val rule = definition.rules.single { it.event == event.event }
                next = logged(next.copy(snapshot = BodyRules.apply(next.snapshot, conversation, definition,
                    rule.deltas.mapValues { it.value * event.concentration })), conversation, event.event,
                    strength = kotlin.math.ceil(event.concentration).toInt().coerceIn(0, 3), concentration = event.concentration, at = time)
            }
            val pause = decision.cooled && definition.cooling.pauseAuto
            next = logged((if (pause) BodyTime.reset(next) else BodyTime.human(next, conversation)).copy(lastHumanAt = if (pause) 0 else time, running = !pause,
                resumedProcess = if (pause) "" else clock.process), conversation,
                if (decision.cooled) "host_cooling" else "host_human", gap = gap, at = time)
        }
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun sharePrivate(conversation: String, shared: Boolean, expected: Long, key: String) {
        require(BodyRules.id(conversation)) { "body_conversation_invalid" }
        val payload = "private-share:$conversation:$shared:$expected"; if (duplicate(key, payload)) return
        val old = snapshot(); require(old.revision == expected) { "body_revision_changed" }
        require(conversation in old.snapshot.sessions || old.snapshot.sessions.size < 128) { "body_session_limit" }
        val current = old.snapshot.sessions[conversation] ?: BodySession()
        // Visibility must not run the calculator, change peaks or restore an older undo snapshot.
        val next = logged(old.copy(snapshot = old.snapshot.copy(sessions = old.snapshot.sessions +
            (conversation to current.copy(privateShared = shared))), undo = null), conversation, "host_visibility", at = now().wallMs)
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun undo(conversation: String, expectedSeq: Long, key: String) {
        val payload = "undo:$conversation:$expectedSeq"; if(duplicate(key, payload)) return
        val old = snapshot()
        require(old.versions.lastOrNull()?.definition?.triggerMode != "auto") { "body_auto_undo_disabled" }
        val undo = old.undo ?: error("body_nothing_to_undo")
        require(undo.seq == expectedSeq && undo.seq == old.revision && undo.conversation == conversation &&
            undo.version == old.versions.last().version) { "body_undo_changed" }
        val clock = now()
        val restored = settle(old.copy(snapshot = undo.before, clock = undo.clock, timeline = undo.timeline), clock)
        val next = logged(restored.copy(undo = null), conversation, "host_undo")
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun selfReport(conversation: String, text: String, key: String) {
        require(text.length <= 300 && BodyRules.id(conversation)) { "body_self_report_limit" }
        val payload = "self:$conversation:$text"; if(duplicate(key, payload)) return
        val old = snapshot(); require(old.running) { "body_not_running" }
        require(conversation in old.snapshot.sessions || old.snapshot.sessions.size < 128) { "body_session_limit" }
        val session = old.snapshot.sessions[conversation] ?: BodySession()
        val settled = settle(old, now())
        val current = settled.snapshot.sessions[conversation] ?: session
        val next = logged(settled.copy(snapshot = settled.snapshot.copy(sessions = settled.snapshot.sessions +
            (conversation to current.copy(selfReport = text))), undo = null), conversation, "host_self_report")
        commit(next.copy(receipts = receipt(next, key, payload)))
    }
    @Synchronized fun refresh() = commit(settle(snapshot(), now()))
}
