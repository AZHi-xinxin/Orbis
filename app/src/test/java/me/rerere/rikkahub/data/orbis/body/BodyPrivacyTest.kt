package me.rerere.rikkahub.data.orbis.body

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import org.junit.Assert.*
import org.junit.Test

class BodyPrivacyTest {
    private class Memory : ActivityStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }
    private val definition = BodyDefinition(listOf(BodyDimension("energy", "活力"),
        BodyDimension("intensity", "强烈情绪", isPrivate = true)),
        listOf(BodyRule("quiet", mapOf("energy" to 2.0)), BodyRule("intense", mapOf("intensity" to 8.0))))
    @Test fun privateValuesPeaksChartsAndEventLabelsAreAbsentUntilHumanChooses() {
        val memory = Memory(); val repo = BodyRepository(memory) { BodyClock("p", 0, 1000) }
        repo.define(definition, 0, "draft"); repo.activate(1, "activate")
        repo.setRunningFromTool("chat", true, 2, "start"); repo.event("chat", "intense", 0, true, "event")
        repo.sharePrivate("chat", true, repo.snapshot().revision, "share")
        val state = repo.snapshot()
        assertEquals(listOf("energy"), BodyPresentation.metrics(state, "chat", false).map { it.dimension.id })
        assertTrue(BodyPresentation.chart(state, "chat", "intensity", false, 0, 2000).isEmpty())
        assertFalse(BodyPresentation.history(state, "chat", false).any { it.event == "intense" })
        assertTrue(BodyPresentation.history(state, "chat", false).all { "intensity" !in it.values })
        val shown = BodyPresentation.metrics(state, "chat", true).single { it.dimension.id == "intensity" }
        assertEquals(58.0, shown.value, .0); assertEquals(58.0, shown.peak, .0)
        assertEquals(58.0, BodyPresentation.chart(state, "chat", "intensity", true, 0, 2000).last().values["intensity"]!!, .0)
    }
    @Test fun hidingAndReopeningDoesNotPauseEraseOrAlterCalculationsAndSurvivesStorageReopen() {
        val body = Memory(); val display = Memory()
        val repo = BodyRepository(body) { BodyClock("p", 0, 1000) }; val view = BodyDisplayStore(display)
        assertFalse(view.state.value.privateVisible)
        repo.define(definition, 0, "draft"); repo.activate(1, "activate"); repo.setRunningFromTool("chat", true, 2, "start")
        view.setPrivateVisibleFromHuman(true)
        repo.event("chat", "intense", 0, true, "one")
        repo.sharePrivate("chat", true, repo.snapshot().revision, "share")
        val beforeHide = body.text; view.setPrivateVisibleFromHuman(false); assertEquals(beforeHide, body.text)
        repo.event("chat", "intense", 0, true, "two")
        view.setPrivateVisibleFromHuman(true)
        val restored = BodyRepository(body) { BodyClock("p2", 0, 2000) }
        assertTrue(BodyDisplayStore(display).state.value.privateVisible)
        assertEquals(66.0, BodyPresentation.metrics(restored.snapshot(), "chat", true).last().value, .0)
        assertEquals(2, BodyPresentation.history(restored.snapshot(), "chat", true).count { it.event == "intense" })
        assertFalse(body.text!!.contains("privateVisible"))
        assertFalse(display.text!!.contains("intensity"))
    }
    @Test fun privateClassificationCannotBeRemovedByDraftReplacementOrRetiredId() {
        val memory = Memory(); val repo = BodyRepository(memory) { BodyClock("p", 0, 1000) }
        repo.define(definition, 0, "draft")
        val ordinary = definition.copy(dimensions = definition.dimensions.map { it.copy(isPrivate = false) })
        assertThrows(IllegalArgumentException::class.java) { repo.define(ordinary, 1, "unhide") }
        repo.define(BodyDefinition(), 1, "remove")
        val reopened = BodyRepository(memory) { BodyClock("p", 0, 1000) }
        assertThrows(IllegalArgumentException::class.java) { reopened.define(ordinary, 2, "readd") }
        reopened.define(definition, 2, "private-again"); reopened.activate(3, "publish")
        assertThrows(IllegalArgumentException::class.java) { reopened.define(ordinary, 4, "published-unhide") }
    }
    @Test fun oldDefinitionsDefaultPublicButOldEventHiddenGateCannotBeBypassedByDisplayPreference() {
        val old = BodyRules.json.decodeFromString<BodyDimension>("""{"id":"old","label":"旧指标"}""")
        assertFalse(old.isPrivate)
        assertTrue(BodyRules.json.encodeToString(definition).contains("\"private\":true"))
        val state = BodyState(versions = listOf(BodyVersion(1, 0, definition)),
            snapshot = BodySnapshot(sessions = mapOf("chat" to BodySession(visible = false))))
        assertTrue(BodyPresentation.metrics(state, "chat", true).isEmpty())
        assertTrue(BodyPresentation.history(state, "chat", true).isEmpty())
        assertTrue(BodyPresentation.chart(state, "chat", "intensity", true, 0, 2000).isEmpty())
    }
    @Test fun aiPauseModifyAndResumeIsRevisionCheckedAndIdempotent() {
        val memory = Memory(); val repo = BodyRepository(memory) { BodyClock("p", 0, 1000) }
        repo.define(definition, 0, "draft"); repo.activate(1, "activate")
        repo.setRunningFromTool("chat", true, 2, "start")
        assertThrows(IllegalArgumentException::class.java) { repo.define(definition, 3, "unsafe") }
        repo.setRunningFromTool("chat", false, 3, "pause"); val paused = memory.text
        repo.setRunningFromTool("chat", false, 3, "pause"); assertEquals(paused, memory.text)
        repo.define(definition, 4, "edit"); repo.activate(5, "publish")
        assertThrows(IllegalArgumentException::class.java) { repo.setRunningFromTool("chat", true, 5, "stale") }
        repo.setRunningFromTool("chat", true, 6, "continue"); assertTrue(repo.snapshot().running)
    }
}
