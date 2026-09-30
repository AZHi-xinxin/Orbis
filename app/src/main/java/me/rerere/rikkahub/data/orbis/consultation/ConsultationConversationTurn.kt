package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.GenerationTerminalEvidence
import me.rerere.rikkahub.data.ai.matchesGenerationTerminalEvidence
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import kotlin.uuid.Uuid

/** Host-owned delivery identity. None of these fields is a tool grant or a human message. */
@Serializable
internal data class ConsultationConversationTurn(
    val requestId: String,
    val sessionId: String,
    val subject: String,
    val assistantId: String,
    val phase: String,
    val configRevision: Long,
    val expiresAtMillis: Long,
    val sourceKind: String,
    val outputTokenLimit: Int,
    val relaySequence: Long,
) {
    val conversationId: Uuid get() = consultationConversationId(sessionId, subject)
    val inputId: Uuid get() = consultationInputId(requestId)
}

internal fun consultationConversationId(sessionId: String, subject: String): Uuid =
    Uuid.parse(java.util.UUID.nameUUIDFromBytes("consultation:$sessionId:$subject".toByteArray(Charsets.UTF_8)).toString())

internal fun consultationInputId(requestId: String): Uuid =
    Uuid.parse(java.util.UUID.nameUUIDFromBytes("consultation-input:$requestId".toByteArray(Charsets.UTF_8)).toString())

internal sealed interface ConsultationTurnResult {
    data class Complete(val text: String, val messageId: String) : ConsultationTurnResult
    data object WaitingApproval : ConsultationTurnResult
    data object Unknown : ConsultationTurnResult
}

/** Closed diagnostic codes only: never message text, tool arguments or exception messages. */
internal enum class ConsultationTerminalFailure(val code: String) {
    GENERATION_NOT_COMPLETED("generation_not_completed_no_automatic_retry"),
    UNEXECUTED_TOOL("unexecuted_tool_no_automatic_retry"),
    MISSING_TERMINAL_ASSISTANT("missing_terminal_assistant_no_automatic_retry"),
    TOOL_CALL_TAIL("tool_call_tail_no_automatic_retry"),
    EMPTY_FINAL_TEXT("empty_final_text_no_automatic_retry"),
    OVERSIZED_FINAL_TEXT("oversized_final_text_no_automatic_retry"),
    TERMINAL_RESPONSE_UNVERIFIED("terminal_response_unverified_no_automatic_retry"),
}

internal data class ConsultationTerminalAssessment(
    val result: ConsultationTurnResult,
    val failure: ConsultationTerminalFailure? = null,
)

internal data class ConsultationPendingApproval(
    val conversationId: String, val requestId: String, val toolCallId: String,
    val name: String, val arguments: String, val approvalFingerprint: String?,
)

/** End-of-session denial receipt, never execution or approval. The caller must establish a
 * fresh ARCHIVING claim and verify that this message belongs to the stopped local request. */
internal fun denyStoppedConsultationPendingTools(message: UIMessage): UIMessage {
    check(message.getTools().all { it.isExecuted || it.isPending }) { "consultation_tool_outcome_unresolved" }
    return message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.isPending) part.copy(
            approvalState = ToolApprovalState.Denied("咨询已结束，未批准的操作不再执行。"),
            output = listOf(UIMessagePart.Text("本次工具在执行前因咨询结束而被拒绝；未执行。")),
        ) else part
    })
}

internal fun matchesAcknowledgedConsultationBody(checkpoint: ConsultationCheckpoint, subject: String,
    speaker: String?, requestId: String?, body: String?): Boolean =
    checkpoint.phase == "ACTIVE" && checkpoint.state == "COMPLETE" && checkpoint.submitAttempts > 0 &&
        checkpoint.toolInFlight == null && checkpoint.finalText.isNotBlank() &&
        speaker == subject && requestId == checkpoint.requestId && body == checkpoint.finalText

/** One identifier for this app process, not a credential and never a retry permission. */
internal val consultationRuntimeProcessId: String = consultationRandom(16)

internal fun canRecoverAbandonedConsultation(checkpoint: ConsultationCheckpoint, processId: String,
    hasLiveJob: Boolean, hasJournal: Boolean): Boolean =
    checkpoint.state == "RUNNING" && checkpoint.conversationTurn != null && !hasLiveJob &&
        ((checkpoint.executionProcessId != null && checkpoint.executionProcessId != processId) || hasJournal)

internal fun consultationJobMatchesEnd(turn: ConsultationConversationTurn, sessionId: String,
    requestId: String? = null, phase: String = "ACTIVE"): Boolean =
    turn.sessionId == sessionId && turn.phase == phase && (requestId == null || turn.requestId == requestId)

/** Durable approval intent precedes even the ordinary Room Approved/Denied update. It does
 * not approve anything itself; ordinary host-snapshot validation still decides the operation. */
internal fun consultationApprovalIntent(checkpoint: ConsultationCheckpoint, processId: String): ConsultationCheckpoint {
    check(checkpoint.state == "WAITING_APPROVAL" && checkpoint.conversationTurn != null && checkpoint.toolInFlight == null)
    return checkpoint.copy(state = "RUNNING", executionProcessId = processId)
}

/** Only the last, terminal assistant TEXT is transportable. Never concatenate tool commentary,
 * reasoning, previous turns, or a compaction summary into the other participant's message. */
internal fun consultationTerminalResult(messages: List<UIMessage>, inputId: String,
    generationSucceeded: Boolean, outputMessageIds: Set<String> = emptySet(),
    terminalEvidence: GenerationTerminalEvidence? = null, contextEpoch: Long? = null,
    requireTerminalEvidence: Boolean = false): ConsultationTurnResult =
    consultationTerminalAssessment(messages, inputId, generationSucceeded, outputMessageIds,
        terminalEvidence, contextEpoch, requireTerminalEvidence).result

/** Same transport decision as before, with a bounded reason alongside Unknown. */
internal fun consultationTerminalAssessment(messages: List<UIMessage>, inputId: String,
    generationSucceeded: Boolean, outputMessageIds: Set<String> = emptySet(),
    terminalEvidence: GenerationTerminalEvidence? = null, contextEpoch: Long? = null,
    requireTerminalEvidence: Boolean = false): ConsultationTerminalAssessment {
    fun unknown(reason: ConsultationTerminalFailure) = ConsultationTerminalAssessment(ConsultationTurnResult.Unknown, reason)
    val trigger = messages.indexOfLast { it.id.toString() == inputId }
    if (!generationSucceeded) return unknown(ConsultationTerminalFailure.GENERATION_NOT_COMPLETED)
    val generated = if (trigger >= 0) messages.drop(trigger + 1)
        else messages.filter { it.id.toString() in outputMessageIds }
    if (generated.any { it.getTools().any { tool -> tool.isPending } })
        return ConsultationTerminalAssessment(ConsultationTurnResult.WaitingApproval)
    if (generated.any { it.getTools().any { tool -> !tool.isExecuted } })
        return unknown(ConsultationTerminalFailure.UNEXECUTED_TOOL)
    val last = generated.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT && !it.isCompactionSummary() }
        ?: return unknown(ConsultationTerminalFailure.MISSING_TERMINAL_ASSISTANT)
    // Completed tools may share the same bubble with a later provider response. Only fresh,
    // exact terminal-step evidence can separate its final text from earlier tool commentary.
    val text = if (terminalEvidence != null) {
        if (contextEpoch == null || !matchesGenerationTerminalEvidence(terminalEvidence, last, contextEpoch))
            return unknown(ConsultationTerminalFailure.TERMINAL_RESPONSE_UNVERIFIED)
        terminalEvidence.text
    } else {
        if (requireTerminalEvidence) return unknown(ConsultationTerminalFailure.TERMINAL_RESPONSE_UNVERIFIED)
        // Compatibility for older pure no-tool callers; production requests require evidence.
        if (last.getTools().isNotEmpty()) return unknown(ConsultationTerminalFailure.TOOL_CALL_TAIL)
        last.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
    if (text.isBlank()) return unknown(ConsultationTerminalFailure.EMPTY_FINAL_TEXT)
    if (text.toByteArray(Charsets.UTF_8).size > 16384) return unknown(ConsultationTerminalFailure.OVERSIZED_FINAL_TEXT)
    return ConsultationTerminalAssessment(ConsultationTurnResult.Complete(text, last.id.toString()))
}
