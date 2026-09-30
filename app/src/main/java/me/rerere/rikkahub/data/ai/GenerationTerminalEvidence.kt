package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import java.security.MessageDigest

/** Ephemeral host evidence for ONE naturally completed provider response. Never a retry grant,
 * persisted checkpoint schema, or substitute for a successful final Room/journal commit. */
data class GenerationTerminalEvidence internal constructor(
    val messageId: String,
    val contextEpoch: Long,
    val text: String,
    val tailDigest: String,
) {
    override fun toString(): String = "GenerationTerminalEvidence(redacted)"
}

@Suppress("DEPRECATION")
internal fun generationResponseHasToolParts(message: UIMessage): Boolean = message.parts.any {
    it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult ||
        it is UIMessagePart.ServerTool || it is UIMessagePart.Search
}

/** Retain reasoning for the existing think-tag conversion, but never rerun attachment writes. */
internal fun generationTerminalTextInput(response: UIMessage): UIMessage = response.copy(
    parts = response.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Reasoning },
)

@Suppress("DEPRECATION")
private fun terminalUiTail(message: UIMessage): String? {
    if (message.role != MessageRole.ASSISTANT || message.isCompactionSummary()) return null
    if (message.getTools().any { !it.isExecuted || it.isPending }) return null
    // Other tool representations have no client completion proof in this protocol.
    if (message.parts.any { it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult ||
            it is UIMessagePart.ServerTool || it is UIMessagePart.Search }) return null
    val lastTool = message.parts.indexOfLast { it is UIMessagePart.Tool }
    return message.parts.drop(lastTool + 1).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
}

private fun terminalTailDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Called only in the natural no-new-tools branch. The independent response is essential:
 * text after a Tool in a merged UI message may still be commentary from that tool-call step. */
internal fun createGenerationTerminalEvidence(
    rawResponse: UIMessage,
    transformedResponse: UIMessage,
    displayedMessage: UIMessage,
    contextEpoch: Long,
): GenerationTerminalEvidence? {
    if (contextEpoch < 0 || rawResponse.role != MessageRole.ASSISTANT || rawResponse.isCompactionSummary() ||
        generationResponseHasToolParts(rawResponse) || transformedResponse.role != MessageRole.ASSISTANT ||
        transformedResponse.isCompactionSummary() || generationResponseHasToolParts(transformedResponse)) return null
    val text = transformedResponse.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val tail = terminalUiTail(displayedMessage) ?: return null
    // Non-streaming continuation can append into an existing Text part. Match exact content,
    // not a part offset or a guessed prefix length; never send the preceding UI commentary.
    if (!tail.endsWith(text)) return null
    return GenerationTerminalEvidence(displayedMessage.id.toString(), contextEpoch, text, terminalTailDigest(tail))
}

internal fun matchesGenerationTerminalEvidence(
    evidence: GenerationTerminalEvidence,
    committedMessage: UIMessage,
    contextEpoch: Long,
): Boolean {
    if (evidence.messageId != committedMessage.id.toString() || evidence.contextEpoch != contextEpoch) return false
    val tail = terminalUiTail(committedMessage) ?: return false
    return tail.endsWith(evidence.text) && evidence.tailDigest == terminalTailDigest(tail)
}
