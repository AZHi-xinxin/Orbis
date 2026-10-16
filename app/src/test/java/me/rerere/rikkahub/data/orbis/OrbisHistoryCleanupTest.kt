package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.sentinel.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OrbisHistoryCleanupTest {
    private val now = 20 * 86_400_000L
    private val a = "11111111-1111-4111-8111-111111111111"
    private val c = "22222222-2222-4222-8222-222222222222"
    private val binding = OrbisEventBinding(a, c)
    private val json = Json { encodeDefaults = true }
    private class Disk(var value: String? = null) {
        var failBefore = false
        var failAfter = false
        fun inbox() = OrbisEventInbox({ value }, { if (failBefore) error("before"); value = it; if (failAfter) error("after") })
        fun rules() = OrbisSentinelRuleStore({ value }, { if (failBefore) error("before"); value = it; if (failAfter) error("after") }, Any())
    }
    private fun event(id: String, status: String = "replied", received: Long = 1, wake: Boolean = true) =
        OrbisInboxEvent(id = id, eventId = id, source = "lc_sentinel", text = "original $id " + "x".repeat(2000), wake = wake,
            assistantId = a, conversationId = c, receivedAt = received, state = status,
            independentDelivery = true, attemptStarted = true, historyCommitted = true)
    private fun storage(events: List<OrbisInboxEvent>) = Disk(json.encodeToString(OrbisInboxState(bindings = mapOf("lc_sentinel" to binding), events = events)))
    private fun input(e: OrbisInboxEvent) = OrbisIncomingEvent(e.eventId, e.source, e.text, e.wake, e.occurredAt, e.localImage, e.workshopImages)

    @Test fun oldCompletedTextIsRemovedButIdentityAndWakeHighWaterRemain() {
        val old = event("old").copy(occurredAt = 1)
        val disk = storage(listOf(old)); val inbox = disk.inbox()
        val result = inbox.cleanHistory(now, 5, emptySet(), UUID.randomUUID().toString())
        assertEquals(1, result.removed)
        assertTrue(result.bytesAfter < result.bytesBefore)
        assertFalse(disk.value!!.contains("original old"))
        val reopened = disk.inbox()
        assertTrue(reopened.state.value.events.isEmpty())
        assertEquals(1L, reopened.state.value.lastWakeAt(c))
        assertEquals(old.copy(text = "", error = null), reopened.get(old.id))
        assertTrue(reopened.accept(input(old), binding, now).second)
        assertTrue(reopened.accept(input(old).copy(occurred_at = null), binding, now).second)
        assertEquals("event_id_conflict", runCatching { reopened.accept(input(old).copy(text = "changed"), binding, now) }.exceptionOrNull()?.message)
        assertEquals("event_id_conflict", runCatching { reopened.accept(input(old).copy(occurred_at = 2), binding, now) }.exceptionOrNull()?.message)
        assertFalse(reopened.claimIndependentDispatch(old.id))
        reopened.mark(old.id, "generating")
        assertEquals("replied", reopened.get(old.id)?.state)
        assertEquals(binding, reopened.binding("lc_sentinel"))
    }

    @Test fun everyPendingUncertainRecoveryAndWorkshopReceiptIsProtected() {
        val protected = listOf("accepted", "queued", "generating", "pending_tool", "unknown", "failed", "suppressed", "skipped", "target_invalid", "displayed")
            .map { event(it, it) } + event("uncommitted").copy(historyCommitted = false) +
            event("workshop").copy(source = "image_workshop.${UUID.randomUUID()}") + event("rule-reference")
        val inbox = storage(protected + event("safe-display", "displayed", wake = false)).inbox()
        val result = inbox.cleanHistory(now, 5, setOf("rule-reference"), UUID.randomUUID().toString())
        assertEquals(1, result.removed)
        assertEquals(protected, inbox.state.value.events)
        assertEquals(protected.size, result.protected)
    }

    @Test fun boundaryRecentCompletionClockRollbackAndDaysValidate() {
        val cutoff = historyCutoff(now, 5)
        val inbox = storage(listOf(event("before", received = cutoff - 1), event("exact", received = cutoff),
            event("after", received = cutoff + 1), event("just-completed").copy(updatedAt = now), event("future", received = now + 1))).inbox()
        assertEquals(1, inbox.historyPreview(now, 5).candidates)
        assertEquals(0, inbox.historyPreview(0, 5).candidates)
        assertTrue(runCatching { inbox.historyPreview(now, 0) }.isFailure)
        assertTrue(runCatching { inbox.historyPreview(now, 366) }.isFailure)
        assertFalse(OrbisHistoryRetention().isDue(now))
        assertFalse(OrbisHistoryRetention(true, 5, now + 1).isDue(now))
        assertFalse(OrbisHistoryRetention(true, 5, now - 86_400_000 + 1).isDue(now))
        assertTrue(OrbisHistoryRetention(true, 5, now - 86_400_000).isDue(now))
    }

    @Test fun cleanupOperationRetriesAndWriteFailuresNeverReplayOldNotification() {
        for (after in listOf(false, true)) {
            val old = event("old"); val disk = storage(listOf(old)); val inbox = disk.inbox()
            val operation = UUID.randomUUID().toString()
            disk.failBefore = !after; disk.failAfter = after
            assertTrue(runCatching { inbox.cleanHistory(now, 5, emptySet(), operation) }.isFailure)
            disk.failBefore = false; disk.failAfter = false
            val retry = inbox.cleanHistory(now, 5, emptySet(), operation)
            assertEquals(1, retry.removed)
            assertEquals(retry, disk.inbox().cleanHistory(now, 5, emptySet(), operation))
            assertTrue(disk.inbox().accept(input(old), binding, now).second)
        }
    }

    @Test fun cleanupFrees2000RecordLimitWithoutExpiringDeduplication() {
        val old = (1..2000).map { event("old$it").copy(text = "text") }
        val disk = storage(old); val inbox = disk.inbox()
        assertEquals("event_inbox_full", runCatching { inbox.accept(OrbisIncomingEvent("new", "lc_sentinel", "new"), binding, now) }.exceptionOrNull()?.message)
        inbox.cleanHistory(now, 3, emptySet(), UUID.randomUUID().toString())
        assertEquals(2000, inbox.state.value.historyReceipts.size)
        assertFalse(inbox.accept(OrbisIncomingEvent("new", "lc_sentinel", "new"), binding, now).second)
        assertTrue(inbox.accept(input(old.first()), binding, now).second)
    }

    private fun rule() = OrbisSentinelRule(id = "r", assistantId = a, conversationId = c,
        type = OrbisSentinelType.INTERVAL, enabled = true, prompt = "test", intervalMs = 1000,
        rearm = OrbisSentinelRearm.AFTER_COOLDOWN, createdAtMs = 1)

    @Test fun executionCleanupPreservesConfigPendingAndDuplicateComplete() {
        val disk = Disk(); val store = disk.rules(); store.create(rule())
        store.reserveFire("r", "done", 100); store.stageEventText("r", "done", "test"); store.completeFire("r", "done", 101)
        store.reserveFire("r", "pending", 200)
        val before = store.refresh()
        assertEquals(0, store.historyPreview(now, 5, emptySet()).candidates)
        val result = store.cleanHistory(now, 5, setOf(OrbisHistoryCompletion("native_sentinel.r", "done", a, c)), UUID.randomUUID().toString())
        assertEquals(1, result.removed)
        assertEquals(before.rules, store.refresh().rules)
        assertEquals("pending", store.executions().single().eventId)
        store.abandonPending("r", "pending", now, "human_cancelled")
        val state = store.refresh()
        assertEquals(state.rules.single(), store.completeFire("r", "done", now))
        store.rearm("r", now)
        assertEquals("sentinel_event_id_conflict", runCatching { store.reserveFire("r", "done", now + 1) }.exceptionOrNull()?.message)
        assertEquals(1, disk.rules().refresh().historyReceipts.size)
    }

    @Test fun twoStoresReportPartialCommitAndResumeSameOperation() {
        val deliveries = storage(listOf(event("done").copy(source = "native_sentinel.r"))); val inbox = deliveries.inbox()
        val executions = Disk(); val rules = executions.rules(); rules.create(rule())
        rules.reserveFire("r", "done", 100); rules.stageEventText("r", "done", "test"); rules.completeFire("r", "done", 101)
        val operation = UUID.randomUUID().toString(); executions.failBefore = true
        val partial = cleanOrbisHistory(inbox, rules, now, 5, operation)
        assertNotNull(partial.error); assertEquals(1, partial.deliveries?.removed); assertNull(partial.executions)
        executions.failBefore = false
        val resumed = cleanOrbisHistory(inbox, rules, now, 5, operation)
        assertNull(resumed.error); assertEquals(1, resumed.deliveries?.removed); assertEquals(1, resumed.executions?.removed)
        assertEquals(operation, resumed.deliveries?.operationId)
    }

    @Test fun sameIdFromAnotherSourceOrOwnerCannotProveNativeExecutionComplete() {
        val disk = Disk(); val rules = disk.rules(); rules.create(rule())
        rules.reserveFire("r", "done", 100); rules.stageEventText("r", "done", "test"); rules.completeFire("r", "done", 101)
        for (proof in listOf(OrbisHistoryCompletion("lc_sentinel", "done", a, c),
                OrbisHistoryCompletion("native_sentinel.other", "done", a, c),
                OrbisHistoryCompletion("native_sentinel.r", "done", c, c),
                OrbisHistoryCompletion("native_sentinel.r", "done", a, a))) {
            assertEquals(0, rules.historyPreview(now, 5, setOf(proof)).candidates)
        }
    }

    @Test fun unreadableCommitBlocksNextMutationUntilDurableCleanupIsRecovered() {
        val old = event("old")
        var encoded: String? = json.encodeToString(OrbisInboxState(bindings = mapOf("lc_sentinel" to binding), events = listOf(old)))
        var failRead = false
        var failAfterNextWrite = true
        val inbox = OrbisEventInbox({ if (failRead) error("unreadable"); encoded }, { encoded = it; if (failAfterNextWrite) failRead = true })
        assertTrue(runCatching { inbox.cleanHistory(now, 5, emptySet(), UUID.randomUUID().toString()) }.isFailure)
        val saved = encoded
        assertTrue(runCatching { inbox.accept(OrbisIncomingEvent("new", "lc_sentinel", "new"), binding, now) }.isFailure)
        assertEquals(saved, encoded)
        failRead = false; failAfterNextWrite = false
        assertTrue(inbox.accept(input(old), binding, now).second)
        assertTrue(inbox.state.value.events.isEmpty())
        assertFalse(inbox.accept(OrbisIncomingEvent("new", "lc_sentinel", "new"), binding, now).second)
    }
}
