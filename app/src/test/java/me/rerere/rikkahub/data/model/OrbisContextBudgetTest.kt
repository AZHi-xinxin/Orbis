package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisContextBudgetTest {
    private val model = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val finished = LocalDateTime(2026, 9, 8, 12, 30)
    private fun reply(usage: TokenUsage? = TokenUsage(promptTokens = 150000, completionTokens = 300, cachedTokens = 140000)) =
        UIMessage.assistant("synthetic").copy(modelId = model, finishedAt = finished, usage = usage)
    private fun derive(messages: List<UIMessage>, limit: Int? = 1000000, busy: Boolean = false,
        threshold: Int = 1000000, estimate: Long? = null, source: String? = null) =
        deriveOrbisContextBudget(messages, model, limit, busy, threshold, estimate, source)

    @Test fun `completed matching model uses full prompt without subtracting cache`() {
        val result = derive(listOf(reply()))
        assertEquals(OrbisBudgetReason.READY_REFERENCE, result.reason)
        assertEquals(.15, result.inputRatio!!, .000001)
        assertEquals("15%", result.percentLabel)
        assertEquals("2026-09-08T12:30", result.completedAt)
    }
    @Test fun `no assistant is unknown not zero percent`() {
        val result = derive(listOf(UIMessage.user("synthetic")))
        assertEquals(OrbisBudgetReason.NO_ASSISTANT, result.reason)
        assertEquals("—", result.percentLabel)
    }
    @Test fun `generating with old completed usage does not display stale percentage`() {
        val result = derive(listOf(reply()), busy = true)
        assertEquals(OrbisBudgetReason.GENERATING, result.reason)
        assertNull(result.inputRatio)
        assertNotNull(result.usage)
    }
    @Test fun `missing latest usage never falls back to older successful reply`() {
        val result = derive(listOf(reply(), UIMessage.user("next"), reply(null)))
        assertEquals(OrbisBudgetReason.USAGE_MISSING, result.reason)
        assertNull(result.usage)
        assertNull(result.inputRatio)
    }
    @Test fun `zero absent and invalid token fields are never guessed`() {
        assertEquals(OrbisBudgetReason.USAGE_MISSING, derive(listOf(reply(TokenUsage()))).reason)
        listOf(TokenUsage(promptTokens = -1), TokenUsage(promptTokens = 100, cachedTokens = 101),
            TokenUsage(promptTokens = 100, completionTokens = -1), TokenUsage(promptTokens = 100, cachedTokens = -1)).forEach {
            assertEquals(OrbisBudgetReason.USAGE_INVALID, derive(listOf(reply(it))).reason)
        }
    }
    @Test fun `unknown model catalogue limit does not block a user reminder threshold`() {
        listOf(null, 0, -20).forEach {
            val result = derive(listOf(reply()), limit = it)
            assertEquals(OrbisBudgetReason.READY_REFERENCE, result.reason)
            assertNull(result.referenceLimit)
            assertEquals(.15, result.inputRatio!!, .000001)
        }
    }
    @Test fun `model mismatch and missing model identifiers suppress ratio`() {
        assertEquals(OrbisBudgetReason.MODEL_CHANGED,
            derive(listOf(reply().copy(modelId = Uuid.parse("00000000-0000-0000-0000-000000000002")))).reason)
        assertEquals(OrbisBudgetReason.MODEL_UNKNOWN, derive(listOf(reply().copy(modelId = null))).reason)
        assertEquals(OrbisBudgetReason.MODEL_UNKNOWN, deriveOrbisContextBudget(listOf(reply()), null, 1000000, false, 1000000).reason)
    }
    @Test fun `incomplete reply cannot present completed usage`() {
        assertEquals(OrbisBudgetReason.INCOMPLETE, derive(listOf(reply().copy(finishedAt = null))).reason)
    }
    @Test fun `tool continuation is ambiguous even if it has output and finished timestamp`() {
        val tool = UIMessagePart.Tool("synthetic", "workspace_shell", "{}", listOf(UIMessagePart.Text("done")))
        val result = derive(listOf(reply().copy(parts = listOf(tool, UIMessagePart.Text("finished")))))
        assertEquals(OrbisBudgetReason.TOOL_CONTINUATION, result.reason)
        assertNull(result.inputRatio)
        assertNotNull(result.usage)
    }
    @Test fun `chosen branch input is honored without searching other alternatives`() {
        val branchA = derive(listOf(reply(TokenUsage(promptTokens = 250000))))
        val branchB = derive(listOf(reply(null)))
        assertEquals("25%", branchA.percentLabel)
        assertEquals("—", branchB.percentLabel)
    }
    @Test fun `new user input does not get estimated or added to last request`() {
        val result = derive(listOf(reply(), UIMessage.user("large unseen next input")))
        assertEquals("15%", result.percentLabel)
        assertEquals(150000, result.usage!!.promptTokens)
    }
    @Test fun `over reference limit is not clamped into apparently safe one hundred percent`() {
        val result = derive(listOf(reply(TokenUsage(promptTokens = 1500000))))
        assertEquals("150%", result.percentLabel)
        assertEquals(1.5, result.inputRatio!!, 0.0)
    }
    @Test fun `synthetic internal messages never become source evidence`() {
        val result = derive(listOf(reply(), reply(null).copy(isSynthetic = true)))
        assertEquals("15%", result.percentLabel)
    }
    @Test fun `input ratio cannot overflow an Int during percent calculation`() {
        val result = derive(listOf(reply(TokenUsage(promptTokens = Int.MAX_VALUE))), threshold = 1)
        assertEquals("214748364700%", result.percentLabel)
        assertEquals(2147483647.0, result.inputRatio!!, 0.0)
    }
    @Test fun `ring uses human reminder threshold instead of model catalogue maximum`() {
        val result = derive(listOf(reply(TokenUsage(promptTokens = 120000))), limit = 1000000, threshold = 600000)
        assertEquals("600K", result.thresholdLabel)
        assertEquals("20%", result.percentLabel)
        assertEquals(540000L, result.reminderStartsAtTokens)
        assertEquals(1000000, result.referenceLimit)
    }
    @Test fun `zero disables reminder ratio without pretending current usage is zero`() {
        val result = derive(listOf(reply()), threshold = 0)
        assertEquals(OrbisBudgetReason.REMINDERS_DISABLED, result.reason)
        assertEquals("提醒关", result.thresholdLabel)
        assertEquals("—", result.percentLabel)
        assertNull(result.reminderStartsAtTokens)
        assertEquals(150000L, result.currentUsageTokens)
    }
    @Test fun `default does not silently activate reminder`() {
        val result = deriveOrbisContextBudget(listOf(reply()), model, 1000000, false)
        assertEquals(0, result.reminderThresholdTokens)
        assertNull(result.inputRatio)
    }
    @Test fun `ninety percent of 350k starts at 315k not 340k`() {
        val result = derive(listOf(reply()), threshold = 350000)
        assertEquals(315000L, result.reminderStartsAtTokens)
        assertEquals(1L, derive(listOf(reply()), threshold = 1).reminderStartsAtTokens)
        assertEquals(10L, derive(listOf(reply()), threshold = 11).reminderStartsAtTokens)
    }
    @Test fun `fresh post compression estimate supersedes archived provider usage and keeps provenance`() {
        val result = derive(listOf(reply(TokenUsage(promptTokens = 350000))), threshold = 350000,
            estimate = 7000L, source = "压缩后估算，不是供应商实测")
        assertEquals(OrbisBudgetReason.CURRENT_ESTIMATE, result.reason)
        assertEquals("2%", result.percentLabel)
        assertEquals(7000L, result.currentUsageTokens)
        assertEquals(350000, result.usage!!.promptTokens)
        assertEquals("压缩后估算，不是供应商实测", result.currentUsageSource)
    }
    @Test fun `estimate can show a context without provider usage but cannot look precise`() {
        val result = derive(listOf(UIMessage.user("new draft")), threshold = 350000, estimate = 1000L)
        assertEquals(OrbisBudgetReason.CURRENT_ESTIMATE, result.reason)
        assertTrue(result.currentUsageSource!!.contains("估算"))
        assertTrue(result.currentUsageSource!!.contains("不是供应商精确"))
        assertEquals(1000L, result.currentUsageTokens)
    }
    @Test fun `negative estimate is rejected and generating estimate has no percentage`() {
        assertEquals(OrbisBudgetReason.READY_REFERENCE, derive(listOf(reply()), estimate = -1).reason)
        val busy = derive(listOf(reply()), busy = true, estimate = 999L)
        assertEquals(OrbisBudgetReason.GENERATING, busy.reason)
        assertNull(busy.inputRatio)
    }
    @Test fun `threshold normalization and labels remain bounded and readable`() {
        assertEquals(0, derive(listOf(reply()), threshold = -1).reminderThresholdTokens)
        assertEquals(1000000, derive(listOf(reply()), threshold = Int.MAX_VALUE).reminderThresholdTokens)
        assertEquals("1M", formatOrbisTokenCount(1000000))
        assertEquals("350K", formatOrbisTokenCount(350000))
        assertEquals("12.3K", formatOrbisTokenCount(12345))
        assertEquals("999", formatOrbisTokenCount(999))
    }
    @Test fun `pending asynchronous estimate never flashes archived usage as current ratio`() {
        val result = deriveOrbisContextBudget(listOf(reply(TokenUsage(promptTokens = 350000))), model,
            1000000, false, reminderThresholdTokens = 350000, currentEstimatePending = true)
        assertEquals(OrbisBudgetReason.ESTIMATE_PENDING, result.reason)
        assertNull(result.inputRatio)
        assertNull(result.currentUsageTokens)
        assertEquals(350000, result.usage!!.promptTokens)
    }
}
