package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectedUsageEstimateTest {
    private fun alreadyInvalidated(text: String) = UIMessage.assistant(text).copy(usageContextInvalidated = true)

    @Test fun `changing parts resets estimate even when every original anchor is already invalidated`() {
        val old = alreadyInvalidated("retained prose").copy(parts = listOf(
            UIMessagePart.Tool("synthetic-call", "read", "{}", listOf(UIMessagePart.Text("large old result"))),
            UIMessagePart.Text("retained prose"),
        ))
        val source = listOf(old, UIMessage.user("recent human message").copy(usageContextInvalidated = true))
        val projected = listOf(old.copy(parts = listOf(UIMessagePart.Text("retained prose"))), source[1])

        assertTrue(shouldResetProjectedUsageEstimate(source, projected))
        assertEquals(2, source[0].parts.size)
    }

    @Test fun `removing a whole tool only message resets an already invalidated retained tail`() {
        val toolOnly = alreadyInvalidated("").copy(parts = listOf(
            UIMessagePart.Tool("synthetic-call", "read", "{}", listOf(UIMessagePart.Text("old output"))),
        ))
        val tail = alreadyInvalidated("retained final reply")
        assertTrue(shouldResetProjectedUsageEstimate(listOf(toolOnly, tail), listOf(tail)))
    }

    @Test fun `unchanged messages do not discard estimate just because flags were previously set`() {
        val source = listOf(alreadyInvalidated("same input"), alreadyInvalidated("same final reply"))
        assertFalse(shouldResetProjectedUsageEstimate(source, source))
        assertFalse(shouldResetProjectedUsageEstimate(source, source.map { it.copy(parts = it.parts.toList()) }))
    }

    @Test fun `new invalidation resets otherwise identical original parts`() {
        val original = UIMessage.assistant("same prose")
        assertFalse(original.usageContextInvalidated)
        assertTrue(shouldResetProjectedUsageEstimate(listOf(original), listOf(original.copy(usageContextInvalidated = true))))
    }

    @Test fun `ordinary synthetic system prompt alone does not reset a valid history estimate`() {
        val source = listOf(UIMessage.user("human"), UIMessage.assistant("assistant"))
        val projected = listOf(UIMessage.system("synthetic host prompt").copy(isSynthetic = true)) + source
        assertFalse(shouldResetProjectedUsageEstimate(source, projected))
    }

    @Test fun `already invalidated pruning really lowers the first prepared request estimate`() = runBlocking {
        val large = alreadyInvalidated("kept prose").copy(parts = listOf(
            UIMessagePart.Tool("large-call", "read", "{}", listOf(UIMessagePart.Text("x".repeat(100_000)))),
            UIMessagePart.Text("kept prose"),
        ))
        val tail = UIMessage.user("next independent wake").copy(usageContextInvalidated = true)
        val source = listOf(large, tail)
        val projected = listOf(large.copy(parts = listOf(UIMessagePart.Text("kept prose"))), tail)
        val staleEstimate = estimateCurrentContext(source).tokens
        val snapshot = GenerationInputSnapshot()
        snapshot.input { projected }
        val request = snapshot.prepareRequest(
            schemaText = "",
            thresholdTokens = Int.MAX_VALUE,
            includeCompactionReminder = false,
            initialEstimate = staleEstimate.takeUnless { shouldResetProjectedUsageEstimate(source, projected) },
        )

        assertTrue("pruned payload must not survive as a stale estimate floor", request.estimatedTokens < staleEstimate / 10)
        assertEquals(projected, request.messages)
        assertEquals(100_000, ((source[0].getTools().single().output.single()) as UIMessagePart.Text).text.length)
    }
}
