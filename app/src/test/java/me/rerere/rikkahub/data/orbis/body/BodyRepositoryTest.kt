package me.rerere.rikkahub.data.orbis.body

import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BodyRepositoryTest {
    private class Memory : ActivityStorage {
        var text: String? = null; var fail = false; var writes = 0
        override fun read() = text
        override fun write(text: String) { if(fail) error("disk_full"); this.text = text; writes++ }
    }
    private val local = BodyDimension("energy", "活力", halfLifeMinutes = 1.0)
    private val slow = BodyDimension("bond", "熟悉度", "persistent", halfLifeMinutes = 1000.0)
    private fun definition() = BodyDefinition(listOf(local, slow), listOf(
        BodyRule("rest", mapOf("energy" to 8.0, "bond" to 4.0), listOf("休息")),
        BodyRule("return", mapOf("energy" to 2.0))), BodyAbsence(30, 24, mapOf("bond" to 2.0), "return"))
    private class Fixture {
        val memory = Memory(); var clock = BodyClock("p1", 0, 1_000_000)
        val repo = BodyRepository(memory) { clock }
    }
    private fun prepared(): Fixture = Fixture().also { f ->
        f.repo.define(definition(), 0, "draft"); f.repo.activate(1, "activate"); f.repo.setRunning(true)
    }
    private fun value(f: Fixture, id: String, conversation: String = "chat1") =
        BodyRules.values(f.repo.snapshot().snapshot, conversation, f.repo.snapshot().versions.last().definition).getValue(id)
    @Test fun defaultsHaveNoDefinitionNoSideEffects() {
        val f = Fixture(); assertFalse(f.repo.snapshot().running); assertNull(f.memory.text)
        assertThrows(IllegalArgumentException::class.java) { f.repo.setRunning(true) }
        assertEquals(0, f.memory.writes)
    }
    @Test fun onlyDeclaredEventsCanChangeValues() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        assertEquals(58.0, value(f, "energy"), 0.00001); assertEquals(54.0, value(f, "bond"), 0.00001)
        assertThrows(IllegalStateException::class.java) { f.repo.event("chat1", "invented", 0, true, "two") }
    }
    @Test fun sessionIsolatedButPersistentSharedWithinOwner() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        assertEquals(50.0, value(f, "energy", "chat2"), 0.0)
        assertEquals(54.0, value(f, "bond", "chat2"), 0.0)
        val other = prepared(); assertEquals(50.0, value(other, "bond"), 0.0)
    }
    @Test fun eventRetrySurvivesReopenAndConflictingPayloadRefused() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        val before = f.memory.text
        val again = BodyRepository(f.memory) { f.clock }
        again.event("chat1", "rest", 0, true, "one"); assertEquals(before, f.memory.text)
        assertThrows(IllegalArgumentException::class.java) { again.event("chat1", "rest", 1, true, "one") }
    }
    @Test fun diskFailureDoesNotPublishOrConsumeIdempotencyKey() {
        val f = prepared(); val before = f.repo.snapshot(); f.memory.fail = true
        assertThrows(IllegalStateException::class.java) { f.repo.event("chat1", "rest", 0, true, "one") }
        assertEquals(before, f.repo.snapshot()); f.memory.fail = false
        f.repo.event("chat1", "rest", 0, true, "one"); assertEquals(58.0, value(f, "energy"), 0.0)
    }
    @Test fun wallClockHalfLifeContinuesAcrossReboot() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        f.clock = f.clock.copy(wallMs = 1_060_000); f.repo.refresh()
        assertEquals(54.0, value(f, "energy"), .000001)
        f.clock = BodyClock("p2", 0, 1_120_000); f.repo.refresh()
        assertEquals(52.0, value(f, "energy"), .000001)
    }
    @Test fun absenceSettlesBeforeNextHumanAndIsNotAppliedTwice() {
        val f = prepared(); f.repo.human("chat1", "h1", "", false)
        f.clock = f.clock.copy(wallMs = f.clock.wallMs + 100L * 3600000)
        f.repo.refresh()
        // Drift runs for the configured 24 hours, then natural decay runs alone.
        val k = kotlin.math.ln(2.0) / (1000.0 * 60)
        val expected = 50 + (2.0 / 3600 / k) * (1 - kotlin.math.exp(-k * 24 * 3600)) *
            kotlin.math.exp(-k * 75.5 * 3600)
        assertEquals(expected, value(f, "bond"), .000001)
        f.repo.human("chat1", "h2", "", false)
        assertEquals(expected, value(f, "bond"), .000001)
        assertEquals(52.0, value(f, "energy"), .000001) // only the configured return event
        val before = f.memory.text; f.repo.human("chat1", "h2", "edited", false)
        assertEquals(before, f.memory.text)
    }
    @Test fun clockRollbackDoesNotPenalizeOrInventHugeGap() {
        val f = prepared(); f.repo.human("chat1", "h1", "", false)
        f.clock = f.clock.copy(wallMs = 100); f.repo.human("chat1", "h2", "", false)
        assertEquals(50.0, value(f, "bond"), 0.0)
        assertEquals(1_000_000, f.repo.snapshot().lastHumanAt)
    }
    @Test fun keywordsOffByDefaultAndEditDoesNotReplay() {
        val f = prepared(); f.repo.human("chat1", "h1", "休息", false)
        assertEquals(50.0, value(f, "energy"), 0.0)
        f.repo.human("chat1", "h2", "休息", true); assertEquals(58.0, value(f, "energy"), 0.0)
        f.repo.human("chat1", "h2", "休息休息", true); assertEquals(58.0, value(f, "energy"), 0.0)
    }
    @Test fun pauseDropsAbsenceAndFreezesValues() {
        val f = prepared(); f.repo.human("chat1", "h1", "", false)
        f.repo.event("chat1", "rest", 0, true, "one"); f.repo.setRunning(false)
        f.clock = f.clock.copy(activeMs = 600000, wallMs = 999000000)
        f.repo.refresh(); assertEquals(58.0, value(f, "energy"), 0.0)
        f.repo.setRunning(true); f.repo.human("chat1", "h2", "", false)
        assertEquals(54.0, value(f, "bond"), 0.0)
    }
    @Test fun newVersionDoesNotReinterpretOldDecayAndIsContinuous() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        f.clock = f.clock.copy(wallMs = 1_060_000); f.repo.setRunning(false)
        assertEquals(54.0, value(f, "energy"), .000001)
        val next = definition().copy(dimensions = listOf(local.copy(halfLifeMinutes = 2.0), slow))
        f.repo.define(next, f.repo.snapshot().revision, "d2"); f.repo.activate(f.repo.snapshot().revision, "a2")
        assertEquals(54.0, value(f, "energy"), .000001)
        f.repo.setRunning(true); f.clock = f.clock.copy(wallMs = 1_180_000); f.repo.refresh()
        assertEquals(52.0, value(f, "energy"), .000001)
        assertEquals(2, f.repo.snapshot().versions.size)
    }
    @Test fun runtimeDefinitionsAndDestructiveScopeMigrationRefused() {
        val f = prepared()
        assertThrows(IllegalArgumentException::class.java) { f.repo.define(definition(), f.repo.snapshot().revision, "d2") }
        f.repo.setRunning(false)
        f.repo.define(definition().copy(dimensions = listOf(local.copy(scope = "persistent"), slow)), f.repo.snapshot().revision, "d2")
        assertThrows(IllegalArgumentException::class.java) { f.repo.activate(f.repo.snapshot().revision, "a2") }
        assertEquals(1, f.repo.snapshot().versions.size)
    }
    @Test fun undoIsDurableLatestOnlyAndRestoresPersistentAndVisibility() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        val seq = f.repo.snapshot().revision
        assertThrows(IllegalArgumentException::class.java) { f.repo.undo("chat2", seq, "wrong") }
        f.repo.undo("chat1", seq, "undo"); assertEquals(50.0, value(f, "bond"), 0.0)
        assertNull(f.repo.snapshot().snapshot.sessions["chat1"])
        val restored = BodyRepository(f.memory) { f.clock }; restored.undo("chat1", seq, "undo")
        assertNull(restored.snapshot().undo)
    }
    @Test fun laterHumanOrOtherConversationCannotBeRolledBackAccidentally() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        val seq = f.repo.snapshot().revision; f.repo.human("chat1", "h1", "", false)
        assertThrows(IllegalStateException::class.java) { f.repo.undo("chat1", seq, "undo") }
    }
    @Test fun selfReportDoesNotWriteCalculationAndHiddenEventRemainsHidden() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, false, "one")
        f.repo.selfReport("chat1", "自述：活力999", "self")
        assertEquals(58.0, value(f, "energy"), 0.0)
        assertFalse(f.repo.snapshot().snapshot.sessions.getValue("chat1").visible)
    }
    @Test fun invalidNumbersDuplicatesCodeAndUnknownFieldsAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { number ->
            assertThrows(IllegalArgumentException::class.java) { BodyRules.validate(definition().copy(dimensions = listOf(local.copy(baseline = number), slow))) }
        }
        assertThrows(IllegalArgumentException::class.java) { BodyRules.validate(definition().copy(dimensions = listOf(local, local))) }
        assertThrows(Exception::class.java) { BodyRules.json.decodeFromString<BodyDefinition>("{\"script\":\"eval()\"}") }
        assertThrows(IllegalArgumentException::class.java) { BodyRules.validate(definition().copy(rules = listOf(BodyRule("../file", mapOf("energy" to 1.0))))) }
    }
    @Test fun clampNeverLetsAnInvalidOldValueEscapeHardLimits() {
        assertEquals(90.0, BodyRules.delta(10000.0, -1000.0, local), 0.0)
        assertEquals(10.0, BodyRules.delta(-10000.0, 1000.0, local), 0.0)
        assertEquals(100.0, BodyRules.delta(99.0, 1000.0, local), 0.0)
    }
    @Test fun corruptedStoreFailsClosedWithoutOverwrite() {
        val memory = Memory(); memory.text = "broken"
        assertThrows(Exception::class.java) { BodyRepository(memory) { BodyClock() } }
        assertEquals("broken", memory.text); assertEquals(0, memory.writes)
    }
    @Test fun oldEmptyStateDecodesWithoutNewFields() {
        assertEquals(BodyState(), BodyRules.json.decodeFromString<BodyState>("{}"))
        val state = prepared().repo.snapshot()
        assertEquals(state, BodyRules.json.decodeFromString<BodyState>(BodyRules.json.encodeToString(state)))
    }
    @Test fun replayReceiptKeepsOriginalSeqAfterSubsequentEvents() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one", 1)
        val seq = f.repo.receiptSeq("one")
        f.repo.event("chat1", "rest", 0, true, "two", 1)
        f.repo.event("chat1", "rest", 0, true, "one", 1)
        assertEquals(seq, f.repo.receiptSeq("one")); assertNotEquals(seq, f.repo.snapshot().revision)
    }
    @Test fun copiedHumanMessageInForkDoesNotDuplicateSharedTriggers() {
        val f = prepared(); f.repo.human("chat1", "h1", "休息", true)
        val before = f.memory.text; f.repo.human("fork", "h1", "休息", true)
        assertEquals(before, f.memory.text); assertEquals(54.0, value(f, "bond"), 0.0)
    }
    @Test fun staleVersionIsRejectedWithoutAnyWrite() {
        val f = prepared(); val before = f.memory.text
        assertThrows(IllegalArgumentException::class.java) { f.repo.event("chat1", "rest", 0, true, "one", 9) }
        assertEquals(before, f.memory.text)
    }
    @Test fun reopeningAndRefreshingContinuesWithoutNewHuman() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        f.clock = BodyClock("p2", 0, 1_060_000)
        val reopened = BodyRepository(f.memory) { f.clock }
        reopened.refresh()
        assertEquals(54.0, BodyRules.values(reopened.snapshot().snapshot, "chat1", definition()).getValue("energy"), .000001)
        f.repo.human("chat1", "h2", "", false)
        f.clock = f.clock.copy(wallMs = 1_120_000); f.repo.refresh()
        assertEquals(52.0, value(f, "energy"), .000001)
    }
    @Test fun invalidImportedValuesAndUndoCannotBypassBounds() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one")
        val state = f.repo.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            BodyRules.validate(state.copy(snapshot = state.snapshot.copy(persistent = mapOf("bond" to 100000.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BodyRules.validate(state.copy(undo = state.undo!!.copy(before = BodySnapshot(persistent = mapOf("foreign" to 5.0)))))
        }
        assertThrows(IllegalArgumentException::class.java) { BodyRules.validate(state.copy(receipts = mapOf("key" to "value"))) }
    }
    @Test fun retiringDimensionCleansPeaksAndRequiresNewIdToReintroduce() {
        val f = prepared(); f.repo.event("chat1", "rest", 0, true, "one"); f.repo.setRunning(false)
        val next = BodyDefinition(listOf(slow), listOf(BodyRule("rest", mapOf("bond" to 1.0))))
        f.repo.define(next, f.repo.snapshot().revision, "d2"); f.repo.activate(f.repo.snapshot().revision, "a2")
        assertFalse(f.repo.snapshot().snapshot.sessions.getValue("chat1").peaks.containsKey("energy"))
        f.repo.define(definition(), f.repo.snapshot().revision, "d3")
        assertThrows(IllegalArgumentException::class.java) { f.repo.activate(f.repo.snapshot().revision, "a3") }
    }
    @Test fun risingTowardANewBaselineUpdatesPeakWithoutFakingAnInstantJump() {
        val def = BodyDefinition(listOf(local.copy(baseline = 80.0)))
        val original = BodySnapshot(sessions = mapOf("chat" to BodySession(mapOf("energy" to 40.0), mapOf("energy" to 50.0))))
        val unchanged = BodyRules.materialize(original, def, 0).sessions.getValue("chat")
        assertEquals(40.0, unchanged.values.getValue("energy"), 0.0)
        assertEquals(50.0, unchanged.peaks.getValue("energy"), 0.0)
        val moved = BodyRules.materialize(original, def, 60000).sessions.getValue("chat")
        assertEquals(60.0, moved.values.getValue("energy"), .000001)
        assertEquals(60.0, moved.peaks.getValue("energy"), .000001)
    }
}
