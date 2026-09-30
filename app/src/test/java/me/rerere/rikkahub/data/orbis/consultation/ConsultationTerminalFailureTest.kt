package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConsultationTerminalFailureTest {
    private val trigger = UIMessage.user("synthetic input").copy(id = consultationInputId("a".repeat(32)))
    private val tool = UIMessagePart.Tool("synthetic-call", "synthetic_tool", "{\"private\":\"input\"}")
    private val checkpoint = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "owner", "binding", "input",
        state = "UNKNOWN")
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun assess(vararg generated: UIMessage, succeeded: Boolean = true) =
        consultationTerminalAssessment(listOf(trigger) + generated, trigger.id.toString(), succeeded)

    private fun assertFailure(reason: ConsultationTerminalFailure, assessment: ConsultationTerminalAssessment) {
        assertSame(ConsultationTurnResult.Unknown, assessment.result)
        assertEquals(reason, assessment.failure)
    }

    @Test fun incompleteGenerationIsDistinctFromMissingOrEmptyFinalText() {
        assertFailure(ConsultationTerminalFailure.GENERATION_NOT_COMPLETED,
            assess(UIMessage.assistant("synthetic partial body"), succeeded = false))
        assertFailure(ConsultationTerminalFailure.MISSING_TERMINAL_ASSISTANT, assess())
        assertFailure(ConsultationTerminalFailure.MISSING_TERMINAL_ASSISTANT, assess(UIMessage.user("not an assistant")))
        assertFailure(ConsultationTerminalFailure.EMPTY_FINAL_TEXT, assess(UIMessage.assistant(" \n\t")))
        assertFailure(ConsultationTerminalFailure.EMPTY_FINAL_TEXT,
            assess(assistant(UIMessagePart.Reasoning("synthetic private reasoning"))))
    }

    @Test fun unfinishedToolAndExecutedToolTailHaveSeparateReasonsWithoutForwardingPreamble() {
        assertFailure(ConsultationTerminalFailure.UNEXECUTED_TOOL,
            assess(assistant(tool), UIMessage.assistant("synthetic final-looking text")))
        assertFailure(ConsultationTerminalFailure.UNEXECUTED_TOOL,
            assess(assistant(tool.copy(approvalState = ToolApprovalState.Approved))))
        assertFailure(ConsultationTerminalFailure.TOOL_CALL_TAIL,
            assess(assistant(UIMessagePart.Text("synthetic private preamble"),
                tool.copy(output = listOf(UIMessagePart.Text("synthetic private receipt"))))))
    }

    @Test fun exactByteLimitIsAllowedAndOneExtraByteIsDiagnosedWithoutTruncation() {
        val exact = UIMessage.assistant("x".repeat(16384))
        val complete = assess(exact)
        assertEquals(ConsultationTurnResult.Complete(exact.toText(), exact.id.toString()), complete.result)
        assertNull(complete.failure)
        assertFailure(ConsultationTerminalFailure.OVERSIZED_FINAL_TEXT, assess(UIMessage.assistant("x".repeat(16385))))
        assertFailure(ConsultationTerminalFailure.OVERSIZED_FINAL_TEXT, assess(UIMessage.assistant("字".repeat(5462))))
    }

    @Test fun compactionSummaryOrUntrackedOutputCannotMasqueradeAsTerminalAssistant() {
        val summary = assistant(UIMessagePart.Text("synthetic summary", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) }))
        assertFailure(ConsultationTerminalFailure.MISSING_TERMINAL_ASSISTANT, assess(summary))
        val final = UIMessage.assistant("synthetic final")
        assertFailure(ConsultationTerminalFailure.MISSING_TERMINAL_ASSISTANT,
            consultationTerminalAssessment(listOf(final), trigger.id.toString(), true))
        val tracked = consultationTerminalAssessment(listOf(final), trigger.id.toString(), true, setOf(final.id.toString()))
        assertEquals(ConsultationTurnResult.Complete("synthetic final", final.id.toString()), tracked.result)
        assertNull(tracked.failure)
    }

    @Test fun closedReasonCodesSurviveGenericOuterFailureWithoutCopyingExceptionText() {
        val wrapper = ConsultationRuntimeFailure("execution_incomplete_no_automatic_retry")
        for (reason in ConsultationTerminalFailure.entries) {
            assertTrue(reason.code.matches(Regex("[a-z_]{1,80}")))
            val saved = checkpoint.copy(failure = reason.code)
            assertEquals(reason.code, consultationGenerationFailureAfterTerminal(saved, wrapper))
            assertEquals(reason.code, consultationGenerationFailureAfterTerminal(saved, IllegalStateException("synthetic private error")))
        }
        assertEquals(ConsultationTerminalFailure.entries.size, ConsultationTerminalFailure.entries.map { it.code }.toSet().size)
    }

    @Test fun staleOrUnknownFailureStringsAreNotCarriedIntoAnOuterFailure() {
        val generic = "execution_incomplete_no_automatic_retry"
        val error = IllegalStateException("synthetic secret in exception")
        for (saved in listOf(null, "synthetic secret in checkpoint", generic)) {
            assertEquals(generic, consultationGenerationFailureAfterTerminal(checkpoint.copy(failure = saved), error))
        }
        for (state in listOf("PREPARED", "RUNNING", "WAITING_APPROVAL", "COMPLETE", "SUBMITTED")) {
            assertEquals(generic, consultationGenerationFailureAfterTerminal(
                checkpoint.copy(state = state, failure = ConsultationTerminalFailure.EMPTY_FINAL_TEXT.code), error))
        }
    }

    @Test fun specificTransportAndCancellationFailuresStillTakePrecedence() {
        val saved = checkpoint.copy(failure = ConsultationTerminalFailure.GENERATION_NOT_COMPLETED.code)
        val cases = listOf(
            HttpException("synthetic provider response", httpStatus = 400) to "provider_request_failed_no_automatic_retry",
            IOException("synthetic network detail") to "network_interrupted_no_automatic_retry",
            CancellationException("synthetic cancellation detail") to "generation_cancelled_no_automatic_retry",
            IllegalStateException("consultation_delivery_expired") to "generation_deadline_expired_no_automatic_retry",
            IllegalStateException("consultation_binding_changed") to "binding_changed_no_automatic_retry",
        )
        for ((error, expected) in cases) assertEquals(expected, consultationGenerationFailureAfterTerminal(saved, error))
    }

    @Test fun oldCheckpointsDecodeAndNewReasonsUseOnlyTheExistingFailureField() {
        val json = Json { encodeDefaults = true }
        val old = Json.decodeFromString<ConsultationCheckpoint>("""{
            "requestId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sessionId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "phase":"ACTIVE","assistantId":"owner","bindingDigest":"binding","inputDigest":"input","state":"UNKNOWN"
        }""")
        assertNull(old.failure)
        for (reason in ConsultationTerminalFailure.entries) {
            val saved = old.copy(failure = reason.code)
            assertEquals(saved, json.decodeFromString<ConsultationCheckpoint>(json.encodeToString(saved)))
            assertEquals(canStartConsultationGeneration(old), canStartConsultationGeneration(saved))
            assertEquals(canSubmitConsultationCheckpoint(old), canSubmitConsultationCheckpoint(saved))
            assertEquals(canManuallyRetryConsultation(old), canManuallyRetryConsultation(saved))
        }
    }
}
