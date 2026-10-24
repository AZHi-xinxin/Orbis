package me.rerere.rikkahub.data.orbis.body

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import org.junit.Assert.*
import org.junit.Test

/** Seed valid near-capacity documents; exercise the real commit and reopen paths. */
class BodyCapacityBoundaryTest {
    private val clock = BodyClock("capacity-test", 0, 1_000_000)
    private val definition = BodyDefinition(
        listOf(BodyDimension("energy", "活力"), BodyDimension("bond", "熟悉度", "persistent")),
        listOf(BodyRule("rest", mapOf("energy" to 2.0, "bond" to 1.0)))
    )

    private class Memory(var text: String? = null) : ActivityStorage {
        var writes = 0
        override fun read() = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    private fun active(): BodyState {
        val repo = BodyRepository(Memory()) { clock }
        repo.define(definition, 0, "draft")
        repo.activate(1, "activate")
        repo.setRunning(true)
        return repo.snapshot()
    }

    private fun store(state: BodyState): Memory {
        BodyRules.validate(state)
        val text = BodyRules.json.encodeToString(state)
        assertTrue("fixture must fit the production file cap", text.toByteArray().size <= BodyRules.MAX_BYTES)
        return Memory(text)
    }

    private fun unchanged(memory: Memory, repo: BodyRepository, before: BodyState, text: String?) {
        assertEquals(text, memory.text)
        assertEquals(0, memory.writes)
        assertEquals(before, repo.snapshot())
        assertEquals(before, BodyRepository(memory) { clock }.snapshot())
    }

    @Test fun fullReceiptLedgerRejectsNewEventButStillRecognizesDurableRetry() {
        val original = BodyRepository(store(active())) { clock }
        original.event("chat", "rest", 0, true, "already-committed")
        val state = original.snapshot()
        val receipts = state.receipts.toMutableMap()
        repeat(16000 - receipts.size) { index ->
            receipts[index.toString(16).padStart(64, '0')] = "${"a".repeat(64)}:1"
        }
        assertEquals(16000, receipts.size)
        val before = state.copy(receipts = receipts)
        val memory = store(before); val text = memory.text
        val repo = BodyRepository(memory) { clock }
        val error = assertThrows(IllegalArgumentException::class.java) {
            repo.event("chat", "rest", 0, true, "new-event")
        }
        assertEquals("body_receipt_limit", error.message)
        unchanged(memory, repo, before, text)
        repo.event("chat", "rest", 0, true, "already-committed")
        unchanged(memory, repo, before, text)
    }

    @Test fun fullSessionMapRefusesNewWindowWithoutChangingSharedState() {
        val initial = active()
        val before = initial.copy(snapshot = initial.snapshot.copy(
            sessions = (0 until 128).associate { "chat$it" to BodySession() }))
        val memory = store(before); val text = memory.text
        val repo = BodyRepository(memory) { clock }
        val error = assertThrows(IllegalArgumentException::class.java) {
            repo.event("new-window", "rest", 0, true, "refused")
        }
        assertEquals("body_session_limit", error.message)
        unchanged(memory, repo, before, text)
        repo.event("chat0", "rest", 0, true, "existing-window")
        val reopened = BodyRepository(memory) { clock }.snapshot()
        assertEquals(128, reopened.snapshot.sessions.size)
        assertEquals(51.0, reopened.snapshot.persistent.getValue("bond"), 0.0)
        assertEquals(52.0, reopened.snapshot.sessions.getValue("chat0").values.getValue("energy"), 0.0)
        assertFalse(reopened.snapshot.sessions.containsKey("new-window"))
    }

    @Test fun fullVersionHistoryPreservesDraftAndActiveDefinitionOnRefusal() {
        val before = BodyState(revision = 128, clock = clock, draft = definition,
            versions = (1..64).map { BodyVersion(it, clock.wallMs, definition) })
        val memory = store(before); val text = memory.text
        val repo = BodyRepository(memory) { clock }
        val error = assertThrows(IllegalArgumentException::class.java) { repo.activate(128, "version65") }
        assertEquals("body_version_limit", error.message)
        unchanged(memory, repo, before, text)
    }

    @Test fun logRolloverKeepsMaterializedStateAndRetryReceiptAcrossReopen() {
        val before = active().copy(revision = 2000, logs = (1L..2000L).map {
            BodyLog(it, clock.wallMs, 1, "chat", "rest")
        })
        val memory = store(before)
        val repo = BodyRepository(memory) { clock }
        repo.event("chat", "rest", 0, true, "after-log-cap")
        val reopened = BodyRepository(memory) { clock }
        val after = reopened.snapshot(); val text = memory.text; val writes = memory.writes
        assertEquals(2001L, after.revision)
        assertEquals(1L, after.checkpointSeq)
        assertEquals((2L..2001L).toList(), after.logs.map { it.seq })
        assertEquals(51.0, after.snapshot.persistent.getValue("bond"), 0.0)
        assertEquals(2001L, reopened.receiptSeq("after-log-cap"))
        reopened.event("chat", "rest", 0, true, "after-log-cap")
        assertEquals(text, memory.text); assertEquals(writes, memory.writes)
        assertEquals(after, reopened.snapshot())
    }

    @Test fun utf8FileCapRejectsWholeDraftWithoutPublishingOrOverwriting() {
        val large = BodyDefinition(
            dimensions = (0 until 16).map { BodyDimension("dimension$it", "状态$it") },
            rules = (0 until 64).map { rule -> BodyRule("event$rule",
                (0 until 16).associate { "dimension$it" to 1.0 },
                (0 until 8).map { "字".repeat(63) + it.toString() }) })
        BodyRules.validate(large)
        val before = (1..64).map { count ->
            BodyState(revision = count.toLong(), clock = clock,
                versions = (1..count).map { BodyVersion(it, clock.wallMs, large) })
        }.first { state ->
            BodyRules.json.encodeToString(state).toByteArray().size <= BodyRules.MAX_BYTES &&
                BodyRules.json.encodeToString(state.copy(draft = large)).toByteArray().size > BodyRules.MAX_BYTES
        }
        val memory = store(before); val text = memory.text
        val repo = BodyRepository(memory) { clock }
        val error = assertThrows(IllegalArgumentException::class.java) {
            repo.define(large, before.revision, "too-large-draft")
        }
        assertEquals("body_storage_limit", error.message)
        unchanged(memory, repo, before, text)
    }
}
