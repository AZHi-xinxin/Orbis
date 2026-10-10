package me.rerere.rikkahub.data.orbis.sentinel

import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory synthetic storage only; never starts observation, delivery, or Android services. */
class OrbisSentinelRuleStoreTest {
    @Test fun `skipped wake finishes one occurrence and preserves next periodic wake`() {
        val disk = Storage(); val store = disk.open()
        store.create(rule())
        store.reserveFire("rule-1", "skip-first", 200)
        store.stageEventText("rule-1", "skip-first", "synthetic skipped content")
        store.completeFire("rule-1", "skip-first", 300, skippedReason = "wake_reply_in_progress")
        val reopened = disk.open()
        assertNull(reopened.get("rule-1")!!.pendingEventId)
        assertTrue(reopened.get("rule-1")!!.enabled)
        assertTrue(reopened.get("rule-1")!!.armed)
        assertEquals(300L, reopened.get("rule-1")!!.lastFiredAtMs)
        assertEquals(OrbisSentinelExecutionStatus.CANCELLED, reopened.executions().single().status)
        assertEquals("wake_reply_in_progress", reopened.executions().single().detail)
        assertNull(reopened.reserveFire("rule-1", "too-early", 400))
        assertNotNull(reopened.reserveFire("rule-1", "fresh-next", 1300))
    }

    @Test fun `skipped once notification is not repeatedly rearmed`() {
        val store = Storage().open()
        store.create(rule().copy(type = OrbisSentinelType.ONCE, intervalMs = null, dueAtMs = 200))
        store.reserveFire("rule-1", "skip-once", 200)
        store.stageEventText("rule-1", "skip-once", "synthetic once content")
        store.completeFire("rule-1", "skip-once", 300, skippedReason = "wake_gateway_unconfirmed")
        assertFalse(store.get("rule-1")!!.enabled)
        assertNull(store.reserveFire("rule-1", "must-not-retry", 3000))
    }

    @Test fun `observation failure reason remains valid and survives storage restart`() {
        val disk = Storage(); val store = disk.open()
        store.create(rule())
        store.reserveFire("rule-1", "observation-test", 200)
        val reason = screenObservationFailureReason("accessibility_permission_required")
        store.abandonPending("rule-1", "observation-test", 300, reason)
        assertEquals(reason, disk.open().get("rule-1")!!.lastError)
    }

    @Test fun `pending reservation keeps its original generation across pause resume and restart`() {
        val disk = Storage(); val store = disk.open()
        store.create(rule())
        store.reserveFire("rule-1", "generation-test", 200)
        assertEquals(0L, store.get("rule-1")!!.pendingMasterGeneration)
        store.setHumanEnabled(false, 300)
        store.setHumanEnabled(true, 300)
        assertEquals(2L, store.refresh().masterGeneration)
        assertEquals(0L, disk.open().get("rule-1")!!.pendingMasterGeneration)
        store.abandonPending("rule-1", "generation-test", 300, "test_cancelled")
        assertNull(store.get("rule-1")!!.pendingMasterGeneration)
    }

    private val binding = OrbisSentinelBinding(
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
    )
    private val other = OrbisSentinelBinding(
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444",
    )
    private val json = Json { encodeDefaults = true }

    private class Storage(var contents: String? = null) {
        val lock = Any()
        var writes = 0
        var failBeforeWrite = false
        var failAfterWrite = false
        var dropWrite = false
        var onWrite: (() -> Unit)? = null
        fun open() = OrbisSentinelRuleStore(read = { contents }, write = {
            onWrite?.invoke()
            if (failBeforeWrite) throw IOException("synthetic_before_write")
            writes++
            if (!dropWrite) contents = it
            if (failAfterWrite) throw IOException("synthetic_after_write")
        }, lock = lock)
    }

    private fun rule(id: String = "rule-1", enabled: Boolean = true) = OrbisSentinelRule(
        id = id, assistantId = binding.assistantId, conversationId = binding.conversationId,
        type = OrbisSentinelType.INTERVAL, intervalMs = 1000, prompt = "  合成提醒原文\n不要修改。\t",
        enabled = enabled, createdAtMs = 100, updatedAtMs = 100, cooldownMs = 1000,
    )

    private fun fails(reason: String, block: () -> Unit) {
        assertEquals(reason, runCatching(block).exceptionOrNull()?.message)
    }

    private fun accepted(store: OrbisSentinelRuleStore, id: String, eventId: String, now: Long): OrbisSentinelRule {
        assertNotNull(store.reserveFire(id, eventId, now))
        store.stageEventText(id, eventId, "合成完整事件正文")
        return store.completeFire(id, eventId, now)
    }

    @Test fun `missing and minimal legacy state preserve master but start no new rules`() {
        for (encoded in listOf(null, "{\"version\":1}")) {
            val disk = Storage(encoded)
            val store = disk.open()
            assertTrue(store.state.value.enabled)
            assertTrue(store.list().isEmpty())
            assertTrue(store.executions().isEmpty())
            assertEquals(0, disk.writes)
        }
        val defaultRule = OrbisSentinelRule(
            assistantId = binding.assistantId, conversationId = binding.conversationId,
            type = OrbisSentinelType.INTERVAL, intervalMs = 1000, prompt = "默认不启动观察",
        )
        assertFalse(defaultRule.enabled)
        val store = Storage().open()
        store.create(defaultRule)
        assertNull(store.reserveFire(defaultRule.id, "event-1", 1000))
    }

    @Test fun `exact config round trips including screenshot notification and original prompt`() {
        val disk = Storage()
        val store = disk.open()
        val original = rule().copy(action = OrbisSentinelAction.SCREENSHOT,
            notificationLevel = OrbisSentinelNotificationLevel.STRONG, rearm = OrbisSentinelRearm.AFTER_COOLDOWN)
        store.create(original)
        assertEquals(original, disk.open().get(original.id))
        assertEquals(original.prompt, store.list(binding).single().prompt)
        assertTrue(store.list(other).isEmpty())
        assertTrue(disk.contents!!.contains("\"screenshot\""))
        assertTrue(disk.contents!!.contains("\"strong\""))
    }

    @Test fun `all trigger kinds validate required fields without invented observation`() {
        val store = Storage().open()
        val samples = listOf(
            rule("interval"),
            rule("once").copy(type = OrbisSentinelType.ONCE, intervalMs = null, dueAtMs = 1000),
            rule("idle").copy(type = OrbisSentinelType.CHAT_IDLE, intervalMs = null, thresholdMs = 3000),
            rule("left").copy(type = OrbisSentinelType.CHAT_LEFT, intervalMs = null, thresholdMs = 4000),
            rule("usage").copy(type = OrbisSentinelType.APP_USAGE, intervalMs = null, thresholdMs = 5000,
                appPackage = "synthetic.example.app"),
        )
        samples.forEach { store.create(it) }
        assertEquals(samples, store.list())
        fails("invalid_sentinel_interval") { store.create(rule("bad").copy(intervalMs = 0)) }
        fails("invalid_sentinel_app_condition") {
            store.create(samples.last().copy(id = "bad-package", appPackage = "not/a/package"))
        }
        assertTrue(store.executions().isEmpty())
    }

    @Test fun `AI scoped edits cannot retarget runtime fields or enable human master`() {
        val store = Storage().open()
        store.create(rule())
        store.setHumanEnabled(false, 200)
        fails("sentinel_target_mismatch") { store.update("rule-1", other, 300) { it.copy(prompt = "different") } }
        fails("sentinel_identity_changed") { store.update("rule-1", binding, 300) { it.copy(conversationId = other.conversationId) } }
        fails("sentinel_runtime_state_changed") { store.update("rule-1", binding, 300) { it.copy(lastFiredAtMs = 250) } }
        val changed = store.update("rule-1", binding, 300) { it.copy(prompt = "AI配置的新原文", enabled = true) }
        assertEquals("AI配置的新原文", changed.prompt)
        assertFalse(store.state.value.enabled)
        store.pause(changed.id, binding, 400)
        store.resume(changed.id, binding, 500)
        assertFalse(store.state.value.enabled)
        assertNull(store.reserveFire(changed.id, "while-master-off", 2000))
    }

    @Test fun `durable reservation replays one identity after restart and text freezes once`() {
        val disk = Storage()
        val store = disk.open()
        store.create(rule())
        val original = store.reserveFire("rule-1", "event-original", 200)!!
        assertFalse(original.replay)
        val reopened = disk.open()
        val replay = reopened.reserveFire("rule-1", "event-other", 300)!!
        assertTrue(replay.replay)
        assertEquals("event-original", replay.rule.pendingEventId)
        assertEquals(1, reopened.executions().size)
        val staged = reopened.stageEventText("rule-1", "event-original", "  exact observation\n")
        val writes = disk.writes
        assertEquals(staged, reopened.stageEventText("rule-1", "event-original", "  exact observation\n"))
        assertEquals(writes, disk.writes)
        fails("sentinel_event_text_conflict") { reopened.stageEventText("rule-1", "event-original", "new observation") }
        fails("sentinel_event_pending") { reopened.update("rule-1", binding, 400) { it.copy(prompt = "edited") } }
        fails("sentinel_pending_event_mismatch") { reopened.completeFire("rule-1", "event-other", 500) }
        assertEquals("  exact observation\n", disk.open().get("rule-1")!!.pendingText)
    }

    @Test fun `human pause blocks reserved and staged outboxes permanently until explicit reconciliation`() {
        for (stage in listOf(false, true)) {
            val disk = Storage()
            val store = disk.open()
            store.create(rule())
            store.reserveFire("rule-1", "event-1", 200)
            if (stage) store.stageEventText("rule-1", "event-1", "frozen event")
            store.setHumanEnabled(false, 300)
            assertTrue(store.get("rule-1")!!.pendingBlocked)
            assertEquals(OrbisSentinelExecutionStatus.BLOCKED, store.executions().single().status)
            assertNull(store.reserveFire("rule-1", "new-event", 400))
            store.setHumanEnabled(true, 500)
            assertEquals(500L, store.state.value.resumedAtMs)
            val reopened = disk.open()
            assertNull(reopened.reserveFire("rule-1", "new-event", 600))
            fails("sentinel_event_blocked") { reopened.stageEventText("rule-1", "event-1", "frozen event") }
            reopened.abandonPending("rule-1", "event-1", 700, "human_disabled")
            assertNull(reopened.get("rule-1")!!.pendingEventId)
            assertFalse(reopened.get("rule-1")!!.armed)
            assertEquals(OrbisSentinelExecutionStatus.CANCELLED, reopened.executions().single().status)
        }
    }

    @Test fun `late confirmed receipt can reconcile blocked outbox but never reenables paused rule`() {
        val store = Storage().open()
        store.create(rule())
        store.reserveFire("rule-1", "event-1", 200)
        store.stageEventText("rule-1", "event-1", "already accepted by synthetic inbox")
        store.pause("rule-1", binding, 300)
        val completed = store.completeFire("rule-1", "event-1", 400)
        assertFalse(completed.enabled)
        assertNull(completed.pendingEventId)
        assertEquals(OrbisSentinelExecutionStatus.ACCEPTED, store.executions().single().status)
        assertEquals(completed, store.completeFire("rule-1", "event-1", 500))
    }

    @Test fun `once is disabled after acceptance and missed once is not replayed on master resume`() {
        val store = Storage().open()
        store.create(rule().copy(type = OrbisSentinelType.ONCE, intervalMs = null, dueAtMs = 200))
        val completed = accepted(store, "rule-1", "once-first", 200)
        assertFalse(completed.enabled)
        assertFalse(completed.armed)
        fails("sentinel_once_requires_new_due_time") { store.resume("rule-1", binding, 300) }
        fails("sentinel_once_requires_new_due_time") { store.update("rule-1", binding, 300) { it.copy(enabled = true) } }
        store.update("rule-1", binding, 400) { it.copy(dueAtMs = 1000, enabled = true) }
        store.setHumanEnabled(false, 500)
        store.setHumanEnabled(true, 2000)
        assertNull(store.reserveFire("rule-1", "missed-not-replayed", 3000))
        assertEquals(1, store.executions().size)
    }

    @Test fun `conditional reset and cooldown both gate repeated delivery`() {
        val store = Storage().open()
        store.create(rule().copy(type = OrbisSentinelType.CHAT_IDLE, intervalMs = null, thresholdMs = 100))
        val first = accepted(store, "rule-1", "first", 1000)
        assertFalse(first.armed)
        assertNull(store.reserveFire("rule-1", "second", 3000))
        store.rearm("rule-1", 1100)
        assertNull(store.reserveFire("rule-1", "second", 1500))
        assertNotNull(store.reserveFire("rule-1", "second", 2000))
        assertEquals(2, store.executions().size)
    }

    @Test fun `after cooldown and interval rearm do not require a false condition`() {
        for (configuration in listOf(rule(), rule().copy(type = OrbisSentinelType.CHAT_LEFT,
            intervalMs = null, thresholdMs = 100, rearm = OrbisSentinelRearm.AFTER_COOLDOWN))) {
            val store = Storage().open()
            store.create(configuration)
            assertTrue(accepted(store, "rule-1", "first", 1000).armed)
            assertNull(store.reserveFire("rule-1", "second", 1999))
            assertNotNull(store.reserveFire("rule-1", "second", 2000))
        }
    }

    @Test fun `delete is scoped cancels only its pending receipt and keeps execution history`() {
        val store = Storage().open()
        store.create(rule())
        store.reserveFire("rule-1", "pending", 1000)
        fails("sentinel_target_mismatch") { store.delete("rule-1", other) }
        assertNotNull(store.delete("rule-1", binding))
        assertNull(store.get("rule-1"))
        assertNull(store.delete("rule-1", binding))
        assertEquals(OrbisSentinelExecutionStatus.CANCELLED, store.executions(binding).single().status)
        assertTrue(store.executions(other).isEmpty())
        assertNull(store.reserveFire("rule-1", "never-delivered", 2000))
    }

    @Test fun `publish waits for atomic write verification and rejected writes preserve old state`() {
        val disk = Storage()
        val store = disk.open()
        val observed = mutableListOf<OrbisSentinelState>()
        disk.onWrite = { observed += store.state.value }
        store.create(rule())
        assertTrue(observed.single().rules.isEmpty())
        val before = store.state.value
        disk.failBeforeWrite = true
        fails("synthetic_before_write") { store.pause("rule-1", binding, 300) }
        assertEquals(before, store.state.value)
        disk.failBeforeWrite = false
        disk.dropWrite = true
        fails("sentinel_store_verify_failed") { store.pause("rule-1", binding, 400) }
        assertEquals(before, store.state.value)
        assertEquals(before.rules.single(), disk.open().get("rule-1"))
    }

    @Test fun `postcommit exception rereads disk and never overwrites the durable reservation`() {
        val disk = Storage()
        val store = disk.open()
        store.create(rule())
        val old = store.state.value
        disk.failAfterWrite = true
        fails("synthetic_after_write") { store.reserveFire("rule-1", "original", 200) }
        assertEquals(old, store.state.value)
        disk.failAfterWrite = false
        val recovered = store.reserveFire("rule-1", "replacement", 300)!!
        assertTrue(recovered.replay)
        assertEquals("original", recovered.rule.pendingEventId)
        assertEquals(1, store.executions().size)
    }

    @Test fun `independent store instances reread under shared lock instead of losing edits`() {
        val disk = Storage()
        val first = disk.open()
        val second = disk.open()
        first.create(rule("first"))
        second.create(rule("second"))
        assertEquals(listOf("first", "second"), first.list().map { it.id })
        first.reserveFire("first", "same-event", 300)
        fails("sentinel_event_id_conflict") { second.reserveFire("second", "same-event", 400) }
        assertEquals(2, second.list().size)
        assertEquals(1, second.executions().size)
    }

    @Test fun `corrupt unsupported duplicate and disappeared storage fail closed`() {
        val original = rule()
        val invalid = listOf("not-json", "{\"version\":2}",
            json.encodeToString(OrbisSentinelState(rules = listOf(original, original))),
            json.encodeToString(OrbisSentinelState(rules = listOf(original.copy(assistantId = "bad")))))
        invalid.forEach { encoded ->
            val disk = Storage(encoded)
            assertNotNull(runCatching { disk.open() }.exceptionOrNull())
            assertEquals(encoded, disk.contents)
            assertEquals(0, disk.writes)
        }
        val disk = Storage()
        val store = disk.open()
        store.create(rule())
        disk.contents = null
        fails("sentinel_store_disappeared") { store.create(rule("second")) }
        assertEquals(1, store.state.value.rules.size)
        assertEquals(1, disk.writes)
    }

    @Test fun `pending errors retain identity and only reason codes are accepted`() {
        val store = Storage().open()
        store.create(rule())
        store.reserveFire("rule-1", "event-1", 200)
        val failed = store.markPendingError("rule-1", "event-1", "capture_permission_missing")
        assertEquals("event-1", failed.pendingEventId)
        assertEquals("capture_permission_missing", store.executions().single().detail)
        fails("invalid_sentinel_error_code") { store.markPendingError("rule-1", "event-1", "provider response with private text") }
        assertTrue(store.reserveFire("rule-1", "different-id", 300)!!.replay)
    }

    @Test fun `master generation rejects observations from before a same millisecond stop and resume`() {
        val disk = Storage()
        val store = disk.open()
        store.create(rule())
        val observed = store.refresh()
        assertEquals(0L, observed.masterGeneration)
        store.setHumanEnabled(false, 200)
        store.setHumanEnabled(true, 200)
        assertEquals(2L, store.refresh().masterGeneration)
        assertNull(store.reserveFire("rule-1", "stale", 1000,
            expectedUpdatedAtMs = observed.rules.single().updatedAtMs,
            expectedMasterGeneration = observed.masterGeneration))
        assertTrue(store.executions().isEmpty())
        val reopened = disk.open()
        assertEquals(2L, reopened.refresh().masterGeneration)
        assertNotNull(reopened.reserveFire("rule-1", "fresh", 1000, expectedMasterGeneration = 2))
    }

    @Test fun `initial master generation is checkable and unchanged switch is not a new generation`() {
        val store = Storage().open()
        store.create(rule())
        store.setHumanEnabled(true, 200)
        assertEquals(0L, store.refresh().masterGeneration)
        assertNull(store.reserveFire("rule-1", "wrong-generation", 1000, expectedMasterGeneration = 1))
        assertNotNull(store.reserveFire("rule-1", "initial-generation", 1000, expectedMasterGeneration = 0))
    }
}
