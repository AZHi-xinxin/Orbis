package me.rerere.rikkahub.data.orbis.boundary

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BoundaryStateTest {
    @Test fun assistantPauseCoversExistingFutureAndOwnerOnlyRequestsAndSurvivesReload() {
        val global = changeBoundary(granted, 0, "original", "pause", "conversation", "", allowRequest = true, until = 120_000, now = 100, scope = "assistant")
        val restored = Json.decodeFromString<AssistantBoundary>(Json.encodeToString(global))
        listOf("original", "other-existing", "new-after-pause", null).forEach {
            assertEquals("assistant", restored.pause(it, 101)!!.scope)
        }
        assertNull(granted.pause("original", 101))
        assertEquals("assistant", restored.events.single().target)
    }
    @Test fun localAndGlobalPausesRestoreIndependently() {
        val local = changeBoundary(granted, 0, "one", "pause", "conversation", "", until = 120_000, now = 100)
        val global = changeBoundary(local, local.revision, "one", "pause", "conversation", "", until = 120_000, now = 101, scope = "assistant")
        val localResumed = changeBoundary(global, global.revision, "two", "resume", "conversation", "", now = 102)
        assertNotNull(localResumed.pause("two", 103))
        val globalResumed = changeBoundary(localResumed, localResumed.revision, "two", "resume", "conversation", "", now = 104, scope = "assistant")
        assertNotNull(globalResumed.pause("one", 105))
        assertNull(globalResumed.pause("two", 105))
        assertEquals("conversation", globalResumed.pause("one", 105)!!.scope)
    }
    @Test fun globalDeadlinePreservesIndependentLocalPauseAndOldDataRemainsLocal() {
        val old = Json.decodeFromString<AssistantBoundary>("""{"grants":["conversation"],"conversations":{"one":{"paused":true,"revision":1}}}""")
        assertNull(old.assistantPause)
        assertNull(old.pause("two", 100))
        val global = changeBoundary(old, old.revision, "one", "pause", "conversation", "", until = 60_100, now = 100, scope = "assistant")
        assertEquals("assistant", global.pause("one", 60_099)!!.scope)
        assertNull(global.pause("two", 60_100))
        assertEquals("conversation", global.pause("one", 60_100)!!.scope)
    }

    @Test fun onlyLatestFiveEventsAreRetainedWithoutRestoringDisabledCapabilities() {
        var state = changeBoundary(AssistantBoundary(grants = BOUNDARY_CATEGORIES), 0, "chat", "disable", "mcp", "garden", now = 1)
        repeat(20) { index -> state = changeBoundary(state, state.revision, "chat", "pause", "conversation", "", until = 120_000, now = index + 2L) }
        assertEquals((17L..21L).toList(), state.events.map { it.at })
        assertEquals(21L, state.revision)
        assertFalse(state.permits("mcp", "garden"))
        assertNotNull(state.pause("chat", 30))
    }

    @Test fun providerHostedToolsObeyChoicesWithoutEditingTheGlobalModel() {
        val model=me.rerere.ai.provider.Model(tools=setOf(me.rerere.ai.provider.BuiltInTools.Search, me.rerere.ai.provider.BuiltInTools.UrlContext))
        val state=AssistantBoundary(grants=setOf("tool"),tools=mapOf("model_builtin_search" to BoundaryChoice(false)))
        assertEquals(setOf(me.rerere.ai.provider.BuiltInTools.UrlContext),state.selectModelTools(model).tools)
        assertEquals(2,model.tools.size)
        assertEquals(model,AssistantBoundary().selectModelTools(model))
    }

    private val granted = AssistantBoundary(grants = BOUNDARY_CATEGORIES)
    @Test fun missingGrantDoesNotChangeExistingBehaviourOrAllowAiToSelfGrant() {
        val state = AssistantBoundary()
        assertTrue(state.permits("model", "a"))
        assertNull(state.pause("c", 100))
        assertThrows(IllegalArgumentException::class.java) {
            changeBoundary(state, 0, "c", "pause", "conversation", "", until = 120_000, now = 100)
        }
    }
    @Test fun pauseIsOwnerAndConversationScopedAndSurvivesSerialization() {
        val a = changeBoundary(granted, 0, "a", "pause", "conversation", "", reason = "休息一下", until = 120_000, now = 100)
        val copy = Json.decodeFromString<AssistantBoundary>(Json.encodeToString(a))
        assertNotNull(copy.pause("a", 101)); assertNull(copy.pause("b", 101))
        assertNull(granted.pause("a", 101))
        assertEquals("休息一下", copy.pause("a", 101)!!.reason)
    }
    @Test fun onlyBoundedExplicitDeadlinesAreAcceptedAtBothEdges() {
        for (scope in listOf("conversation", "assistant")) {
            for (duration in listOf(60_000L, 86_400_000L)) {
                val paused = changeBoundary(granted, 0, "a", "pause", "conversation", "", until = 100 + duration, now = 100, scope = scope)
                assertNotNull(paused.pause("a", 100 + duration - 1))
                assertNull(paused.pause("a", 100 + duration))
            }
            for (deadline in listOf(null, 100L, 60_099L, 86_400_101L, Long.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) {
                    changeBoundary(granted, 0, "a", "pause", "conversation", "", until = deadline, now = 100, scope = scope)
                }
            }
        }
    }
    @Test fun staleOrReplayedWriteCannotRestoreOrOverwriteNewChoice() {
        val a = changeBoundary(granted, 0, "a", "pause", "conversation", "", until = 120_000, now = 100)
        assertThrows(IllegalArgumentException::class.java) {
            changeBoundary(a, 0, "a", "resume", "conversation", "", now = 101)
        }
        assertNotNull(a.pause("a", 101))
    }
    @Test fun connectionsAreDisabledAndHiddenByStableIdAndRestoreTheSameId() {
        for (kind in listOf("model", "mcp", "tool")) {
            val a = changeBoundary(granted, 0, "c", "disable", kind, "one", hidden = true, now = 100)
            assertFalse(a.permits(kind, "one")); assertTrue(a.hidden(kind, "one"))
            assertTrue(a.permits(kind, "two")); assertTrue(granted.permits(kind, "one"))
            val b = changeBoundary(a, a.revision, "c", "enable", kind, "one", now = 101)
            assertTrue(b.permits(kind, "one")); assertFalse(b.hidden(kind, "one"))
        }
    }
    @Test fun alternativeModelAndDecisionAreOneStateTransition() {
        val a = changeBoundary(granted, 0, "c", "disable", "model", "old", alternateModel = "new", now = 100)
        assertEquals("new", a.modelOverride); assertFalse(a.permits("model", "old"))
        assertEquals(1L, a.revision)
        assertThrows(IllegalArgumentException::class.java) {
            changeBoundary(a, a.revision, "c", "disable", "model", "new", alternateModel = "old", now = 101)
        }
    }
    @Test fun disablingTheAiChosenModelDoesNotSilentlyFallBackToTheHumanDefault() {
        val selected = changeBoundary(granted, 0, "c", "disable", "model", "old", alternateModel = "new", now = 100)
        val stopped = changeBoundary(selected, selected.revision, "c", "disable", "model", "new", now = 101)
        assertEquals("new", stopped.modelOverride)
        assertFalse(stopped.permits("model", stopped.modelOverride!!))
    }
    @Test fun boundaryControlIsIndependentFromBusinessTools() {
        assertThrows(IllegalArgumentException::class.java) {
            changeBoundary(granted, 0, "c", "disable", "tool", BOUNDARY_TOOL, now = 100)
        }
        val a = changeBoundary(granted, 0, "c", "disable", "tool", "workspace_shell", now = 100)
        assertTrue(a.permits("tool", BOUNDARY_TOOL))
    }
    @Test fun resumeAuditIdentifiesAiRatherThanHumanReset() {
        val a = changeBoundary(granted, 0, "c", "pause", "conversation", "", allowRequest = true, until = 120_000, now = 100)
        val b = changeBoundary(a, a.revision, "c", "resume", "conversation", "", now = 101)
        assertNull(b.pause("c", 102))
        assertEquals("ai", b.events.last().actor); assertEquals("resume", b.events.last().action)
    }
}
