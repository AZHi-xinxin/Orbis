package me.rerere.rikkahub.data.orbis.body

import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import org.junit.Assert.*
import org.junit.Test

class BodyAutoTriggerTest {
    private class Memory : ActivityStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }
    private val definition = BodyDefinition(
        dimensions = listOf(BodyDimension("focus", "专注", baseline = 20.0, maxDelta = 20.0),
            BodyDimension("calm", "平静", baseline = 40.0, maxDelta = 20.0, isPrivate = true)),
        rules = listOf(BodyRule("engage", mapOf("focus" to 8.0), listOf("想法", "目标", "计划"), listOf("新思路")),
            BodyRule("settle", mapOf("focus" to -8.0, "calm" to 8.0)),
            BodyRule("rest", mapOf("calm" to 2.0), listOf("休息"))),
        triggerMode = "auto",
        cooling = BodyCooling(keywords = listOf("喝杯茶"), synonyms = listOf("歇一歇"), event = "settle", blockedEvents = listOf("engage")))
    private class Fixture {
        val memory = Memory(); var clock = BodyClock("p", 0, 1_000_000)
        val repo = BodyRepository(memory) { clock }
    }
    private fun prepare(def: BodyDefinition = definition) = Fixture().also {
        it.repo.define(def, 0, "draft"); it.repo.activate(1, "publish"); it.repo.setRunningFromTool("chat", true, 2, "start")
    }
    private fun value(f: Fixture, id: String) = BodyRules.values(f.repo.snapshot().snapshot, "chat", f.repo.snapshot().versions.last().definition).getValue(id)
    @Test fun systemMatchesThreeKeywordsConcentrationAndDimensionClampWithoutAIEvent() {
        val f = prepare(); f.repo.human("chat", "one", "想法、目标、计划", false)
        assertEquals(40.0, value(f, "focus"), .0) // 8 * 3 capped at maxDelta 20.
        val log = f.repo.snapshot().logs.single { it.event == "engage" }
        assertEquals(3, log.strength); assertEquals(3.0, log.concentration!!, .0)
        assertThrows(IllegalArgumentException::class.java) { f.repo.event("chat", "engage", 3, true, "manual") }
        assertThrows(IllegalArgumentException::class.java) { f.repo.undo("chat", f.repo.snapshot().revision, "undo") }
    }
    @Test fun sameSentenceDifferentMessageIdsAndRepeatedSentencesOnlyCountOnceAcrossReopen() {
        val f = prepare(); f.repo.human("chat", "one", "想法。想法", false)
        assertEquals(28.0, value(f, "focus"), .0)
        val restored = BodyRepository(f.memory) { f.clock }
        restored.human("chat", "two", "想法", false)
        assertEquals(1, restored.snapshot().logs.count { it.event == "engage" })
        restored.human("chat", "two", "编辑后的计划", false)
        assertEquals(1, restored.snapshot().logs.count { it.event == "engage" })
    }
    @Test fun eventRateLimitIsDurableAndExpiresAfterWindowWithoutErasingLogs() {
        val f = prepare()
        repeat(4) { f.repo.human("chat", "m$it", "第${it}个想法", false) }
        assertEquals(3, f.repo.snapshot().logs.count { it.event == "engage" })
        f.clock = f.clock.copy(wallMs = f.clock.wallMs + BodyAutoTrigger.windowMs(definition) + 1)
        f.repo.human("chat", "after", "另一个想法", false)
        assertEquals(4, f.repo.snapshot().logs.count { it.event == "engage" })
    }
    @Test fun synonymsMatchLocallyAndOverlappingAliasesDoNotMultiplyConcentration() {
        val f = prepare(); f.repo.human("chat", "one", "一个新思路", false)
        assertEquals(28.0, value(f, "focus"), .0)
        val rule = BodyRule("test", mapOf("focus" to 1.0), listOf("思路"), listOf("新思路"))
        assertEquals(1, BodyAutoTrigger.portions("新思路", rule))
    }
    @Test fun eachAssistantCanDefineConcentrationWindowAndRateInsteadOfFixedPersonalNumbers() {
        val slow = prepare(definition.copy(autoPolicy = BodyAutoPolicy(.5, 1, 1)))
        slow.repo.human("chat", "one", "想法计划目标", false)
        assertEquals(24.0, value(slow, "focus"), .0)
        slow.repo.human("chat", "two", "新的想法", false)
        assertEquals(1, slow.repo.snapshot().logs.count { it.event == "engage" })
        slow.clock = slow.clock.copy(wallMs = slow.clock.wallMs + 60_001)
        slow.repo.human("chat", "three", "更多想法", false)
        assertEquals(2, slow.repo.snapshot().logs.count { it.event == "engage" })
        val other = definition.copy(autoPolicy = BodyAutoPolicy(5.0, 20, 6))
        val result = BodyAutoTrigger.evaluate(other, BodyAutoState(), "chat", "想法目标计划想法目标", 1000, true)
        assertEquals(5.0, result.events.single().concentration, .0)
        for (policy in listOf(BodyAutoPolicy(Double.NaN), BodyAutoPolicy(windowMinutes = 0), BodyAutoPolicy(maxEventsPerWindow = 61))) {
            assertThrows(IllegalArgumentException::class.java) { BodyRules.validate(definition.copy(autoPolicy = policy)) }
        }
    }
    @Test fun coolingVocabularyAndAffectedEventsAreConfiguredNotHardcodedOrGloballyNegative() {
        val f = prepare(); f.repo.human("chat", "one", "停", false)
        assertTrue(f.repo.snapshot().running); assertEquals(0L, f.repo.snapshot().auto.cooldownUntil)
        f.repo.human("chat", "two", "喝杯茶，换个想法", false)
        assertEquals(12.0, value(f, "focus"), .0); assertEquals(48.0, value(f, "calm"), .0)
        assertTrue(f.repo.snapshot().running) // No universal pause.
        f.repo.human("chat", "three", "另一个计划，休息", false)
        assertEquals(12.0, value(f, "focus"), .0); assertEquals(50.0, value(f, "calm"), .0)
        assertEquals(0, f.repo.snapshot().logs.count { it.event == "engage" })
        f.clock = f.clock.copy(wallMs = f.clock.wallMs + 5 * 60_000L + 1)
        f.repo.human("chat", "four", "新的目标", false)
        val beforeEvent = 20 - 8 * kotlin.math.exp(-kotlin.math.ln(2.0) * 300001 / (30 * 60000.0))
        assertEquals(beforeEvent + 8, value(f, "focus"), .000001)
    }
    @Test fun noDefaultCoolingWordsAndAnotherAssistantCanUseDifferentResponse() {
        val f = prepare(definition.copy(cooling = BodyCooling()))
        f.repo.human("chat", "one", "喝杯茶，停，歇一歇", false)
        assertEquals(40.0, value(f, "calm"), .0); assertEquals(0L, f.repo.snapshot().auto.cooldownUntil)
        val second = prepare(definition.copy(cooling = BodyCooling(keywords = listOf("切换频道"), event = "rest", cooldownMinutes = 2)))
        second.repo.human("chat", "one", "切换频道", false)
        assertEquals(42.0, value(second, "calm"), .0)
    }
    @Test fun configuredCoolingPauseCanOnlyResumeAfterConfiguredCooldownAndNoCatchup() {
        val f = prepare(definition.copy(cooling = definition.cooling.copy(pauseAuto = true, cooldownMinutes = 1)))
        f.repo.human("chat", "one", "喝杯茶", false)
        assertFalse(f.repo.snapshot().running)
        assertThrows(IllegalArgumentException::class.java) { f.repo.setRunningFromTool("chat", true, f.repo.snapshot().revision, "early") }
        f.repo.human("chat", "paused", "目标计划想法", false)
        f.clock = f.clock.copy(wallMs = f.clock.wallMs + 60_001)
        f.repo.setRunningFromTool("chat", true, f.repo.snapshot().revision, "resume")
        f.repo.human("chat", "paused", "目标计划想法", false)
        assertTrue(f.repo.snapshot().running); assertEquals(12.0, value(f, "focus"), .0)
    }
    @Test fun changingModePreservesStateHistoryAndManualCompatibility() {
        val f = prepare(); f.repo.human("chat", "one", "目标", false)
        f.repo.setRunningFromTool("chat", false, f.repo.snapshot().revision, "pause")
        val before = value(f, "focus"); val history = f.repo.snapshot().logs
        f.repo.define(definition.copy(triggerMode = "manual"), f.repo.snapshot().revision, "manual-def")
        f.repo.activate(f.repo.snapshot().revision, "publish2");f.repo.setRunningFromTool("chat", true, f.repo.snapshot().revision, "resume")
        assertEquals(before, value(f, "focus"), .0); assertEquals(history, f.repo.snapshot().logs.take(history.size))
        f.repo.event("chat", "engage", 0, true, "manual-event")
        assertEquals(before + 8, value(f, "focus"), .0)
    }
    @Test fun privateCalculationContinuesWhileAiAndHumanVisibilityAreIndependent() {
        val f = prepare();f.repo.human("chat", "one", "休息", false)
        val before = f.repo.snapshot().snapshot.sessions.getValue("chat").values
        assertFalse(BodyPresentation.metrics(f.repo.snapshot(), "chat", true).any { it.isPrivate })
        f.repo.sharePrivate("chat", true, f.repo.snapshot().revision, "share")
        assertEquals(before, f.repo.snapshot().snapshot.sessions.getValue("chat").values)
        assertFalse(BodyPresentation.metrics(f.repo.snapshot(), "chat", false).any { it.isPrivate })
        assertTrue(BodyPresentation.metrics(f.repo.snapshot(), "chat", true).any { it.isPrivate })
        f.repo.sharePrivate("chat", false, f.repo.snapshot().revision, "hide")
        f.repo.human("chat", "two", "先休息一会", false)
        assertEquals(44.0, value(f, "calm"), .0)
        assertFalse(BodyPresentation.metrics(f.repo.snapshot(), "chat", true).any { it.isPrivate })
    }
}
