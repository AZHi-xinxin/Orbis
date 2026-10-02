package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class GenerationUsageInvalidationTest {
    @Test fun frozenInputPreservesTheDurableInvalidationFlagAndHistoricalCounters() = runBlocking {
        val original = UIMessage.assistant("synthetic").copy(
            usage = TokenUsage(promptTokens = 900000), usageContextInvalidated = true)
        val snapshot = GenerationInputSnapshot()
        val prepared = snapshot.input { listOf(original) }.single()
        assertTrue(prepared.usageContextInvalidated); assertEquals(original.usage, prepared.usage)
        assertEquals(original, prepared)
    }

    @Test fun markedProviderSegmentCannotSetRequestFloorButFreshSegmentCan() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.initializeInput { listOf(UIMessage.user("synthetic request")) }
        val stale = UIMessage.assistant("synthetic response").copy(
            usage = TokenUsage(promptTokens = 900000), usageContextInvalidated = true)
        snapshot.appendCompletedResponse(stale, emptyList())
        assertTrue(snapshot.prepareRequest(thresholdTokens = 0, includeCompactionReminder = false).estimatedTokens < 10000)
        val fresh = UIMessage.assistant("synthetic new response").copy(usage = TokenUsage(promptTokens = 500000, completionTokens = 100))
        snapshot.appendCompletedResponse(fresh, emptyList())
        assertTrue(snapshot.prepareRequest(thresholdTokens = 0, includeCompactionReminder = false).estimatedTokens >= 500100)
    }
}
