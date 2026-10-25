package me.rerere.rikkahub.data.orbis.body

import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import org.junit.Assert.*
import org.junit.Test

class BodyTimeTest {
    private class Memory : ActivityStorage {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }
    private class Fixture {
        val memory = Memory()
        var clock = BodyClock("first", 0, 1_000_000)
        val repo = BodyRepository(memory) { clock }
        init {
            val dimensions = listOf(BodyDimension("up", "上升", baseline = 50.0, halfLifeMinutes = 60.0),
                BodyDimension("down", "下降", baseline = 50.0, halfLifeMinutes = 60.0, isPrivate = true),
                BodyDimension("shared", "共享", scope = "persistent", baseline = 50.0, halfLifeMinutes = 60.0))
            repo.define(BodyDefinition(dimensions, triggerMode = "auto", absence = BodyAbsence(
                graceMinutes = 10, capHours = 1, perHour = mapOf("up" to 30.0, "down" to -30.0, "shared" to 30.0))), 0, "draft")
            repo.activate(repo.snapshot().revision, "activate"); repo.setRunning(true)
            repo.human("a", "human1", "hello", false)
        }
        fun after(minutes: Int) { clock = clock.copy(wallMs = 1_000_000L + minutes * 60_000L) }
        fun values(conversation: String = "a", view: Boolean = true): Map<String, Double> {
            val s = if (view) repo.viewSnapshot() else repo.snapshot()
            return BodyRules.values(s.snapshot, conversation, s.versions.last().definition)
        }
    }

    @Test fun silentTimeChangesBothDirectionsWithoutAnyHumanOrModelCall() {
        val f = Fixture(); f.after(10); assertEquals(50.0, f.values().getValue("up"), 0.0)
        f.after(40)
        assertTrue(f.values().getValue("up") > 60)
        assertTrue(f.values().getValue("down") < 40)
        assertEquals(50.0, f.values(view = false).getValue("up"), 0.0) // pure projection
        assertEquals(f.values(), f.values())
    }

    @Test fun frequentRefreshAndOneLateSettlementHaveSameValuesAndPeaks() {
        val small = Fixture(); val large = Fixture()
        for (minute in 1..180) { small.after(minute); small.repo.refresh() }
        large.after(180); large.repo.refresh()
        for (id in listOf("up", "down", "shared")) {
            assertEquals(small.values().getValue(id), large.values().getValue(id), 1e-9)
        }
        assertEquals(small.repo.snapshot().snapshot.sessions.getValue("a").peaks.getValue("up"),
            large.repo.snapshot().snapshot.sessions.getValue("a").peaks.getValue("up"), 1e-9)
        assertEquals(small.repo.snapshot().snapshot.peaks.getValue("shared"),
            large.repo.snapshot().snapshot.peaks.getValue("shared"), 1e-9)
    }

    @Test fun chatsHaveIndependentTimersAndSharedDimensionsDoNotStack() {
        val f = Fixture(); f.after(5); f.repo.human("b", "human2", "hello", false)
        f.after(12)
        assertTrue(f.values("a").getValue("up") > 50)
        assertEquals(50.0, f.values("b").getValue("up"), 0.0)
        assertEquals(50.0, f.values("a").getValue("shared"), 0.0)
        f.after(30); f.repo.human("b", "human3", "again", false)
        f.after(35)
        assertTrue(f.values("a").getValue("up") > f.values("b").getValue("up"))
        assertEquals(f.values("a").getValue("shared"), f.values("b").getValue("shared"), 0.0)
    }

    @Test fun processReopenAndPrivateHidingDoNotFreezeTime() {
        val f = Fixture(); f.after(40)
        val expected = f.values()
        f.clock = f.clock.copy(process = "second", activeMs = 0)
        val reopened = BodyRepository(f.memory) { f.clock }
        val state = reopened.viewSnapshot()
        assertEquals(expected, BodyRules.values(state.snapshot, "a", state.versions.last().definition))
        assertFalse(BodyPresentation.dimensions(state, false, "a").any { it.id == "down" })
        assertTrue(BodyRules.values(state.snapshot, "a", state.versions.last().definition).getValue("down") < 50)
    }

    @Test fun pauseExcludesOfflineIntervalAndResumeStartsFreshGracePeriod() {
        val f = Fixture(); f.after(20); f.repo.setRunning(false)
        val frozen = f.values(); f.after(500); assertEquals(frozen, f.values())
        f.repo.setRunning(true); f.after(509)
        assertTrue(f.values().getValue("up") < frozen.getValue("up")) // natural decay only
        f.after(530); assertTrue(f.values().getValue("up") > frozen.getValue("up"))
    }

    @Test fun duplicateHumanDoesNotResetTimersOrReplayAnything() {
        val f = Fixture(); f.after(20); f.repo.human("a", "human1", "edited", false)
        val untouched = Fixture(); untouched.after(20)
        assertEquals(untouched.values(), f.values())
    }

    @Test fun clockRollbackCannotDoubleApplyAnElapsedInterval() {
        val f = Fixture(); f.after(40); f.repo.refresh(); val values = f.values()
        f.after(20); f.repo.refresh(); assertEquals(values, f.values())
        f.after(40); assertEquals(values, f.values())
        f.after(60); val normal = Fixture(); normal.after(60)
        assertEquals(normal.values().getValue("up"), f.values().getValue("up"), 1e-9)
    }

    @Test fun oldRunningStateMigratesOnceWithoutBackdating() {
        val f = Fixture()
        f.memory.text = BodyRules.json.encodeToString(BodyState.serializer(), f.repo.snapshot().copy(timeline = null))
        f.after(5000)
        val migrated = BodyRepository(f.memory) { f.clock }
        assertEquals(50.0, BodyRules.values(migrated.viewSnapshot().snapshot, "a", migrated.snapshot().versions.last().definition).getValue("up"), 0.0)
        f.after(5040); val second = BodyRepository(f.memory) { f.clock }
        assertTrue(BodyRules.values(second.viewSnapshot().snapshot, "a", second.snapshot().versions.last().definition).getValue("up") > 60)
    }

    @Test fun capEndsDriftAndKeepsTheEarlierPeakWhenValueReturns() {
        val f = Fixture(); f.after(70); val peak = f.values().getValue("up")
        f.after(180); val state = f.repo.viewSnapshot()
        assertTrue(f.values().getValue("up") < peak)
        assertEquals(peak, state.snapshot.sessions.getValue("a").peaks.getValue("up"), 1e-9)
    }
}
