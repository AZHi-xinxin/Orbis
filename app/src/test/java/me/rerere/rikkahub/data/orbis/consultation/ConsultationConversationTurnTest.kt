package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.createGenerationTerminalEvidence
import org.junit.Assert.*
import org.junit.Test

class ConsultationConversationTurnTest {
    private val trigger = UIMessage.user("synthetic peer body").copy(id = consultationInputId("a".repeat(32)), isSynthetic = true)
    private val tool = UIMessagePart.Tool("call-1", "synthetic_read", "{}")
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun result(vararg generated: UIMessage, succeeded: Boolean = true) =
        consultationTerminalResult(listOf(trigger) + generated, trigger.id.toString(), succeeded)

    @Test fun stableConversationDoesNotDependOnMainWindowOrRequestAndSubjectsAreIsolated() {
        val sid = "a".repeat(32)
        assertEquals(consultationConversationId(sid, "one"), consultationConversationId(sid, "one"))
        assertNotEquals(consultationConversationId(sid, "one"), consultationConversationId(sid, "two"))
        assertNotEquals(consultationInputId("a".repeat(32)), consultationInputId("b".repeat(32)))
    }

    @Test fun onlyFinalTextLeavesTheDeviceNotReasoningOrToolCommentary() {
        val action = assistant(UIMessagePart.Text("private tool preamble"),
            tool.copy(output = listOf(UIMessagePart.Text("private tool output"))))
        val final = assistant(UIMessagePart.Reasoning("private reasoning"), UIMessagePart.Text("final body"))
        assertEquals(ConsultationTurnResult.Complete("final body", final.id.toString()), result(action, final))
    }

    @Test fun pendingApprovalIsNotACompletedBodyEvenWhenLoopFinishedNormally() {
        assertEquals(ConsultationTurnResult.WaitingApproval,
            result(assistant(UIMessagePart.Text("I will execute"), tool.copy(approvalState = ToolApprovalState.Pending))))
    }

    @Test fun toolOnlyOrUncertainToolNeverBecomesTransportableText() {
        assertEquals(ConsultationTurnResult.Unknown, result(assistant(tool)))
        assertEquals(ConsultationTurnResult.Unknown, result(assistant(tool), UIMessage.assistant("not safe yet")))
        assertEquals(ConsultationTurnResult.Unknown, result(assistant(UIMessagePart.Text("preamble"),
            tool.copy(output = listOf(UIMessagePart.Text("receipt"))))))
    }

    @Test fun failureDoesNotForwardPartialBodyAndReasoningAloneIsNotABody() {
        assertEquals(ConsultationTurnResult.Unknown, result(UIMessage.assistant("partial"), succeeded = false))
        assertEquals(ConsultationTurnResult.Unknown, result(assistant(UIMessagePart.Reasoning("thinking"))))
    }

    @Test fun oldAssistantTextAndOversizedTextAreNotForwarded() {
        assertEquals(ConsultationTurnResult.Unknown, consultationTerminalResult(
            listOf(UIMessage.assistant("old"), trigger), trigger.id.toString(), true))
        assertEquals(ConsultationTurnResult.Unknown, result(UIMessage.assistant("字".repeat(5462))))
        val maximum = UIMessage.assistant("a".repeat(16384))
        assertTrue(result(maximum) is ConsultationTurnResult.Complete)
    }

    @Test fun terminalDiagnosticsKeepExistingResultObjectsAndApprovalPrecedence() {
        val pending = assistant(tool.copy(approvalState = ToolApprovalState.Pending))
        val unexecuted = assistant(tool)
        fun assessment(succeeded: Boolean) = consultationTerminalAssessment(
            listOf(trigger, pending, unexecuted), trigger.id.toString(), succeeded)
        assertSame(ConsultationTurnResult.WaitingApproval, assessment(true).result)
        assertNull(assessment(true).failure)
        assertSame(ConsultationTurnResult.Unknown, assessment(false).result)
        assertEquals(ConsultationTerminalFailure.GENERATION_NOT_COMPLETED, assessment(false).failure)
        assertSame(result(pending, unexecuted), assessment(true).result)
    }

    @Test fun terminalDiagnosticsUseUtf8BytesIncludingTextPartSeparators() {
        val exact = assistant(UIMessagePart.Text("字".repeat(5461)), UIMessagePart.Text(""))
        val oversized = assistant(UIMessagePart.Text("字".repeat(5461)), UIMessagePart.Text("a"))
        assertTrue(result(exact) is ConsultationTurnResult.Complete) // 16,383 + one newline.
        val assessment = consultationTerminalAssessment(listOf(trigger, oversized), trigger.id.toString(), true)
        assertSame(ConsultationTurnResult.Unknown, assessment.result)
        assertEquals(ConsultationTerminalFailure.OVERSIZED_FINAL_TEXT, assessment.failure)
        assertSame(result(oversized), assessment.result)
    }

    @Test fun mergedExecutedToolAndFinalStepRequireFreshTerminalEvidence() {
        val raw = assistant(UIMessagePart.Reasoning("private final thought"), UIMessagePart.Text("final body"))
        val merged = assistant(UIMessagePart.Text("private preamble"),
            tool.copy(output = listOf(UIMessagePart.Text("private tool receipt"))),
            UIMessagePart.Reasoning("private final thought"), UIMessagePart.Text("final body"))
        val proof = requireNotNull(createGenerationTerminalEvidence(raw, raw, merged, 7))
        assertEquals(ConsultationTurnResult.Unknown, result(merged))
        assertEquals(ConsultationTurnResult.Complete("final body", merged.id.toString()),
            consultationTerminalResult(listOf(trigger, merged), trigger.id.toString(), true,
                terminalEvidence = proof, contextEpoch = 7, requireTerminalEvidence = true))
    }

    @Test fun productionMissingEvidenceCannotTreatStepLimitOrToolPostscriptAsCompletion() {
        val postscript = assistant(UIMessagePart.Text("private preamble"),
            tool.copy(output = listOf(UIMessagePart.Text("receipt"))), UIMessagePart.Text("private postscript"))
        for (message in listOf(postscript, UIMessage.assistant("apparently final"))) {
            val assessment = consultationTerminalAssessment(listOf(trigger, message), trigger.id.toString(), true,
                contextEpoch = 7, requireTerminalEvidence = true)
            assertSame(ConsultationTurnResult.Unknown, assessment.result)
            assertEquals(ConsultationTerminalFailure.TERMINAL_RESPONSE_UNVERIFIED, assessment.failure)
        }
    }

    @Test fun terminalEvidenceCannotBypassFailureApprovalOrAnEarlierUnexecutedTool() {
        val final = UIMessage.assistant("final body")
        val proof = requireNotNull(createGenerationTerminalEvidence(final, final, final, 7))
        fun check(messages: List<UIMessage>, succeeded: Boolean = true) = consultationTerminalResult(
            listOf(trigger) + messages, trigger.id.toString(), succeeded,
            terminalEvidence = proof, contextEpoch = 7, requireTerminalEvidence = true)
        assertSame(ConsultationTurnResult.Unknown, check(listOf(final), succeeded = false))
        assertSame(ConsultationTurnResult.WaitingApproval,
            check(listOf(assistant(tool.copy(approvalState = ToolApprovalState.Pending)), final)))
        assertSame(ConsultationTurnResult.Unknown, check(listOf(assistant(tool), final)))
    }

    @Test fun terminalEvidenceMustMatchTheFinalMessageAndCommittedEpoch() {
        val final = UIMessage.assistant("final body")
        val proof = requireNotNull(createGenerationTerminalEvidence(final, final, final, 7))
        for (epoch in listOf(null, 6L, 8L)) {
            assertSame(ConsultationTurnResult.Unknown,
                consultationTerminalResult(listOf(trigger, final), trigger.id.toString(), true,
                    terminalEvidence = proof, contextEpoch = epoch, requireTerminalEvidence = true))
        }
        assertSame(ConsultationTurnResult.Unknown,
            consultationTerminalResult(listOf(trigger, UIMessage.assistant("final body")), trigger.id.toString(), true,
                terminalEvidence = proof, contextEpoch = 7, requireTerminalEvidence = true))
    }

    @Test fun provenTerminalTextStillMustBeNonblankAndWithinTheByteLimit() {
        for ((body, expected) in listOf("" to ConsultationTerminalFailure.EMPTY_FINAL_TEXT,
            " " to ConsultationTerminalFailure.EMPTY_FINAL_TEXT,
            "字".repeat(5462) to ConsultationTerminalFailure.OVERSIZED_FINAL_TEXT)) {
            val final = UIMessage.assistant(body)
            val proof = requireNotNull(createGenerationTerminalEvidence(final, final, final, 7))
            val assessed = consultationTerminalAssessment(listOf(trigger, final), trigger.id.toString(), true,
                terminalEvidence = proof, contextEpoch = 7, requireTerminalEvidence = true)
            assertSame(ConsultationTurnResult.Unknown, assessed.result)
            assertEquals(expected, assessed.failure)
        }
        val maximum = UIMessage.assistant("a".repeat(16384))
        val proof = requireNotNull(createGenerationTerminalEvidence(maximum, maximum, maximum, 7))
        assertTrue(consultationTerminalResult(listOf(trigger, maximum), trigger.id.toString(), true,
            terminalEvidence = proof, contextEpoch = 7, requireTerminalEvidence = true) is ConsultationTurnResult.Complete)
    }

    @Test fun compactionCanFinishOnlyUsingThisRequestsRecordedOutputIdentities() {
        val old = UIMessage.assistant("unrelated")
        val final = UIMessage.assistant("final")
        assertEquals(ConsultationTurnResult.Unknown,
            consultationTerminalResult(listOf(old, final), trigger.id.toString(), true))
        assertEquals(ConsultationTurnResult.Complete("final", final.id.toString()),
            consultationTerminalResult(listOf(old, final), trigger.id.toString(), true, setOf(final.id.toString())))
    }

    @Test fun waitingApprovalOrRunningCannotBeAutomaticallyStarted() {
        val checkpoint = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "owner", "binding", "input")
        for (state in listOf("RUNNING", "WAITING_APPROVAL", "UNKNOWN", "COMPLETE", "SUBMITTED")) {
            assertFalse(canStartConsultationGeneration(checkpoint.copy(state = state)))
        }
    }

    @Test fun archiveOfStoppedTurnCanDenyPendingButCannotSettleInFlightTools() {
        val checkpoint = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "owner", "binding", "input", state = "UNKNOWN")
        assertTrue(canArchiveStoppedConsultation(checkpoint.copy(messages = listOf(trigger, UIMessage.assistant("partial")))))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(toolInFlight = "call-1")))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(messages = listOf(assistant(tool)))))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(state = "RUNNING")))
        assertTrue(canArchiveStoppedConsultation(checkpoint.copy(state = "WAITING_APPROVAL",
            messages = listOf(assistant(tool.copy(approvalState = ToolApprovalState.Pending))))))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(state = "WAITING_APPROVAL",
            messages = listOf(assistant(tool.copy(approvalState = ToolApprovalState.Approved))))))
    }

    @Test fun archiveDenialPreservesCallAndOnlyAddsUnexecutedDenialReceipt() {
        val pending = tool.copy(approvalState = ToolApprovalState.Pending)
        val executed = tool.copy(toolCallId = "done", output = listOf(UIMessagePart.Text("old receipt")))
        val original = assistant(UIMessagePart.Text("old commentary"), pending, executed)
        val denied = denyStoppedConsultationPendingTools(original)
        assertEquals(original.id, denied.id)
        assertEquals(original.parts.first(), denied.parts.first())
        assertEquals(executed, denied.getTools().last())
        val settled = denied.getTools().first()
        assertEquals(pending.input, settled.input)
        assertEquals(pending.toolCallId, settled.toolCallId)
        assertEquals(pending.toolName, settled.toolName)
        assertTrue(settled.approvalState is ToolApprovalState.Denied)
        assertTrue(settled.isExecuted) // A denial receipt is settled; the tool never ran.
        assertTrue(settled.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("未执行"))
    }

    @Test fun archiveCanPreserveCompletedButUnsubmittedBodyWithoutReplayingItsTools() {
        val settled = tool.copy(output = listOf(UIMessagePart.Text("committed receipt")))
        val checkpoint = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "owner", "binding", "input",
            state = "COMPLETE", finalText = "completed body", messages = listOf(assistant(settled), UIMessage.assistant("completed body")))
        assertTrue(canArchiveStoppedConsultation(checkpoint))
        assertFalse(canStartConsultationGeneration(checkpoint))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(toolInFlight = "call-1")))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(finalText = "")))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(messages = listOf(
            assistant(tool.copy(approvalState = ToolApprovalState.Pending))))))
        assertFalse(canArchiveStoppedConsultation(checkpoint.copy(messages = listOf(assistant(tool)))))
    }

    @Test(expected = IllegalStateException::class)
    fun archiveDenialCannotOverwriteUnknownAutomaticTool() {
        denyStoppedConsultationPendingTools(assistant(tool))
    }

    @Test fun lostSubmitAcknowledgementRequiresExactCommittedPeerVisibleReceipt() {
        val checkpoint = ConsultationCheckpoint("a".repeat(32), "b".repeat(32), "ACTIVE", "owner", "binding", "input",
            state = "COMPLETE", finalText = "final body", submitAttempts = 1)
        fun match(cp: ConsultationCheckpoint = checkpoint, speaker: String? = "self", request: String? = cp.requestId,
            body: String? = cp.finalText) = matchesAcknowledgedConsultationBody(cp, "self", speaker, request, body)
        assertTrue(match())
        assertFalse(match(speaker = "peer"))
        assertFalse(match(request = null))
        assertFalse(match(request = "c".repeat(32)))
        assertFalse(match(body = "final body "))
        assertFalse(match(checkpoint.copy(state = "UNKNOWN")))
        assertFalse(match(checkpoint.copy(submitAttempts = 0)))
        assertFalse(match(checkpoint.copy(toolInFlight = "call")))
        assertFalse(match(checkpoint.copy(phase = "ARCHIVING")))
    }

    @Test fun persistedTurnRetainsOriginAndRequestAcrossApprovalAndProcessReload() {
        val turn = ConsultationConversationTurn("a".repeat(32), "b".repeat(32), "subject", "owner", "ACTIVE", 2, 9000,
            "consultation-peer", 16384, 11)
        val json = Json { encodeDefaults = true }
        assertEquals(turn, json.decodeFromString<ConsultationConversationTurn>(json.encodeToString(turn)))
    }

    @Test fun recoveryRequiresOldProcessOrJournalProofAndNeverAnActiveJob() {
        val turn = ConsultationConversationTurn("a".repeat(32), "b".repeat(32), "subject", "owner", "ACTIVE", 2, 9000,
            "consultation-peer", 16384, 11)
        val checkpoint = ConsultationCheckpoint(turn.requestId, turn.sessionId, turn.phase, turn.assistantId,
            "binding", "input", state = "RUNNING", conversationTurn = turn, executionProcessId = "old-process")
        assertTrue(canRecoverAbandonedConsultation(checkpoint, "new-process", false, false))
        assertFalse(canRecoverAbandonedConsultation(checkpoint, "old-process", false, false))
        assertTrue(canRecoverAbandonedConsultation(checkpoint, "old-process", false, true))
        assertFalse(canRecoverAbandonedConsultation(checkpoint, "new-process", true, true))
        assertFalse(canRecoverAbandonedConsultation(checkpoint.copy(executionProcessId = null), "new-process", false, false))
        assertTrue(canRecoverAbandonedConsultation(checkpoint.copy(executionProcessId = null), "new-process", false, true))
        assertFalse(canRecoverAbandonedConsultation(checkpoint.copy(conversationTurn = null), "new-process", false, true))
        for (state in listOf("PREPARED", "WAITING_APPROVAL", "COMPLETE", "SUBMITTED", "UNKNOWN"))
            assertFalse(canRecoverAbandonedConsultation(checkpoint.copy(state = state), "new-process", false, true))
    }

    @Test fun endCancellationTargetsOnlyMatchingHiddenRequestAndPhaseIncludingApprovalContinuation() = runBlocking {
        val turn = ConsultationConversationTurn("a".repeat(32), "b".repeat(32), "subject", "owner", "ACTIVE", 2, 9000,
            "consultation-peer", 16384, 11)
        val ordinaryMainWindow = Job()
        val approvalContinuation = Job()
        val differentRequest = Job()
        val archive = Job()
        val registered = listOf(turn to approvalContinuation,
            turn.copy(requestId = "c".repeat(32)) to differentRequest, turn.copy(phase = "ARCHIVING") to archive)
        registered.filter { consultationJobMatchesEnd(it.first, turn.sessionId, turn.requestId) }
            .forEach { it.second.cancel(); it.second.join() }
        assertTrue(approvalContinuation.isCancelled)
        assertTrue(ordinaryMainWindow.isActive)
        assertTrue(differentRequest.isActive)
        assertTrue(archive.isActive)
        assertFalse(consultationJobMatchesEnd(turn, "d".repeat(32)))
        assertTrue(consultationJobMatchesEnd(turn, turn.sessionId))
        ordinaryMainWindow.cancel(); differentRequest.cancel(); archive.cancel()
    }

    @Test fun durableApprovalIntentPrecedesRoomDecisionAndDoesNotGrantOrLosePendingTools() {
        val turn = ConsultationConversationTurn("a".repeat(32), "b".repeat(32), "subject", "owner", "ACTIVE", 2, 9000,
            "consultation-peer", 16384, 11)
        val pending = assistant(tool.copy(approvalState = ToolApprovalState.Pending))
        val checkpoint = ConsultationCheckpoint(turn.requestId, turn.sessionId, turn.phase, turn.assistantId,
            "binding", "input", state = "WAITING_APPROVAL", messages = listOf(trigger, pending), conversationTurn = turn)
        val intent = consultationApprovalIntent(checkpoint, "process-one")
        assertEquals("RUNNING", intent.state)
        assertEquals("process-one", intent.executionProcessId)
        assertEquals(checkpoint.messages, intent.messages)
        assertTrue(intent.messages.last().getTools().single().isPending)
        assertFalse(canStartConsultationGeneration(intent))
        assertTrue(canRecoverAbandonedConsultation(intent, "process-two", false, false))
        assertEquals(ConsultationTurnResult.WaitingApproval,
            consultationTerminalResult(intent.messages, trigger.id.toString(), true))
        val approvedNoExecution = pending.copy(parts = listOf(tool.copy(approvalState = ToolApprovalState.Approved)))
        assertEquals(ConsultationTurnResult.Unknown,
            consultationTerminalResult(listOf(trigger, approvedNoExecution), trigger.id.toString(), false))
        assertTrue(runCatching { consultationApprovalIntent(checkpoint.copy(toolInFlight = "call-1"), "p") }.isFailure)
        assertTrue(runCatching { consultationApprovalIntent(checkpoint.copy(state = "UNKNOWN"), "p") }.isFailure)
    }
}
