package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import me.rerere.rikkahub.ui.pages.chat.conversationSizeInfo
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisEditedUsageBudgetTest {
    private val model = Uuid.random()
    private fun reply() = UIMessage.assistant("synthetic historical reply").copy(modelId = model,
        finishedAt = LocalDateTime(2026, 10, 1, 12, 0),
        usage = TokenUsage(promptTokens = 900000, completionTokens = 200, cachedTokens = 850000),
        usageContextInvalidated = true)

    @Test fun historicalUsageRemainsPresentableWithoutBecomingBudgetOrSizeAnchor() {
        val message = reply()
        val budget = deriveOrbisContextBudget(listOf(message), model, 1000000, false, 1000000)
        assertEquals(OrbisBudgetReason.HISTORY_EDITED, budget.reason)
        assertEquals(message.usage, budget.usage); assertNull(budget.inputRatio)
        assertNotNull(presentMessageUsage(message.usage, 1200))
        val estimate = estimateCurrentContext(listOf(message), model)
        assertTrue(estimate.tokens < 10000); assertTrue(estimate.basis.startsWith("estimated_text"))
        assertEquals(0, conversationSizeInfo(Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode()))).lastAssistantInputTokens)
    }

    @Test fun branchSwitchAndShorterRetryPrefixCannotReviveAnOldUsageAnchor() {
        val c = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("remove me").toMessageNode(),
            MessageNode(messages = listOf(reply().copy(usageContextInvalidated = false), reply().copy(usageContextInvalidated = false)), selectIndex = 1),
            reply().copy(usageContextInvalidated = false).toMessageNode()))
        val p = prepareOrbisMessageBatch(c, setOf(c.messageNodes.first().id), OrbisMessageBatchOperation.DELETE)
        val branch = p.replacementNodes.first()
        branch.messages.forEach { message ->
            assertNotNull(message.usage); assertTrue(message.usageContextInvalidated)
            assertTrue(estimateCurrentContext(listOf(message), model).tokens < 10000)
        }
        assertEquals(c.messageNodes[1].messages.map { it.usage }, branch.messages.map { it.usage })
    }

    @Test fun aFreshProviderReplyRestoresBudgetButAnOlderValidReplyIsNotBorrowed() {
        val old = reply(); val fresh = old.copy(id = Uuid.random(), usageContextInvalidated = false,
            usage = TokenUsage(promptTokens = 120, completionTokens = 20))
        val budget = deriveOrbisContextBudget(listOf(old, fresh), model, 1000000, false, 1000000)
        assertEquals(OrbisBudgetReason.READY_REFERENCE, budget.reason)
        assertEquals(140L, estimateCurrentContext(listOf(old, fresh), model).tokens)
        assertEquals(OrbisBudgetReason.HISTORY_EDITED,
            deriveOrbisContextBudget(listOf(fresh, old), model, 1000000, false, 1000000).reason)
    }
}
