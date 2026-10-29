package me.rerere.rikkahub.data.orbis.boundary

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BoundaryDeadlineTest {
    @Test fun legacyPauseUsesRecordedStartAndKeepsOtherChoices() {
        val old = AssistantBoundary(grants = BOUNDARY_CATEGORIES, revision = 10,
            conversations = mapOf("chat" to ConversationBoundary(requestUsed = true, revision = 4)),
            assistantPause = ConversationBoundary(scope = "assistant", revision = 8),
            models = mapOf("model" to BoundaryChoice(false, true)), mcps = mapOf("mcp" to BoundaryChoice(false)),
            tools = mapOf("tool" to BoundaryChoice(false)), modelOverride = "another",
            events = listOf(BoundaryEvent(100, "ai", "pause", "chat"), BoundaryEvent(200, "ai", "pause", "assistant")))
        val restored = old.restoreBoundedPauses(1000)
        assertEquals(100 + BOUNDARY_MAX_PAUSE_MS, restored.conversations.getValue("chat").until)
        assertEquals(200 + BOUNDARY_MAX_PAUSE_MS, restored.assistantPause!!.until)
        assertFalse(restored.conversations.getValue("chat").requestUsed)
        assertEquals(old.models, restored.models); assertEquals(old.mcps, restored.mcps)
        assertEquals(old.tools, restored.tools); assertEquals(old.grants, restored.grants)
        assertEquals(old.modelOverride, restored.modelOverride)
        assertEquals("system", restored.events.last().actor)
        val disk = Json.decodeFromString<AssistantBoundary>(Json.encodeToString(restored))
        assertEquals(restored, disk.restoreBoundedPauses(2000))
        assertNull(restored.pause("chat", 200 + BOUNDARY_MAX_PAUSE_MS))
    }
    @Test fun missingHistoryAnchorsOnceAndNeverExtendsOnRestart() {
        val old = AssistantBoundary(grants = setOf("conversation"),
            conversations = mapOf("chat" to ConversationBoundary()))
        val restored = old.restoreBoundedPauses(10_000)
        assertEquals(10_000 + BOUNDARY_MAX_PAUSE_MS, restored.pause("chat", 10_000)!!.until)
        assertEquals(restored, restored.restoreBoundedPauses(70_000))
    }
    @Test fun boundedPauseKeepsItsDeadlineAndVersionWhileOrphanedClaimIsReleased() {
        val pause = ConversationBoundary(until = 120_000, startedAt = 1, requestUsed = true, revision = 5)
        val old = AssistantBoundary(grants = setOf("conversation"), revision = 6, conversations = mapOf("chat" to pause))
        val restored = old.restoreBoundedPauses(5000)
        assertEquals(pause.copy(requestUsed = false), restored.conversations["chat"])
        assertEquals(7L, restored.revision)
        assertEquals(restored, restored.restoreBoundedPauses(6000))
    }
    @Test fun expiredLegacyPauseDoesNotGetAnotherDay() {
        val old = AssistantBoundary(grants = setOf("conversation"),
            conversations = mapOf("chat" to ConversationBoundary()),
            events = listOf(BoundaryEvent(1, "ai", "pause", "chat")))
        assertNull(old.restoreBoundedPauses(2 * BOUNDARY_MAX_PAUSE_MS).pause("chat", 2 * BOUNDARY_MAX_PAUSE_MS))
    }
    @Test fun countdownRoundsUpAndEndsAtZero() {
        assertEquals("还剩 1 秒", boundaryRemaining(1001, 1000))
        assertEquals("还剩 1 分 0 秒", boundaryRemaining(61_000, 1000))
        assertEquals("还剩 24 小时 0 分 0 秒", boundaryRemaining(BOUNDARY_MAX_PAUSE_MS, 0))
        assertEquals("已到约定恢复时间", boundaryRemaining(1000, 1000))
        assertEquals("已到约定恢复时间", boundaryRemaining(1000, 1001))
    }
}
