package me.rerere.rikkahub.data.orbis.consultation

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class ConsultationManualRetryPolicyTest {
    private val failed = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "own-assistant", "binding", "input",
        state = "UNKNOWN", failure = "execution_incomplete_no_automatic_retry")

    @Test fun legacyUnknownWithoutToolsOrSubmissionCanBeExplicitlyRetried() {
        assertTrue(canManuallyRetryConsultation(failed))
        assertTrue(canManuallyRetryConsultation(failed.copy(state = "BUSY")))
        assertFalse(canStartConsultationGeneration(failed))
    }
    @Test fun runningCompletedSubmittedAndArchiveNeverQualify() {
        for (state in listOf("RUNNING", "PREPARED", "COMPLETE", "SUBMITTED"))
            assertFalse(canManuallyRetryConsultation(failed.copy(state = state)))
        assertFalse(canManuallyRetryConsultation(failed.copy(phase = "ARCHIVING")))
        assertFalse(canManuallyRetryConsultation(failed.copy(submitAttempts = 1)))
        assertFalse(canManuallyRetryConsultation(failed.copy(toolInFlight = "tool-id")))
        assertFalse(canManuallyRetryConsultation(failed.copy(finalText = "completed reply")))
    }
    @Test fun anyToolEvenCompletedOrReadOnlyRequiresSeparateReview() {
        val withTool = failed.copy(messages = listOf(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool(toolCallId = "tool-id", toolName = "get_current_time", input = "{}")
        ))))
        assertFalse(canManuallyRetryConsultation(withTool))
    }
    @Test fun outputBudgetDefaultsRespectExplicitUserAndServerCaps() {
        assertEquals(16384, consultationOutputBudget(null, "ACTIVE", 32768))
        assertEquals(8192, consultationOutputBudget(null, "ARCHIVING", 32768))
        assertEquals(2048, consultationOutputBudget(2048, "ACTIVE", 32768))
        assertEquals(32768, consultationOutputBudget(65536, "ACTIVE", 32768))
        assertEquals(1024, consultationOutputBudget(null, "ACTIVE", 1024))
        assertEquals(16384, consultationOutputBudget(0, "ACTIVE", null))
    }
    @Suppress("DEPRECATION")
    @Test fun legacyAndProviderToolsCannotSlipThroughRetryGuard() {
        val parts = listOf(
            UIMessagePart.ToolCall("old-tool", "legacy", "{}"),
            UIMessagePart.ToolResult("old-tool", "legacy", kotlinx.serialization.json.JsonNull, kotlinx.serialization.json.JsonNull),
            UIMessagePart.Search,
        )
        for (part in parts) assertFalse(canManuallyRetryConsultation(failed.copy(
            messages = listOf(UIMessage.assistant("").copy(parts = listOf(part)))
        )))
    }
    @Test fun diagnosticCodesNeverIncludeProviderBodyOrPrivateExceptionText() {
        assertEquals("provider_request_failed_no_automatic_retry", consultationGenerationFailureCode(HttpException("secret-provider-body", httpStatus = 400)))
        assertEquals("execution_incomplete_no_automatic_retry", consultationGenerationFailureCode(IllegalStateException("private prompt")))
        assertEquals("empty_or_oversized_final_no_automatic_retry", consultationGenerationFailureCode(IllegalStateException("consultation_empty_or_large_reply")))
    }
}
