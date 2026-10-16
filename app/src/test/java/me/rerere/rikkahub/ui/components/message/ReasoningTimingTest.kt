package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

class ReasoningTimingTest {
    private val start = Instant.parse("2026-10-14T00:00:00Z")
    private val part = UIMessagePart.Reasoning(reasoning = "test", createdAt = start, finishedAt = null)
    @Test fun oldUnfinishedReasoningNeverCountsTime() {
        assertNull(reasoningDuration(part, false, start + 1088.seconds))
        assertNull(reasoningDuration(part, false, start + 2088.seconds))
    }
    @Test fun activeReasoningTicksButStopsWithGeneration() {
        assertEquals(5.seconds, reasoningDuration(part, true, start + 5.seconds))
        assertNull(reasoningDuration(part, false, start + 6.seconds))
    }
    @Test fun finishedTimeWinsAndClockSkewCannotShowNegative() {
        val ended = part.copy(finishedAt = start + 3.seconds)
        assertEquals(3.seconds, reasoningDuration(ended, true, start + 1088.seconds))
        assertEquals(3.seconds, reasoningDuration(ended, false, start + 2088.seconds))
        assertEquals(0.seconds, reasoningDuration(part, true, start - 5.seconds))
    }
}
