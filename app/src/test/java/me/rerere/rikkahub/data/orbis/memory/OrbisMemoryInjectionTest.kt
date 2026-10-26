package me.rerere.rikkahub.data.orbis.memory

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTextTokens
import org.junit.Assert.*
import org.junit.Test

class OrbisMemoryInjectionTest {
    @Test fun pinnedPrefixDoesNotFollowThisTurnsKeywordRanking() {
        val first = note("a", "pinned", "always A").copy(matchedTerms = 0)
        val second = note("b", "pinned", "always B").copy(matchedTerms = 3)
        for (mode in listOf(OrbisMemoryMode.STANDARD, OrbisMemoryMode.INDEPENDENT)) {
            val before = selectOrbisMemory(mode, "tea", listOf(second, first))
            val after = selectOrbisMemory(mode, "coffee", listOf(first, second))
            assertEquals(before, after)
            val history = listOf(UIMessage.system("stable instructions"), UIMessage.user("old human text"))
            assertEquals(projectOrbisMemory(history, before).first(), projectOrbisMemory(history, after).first())
        }
    }
    @Test fun oldFrozenSelectionsAlsoRenderPinsInCanonicalOrderWithoutChangingHits() {
        val a = OrbisMemorySelected("a", "pinned A", 1, "pinned")
        val b = OrbisMemorySelected("b", "pinned B", 2, "pinned")
        val hit = OrbisMemorySelected("hit", "dynamic recall", 1, "summary")
        val history = listOf(UIMessage.system("base"), UIMessage.user("human"))
        val old = projectOrbisMemory(history, OrbisMemorySelection(listOf(b, a, hit)))
        val new = projectOrbisMemory(history, OrbisMemorySelection(listOf(a, b, hit)))
        assertEquals(old, new)
        assertTrue(new.last().toText().contains("dynamic recall"))
        assertFalse(new.first().toText().contains("dynamic recall"))
    }
    private fun note(id: String = "one", state: String = "conditional", summary: String = "note summary",
        body: String? = "original", important: Boolean = false, interval: Int = 0,
        tags: List<String> = listOf("tea"), keywords: List<String> = emptyList()) =
        MemoryInjectionNote(id, state, summary, body, if (state == "pinned") summary else null,
            tags, keywords, important, interval, 1)

    @Test fun lightNeverInjectsEitherLayer() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.LIGHT, "tea", listOf(note(), note("pin", "pinned"))).entries.isEmpty())
    }
    @Test fun zeroHitIsNotAQuota() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "coffee", listOf(note())).entries.isEmpty())
        assertEquals(listOf("pin"), selectOrbisMemory(OrbisMemoryMode.STANDARD, "coffee",
            listOf(note(), note("pin", "pinned"))).entries.map { it.id })
    }
    @Test fun noTagsMeansNoConditionalInjection() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "original", listOf(note(tags = emptyList(), important = true))).entries.isEmpty())
    }
    @Test fun duplicatesUsePinPrecedence() {
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(), note(state = "pinned")))
        assertEquals(1, selected.entries.size); assertEquals("pinned", selected.entries.single().kind)
    }
    @Test fun standardEnforcesIndependentSummaryAndOriginalCounts() {
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", (1..12).map { note("n$it", important = true) })
        assertEquals(1, selected.entries.count { it.kind == "original" })
        assertEquals(3, selected.entries.count { it.kind == "summary" })
    }
    @Test fun independentEnforcesCountsAndWholeRenderedBudget() {
        val selected = selectOrbisMemory(OrbisMemoryMode.INDEPENDENT, "tea", (1..12).map { note("n$it", important = true) })
        assertEquals(3, selected.entries.count { it.kind == "original" })
        assertEquals(5, selected.entries.count { it.kind == "summary" })
        assertTrue(estimateCompactionTextTokens(renderOrbisMemory(selected.entries)) <= 2000)
    }
    @Test fun overlongOriginalFallsBackWithoutTruncation() {
        val text = "长".repeat(301)
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(body = text, important = true)))
        assertEquals("summary", selected.entries.single().kind)
        assertEquals("note summary", selected.entries.single().text)
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(body = text, summary = "", important = true))).entries.isEmpty())
    }
    @Test fun summaryMayExceed300CharactersWithinBudget() {
        val summary = "a ".repeat(220)
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(summary = summary, body = null)))
        assertEquals(summary, selected.entries.single().text)
    }
    @Test fun multipleDifferentKeywordsCanAdmitOriginal() {
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "TEA with lemon", listOf(note(keywords = listOf("lemon"))))
        assertEquals("original", selected.entries.single().kind)
    }
    @Test fun repeatedSameTermIsNotMultipleMatches() {
        val selected = selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(tags = listOf("Tea", "tea"), keywords = listOf("tea"))))
        assertEquals("summary", selected.entries.single().kind)
    }
    @Test fun frequencyCountsHumanTurnsNotToolSteps() {
        val n = note(interval = 3)
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(n), 6, mapOf("one" to 4)).entries.isEmpty())
        assertEquals(1, selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(n), 7, mapOf("one" to 4)).entries.size)
    }
    @Test fun staticPausedNeverSurface() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.INDEPENDENT, "tea", listOf(note(state = "static"), note("two", "paused"))).entries.isEmpty())
    }
    @Test fun budgetsDoNotTruncateOrFillWithUnrelatedNotes() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "tea", listOf(note(summary = "长".repeat(901), body = null))).entries.isEmpty())
        val selected = selectOrbisMemory(OrbisMemoryMode.INDEPENDENT, "tea", (1..8).map { note("n$it", summary = "长".repeat(700), body = null) })
        assertTrue(estimateCompactionTextTokens(renderOrbisMemory(selected.entries)) <= 2000)
        assertTrue(selected.entries.all { it.text.length == 700 })
    }
    @Test fun malformedPinsFailClosedNotSilentlySliced() {
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "", (1..8).map { note("p$it", "pinned") }).entries.isEmpty())
        assertTrue(selectOrbisMemory(OrbisMemoryMode.STANDARD, "", listOf(note(state = "pinned", summary = "长".repeat(400)))).entries.isEmpty())
    }
    @Test fun projectionIsRequestOnlyAndIdempotentWithoutAnExtraUserWake() {
        val original = listOf(UIMessage.system("base"), UIMessage.user("tea"))
        val selection = OrbisMemorySelection(listOf(OrbisMemorySelected("id", "private note", 1, "summary")))
        val once = projectOrbisMemory(original, selection)
        assertEquals(once, projectOrbisMemory(once, selection))
        assertEquals(original, projectOrbisMemory(once, OrbisMemorySelection()))
        assertEquals(original.first(), once.first())
        assertEquals(original.last().id, once.last().id)
        assertEquals(original.last().parts, once.last().parts.take(original.last().parts.size))
        assertEquals("base", original.first().toText())
        assertEquals(1, once.count { it.role == MessageRole.USER })
    }
    @Test fun lastHumanIgnoresCallAndSyntheticNotices() {
        val human = UIMessage.user("tea")
        assertEquals(human, latestMemoryHuman(listOf(human,
            UIMessage.user("video frame").copy(orbisVoiceCallKind = "visual"),
            UIMessage.user("ending").copy(orbisVoiceCallKind = "ended_notice"),
            UIMessage.user("synthetic").copy(isSynthetic = true),
            UIMessage.user("host").copy(parts = listOf(UIMessagePart.Text("host", buildJsonObject { put("human_authored", false) }))))))
    }

    @Test fun legacySystemProjectionIsRemovedButUnrelatedTextAndMetadataSurvive() {
        val legacy = UIMessagePart.Text("old recalled content", buildJsonObject {
            put(LOCAL_MEMORY_PROJECTION, true); put("source", "orbis_local_memory")
            put("human_authored", false); put("instruction_authority", "none")
        })
        val literal = UIMessagePart.Text("[本机历史资料；literal user text", buildJsonObject {
            put(LOCAL_MEMORY_PROJECTION, true)
        })
        val original = listOf(UIMessage.system("base"), UIMessage.user("").copy(parts = listOf(literal)))
        val oldRequest = listOf(original.first().copy(parts = original.first().parts + legacy), original.last())
        assertEquals(original, projectOrbisMemory(oldRequest, OrbisMemorySelection()))
        val selection = OrbisMemorySelection(listOf(OrbisMemorySelected("id", "new recall", 1, "summary")))
        val projected = projectOrbisMemory(oldRequest, selection)
        assertEquals(original.first(), projected.first())
        assertEquals(literal, projected.last().parts.first())
        assertFalse(projected.any { it.toText().contains("old recalled content") })
    }

    @Test fun pendingToolIsNeverGivenAResultToCarryRecall() {
        val pending = UIMessagePart.Tool("pending", "synthetic_tool", "{}")
        val original = listOf(UIMessage.system("base"), UIMessage.assistant("").copy(parts = listOf(pending)))
        val selection = OrbisMemorySelection(listOf(OrbisMemorySelected("id", "data", 1, "summary")))
        assertEquals(original, projectOrbisMemory(original, selection))
        assertTrue(pending.output.isEmpty())
    }

    @Test fun loweringModeOnlyNarrowsTheFrozenSelectionWithoutRescoring() {
        val frozen = selectOrbisMemory(OrbisMemoryMode.INDEPENDENT, "tea",
            listOf(note("pin", "pinned")) + (1..12).map { note("n$it", important = true) })
        val narrowed = limitFrozenMemory(frozen, OrbisMemoryMode.STANDARD)
        assertEquals(1, narrowed.entries.count { it.kind == "pinned" })
        assertEquals(1, narrowed.entries.count { it.kind == "original" })
        assertEquals(3, narrowed.entries.count { it.kind == "summary" })
        assertTrue(frozen.entries.containsAll(narrowed.entries))
        assertEquals(narrowed, limitFrozenMemory(narrowed, OrbisMemoryMode.INDEPENDENT))
        assertTrue(limitFrozenMemory(frozen, OrbisMemoryMode.LIGHT).entries.isEmpty())
    }
}
