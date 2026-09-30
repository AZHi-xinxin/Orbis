package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class ClaudeMeteredRequestTest {
    @Test fun `zero continuation budget sends exactly one request even on pause turn`() = runBlocking {
        val params = TextGenerationParams(Model(modelId = "synthetic-claude"), maxAutomaticContinuations = 0)
        var calls = 0
        val result = generateClaudeWithPauseTurn(listOf(UIMessage.user("game")), params.model,
            params.maxAutomaticContinuations) {
            calls++
            TextGenerationResult("synthetic", "synthetic-claude", UIMessage.assistant("{\"cell\":1}"), "pause_turn")
        }
        assertEquals(1, calls)
        assertEquals("pause_turn", result.finishReason)
    }

    @Test fun `normal chat retains its existing five continuation default`() = runBlocking {
        val params = TextGenerationParams(Model(modelId = "synthetic-claude"))
        assertEquals(5, params.maxAutomaticContinuations)
        var calls = 0
        generateClaudeWithPauseTurn(listOf(UIMessage.user("normal")), params.model, params.maxAutomaticContinuations) {
            calls++
            TextGenerationResult("synthetic", "synthetic-claude", UIMessage.assistant("text"), "pause_turn")
        }
        assertEquals(6, calls)
    }

    @Test fun `invalid continuation budgets are rejected at parameter boundary`() {
        for (limit in listOf(-1, 6)) assertThrows(IllegalArgumentException::class.java) {
            TextGenerationParams(Model(), maxAutomaticContinuations = limit)
        }
    }
}
