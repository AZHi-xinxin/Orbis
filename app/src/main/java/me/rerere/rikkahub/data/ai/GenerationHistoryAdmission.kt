package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException

/** Cheap streaming admission between exact, throttled persistence boundaries. No history scan. */
internal fun requireBoundedGenerationChunk(chunk: StreamChunk) {
    val units = when (chunk) {
        is StreamChunk.ToolCallDelta -> chunk.inputDelta.length.toLong() + chunk.toolNameDelta.length
        is StreamChunk.TextDelta -> chunk.text.length.toLong()
        is StreamChunk.ReasoningDelta -> chunk.text.length.toLong()
        is StreamChunk.ServerToolInputDelta -> chunk.inputDelta.length.toLong()
        else -> 0L
    }
    if (units > MessageNodeBudget.MAX_GENERATION_NODE_BYTES) throw MessageNodeCapacityException("generation_chunk_capacity")
}

/** UTF-16 units are a lower bound on serialized UTF-8 bytes; exact JSON admission follows. */
internal fun requireBoundedWorkingMessage(message: UIMessage?) {
    var units = 0L
    message?.parts?.forEach { part ->
        units += when (part) {
            is UIMessagePart.Text -> part.text.length.toLong()
            is UIMessagePart.Reasoning -> part.reasoning.length.toLong()
            is UIMessagePart.Tool -> part.input.length.toLong() + part.toolName.length
            else -> 0L // Image data is transformed into files before exact output admission.
        }
        if (units > MessageNodeBudget.MAX_GENERATION_NODE_BYTES) throw MessageNodeCapacityException("generation_working_capacity")
    }
}

/** Never turn pending approval, unexecuted tools, or a human prompt into a completed model reply. */
internal fun canStopAtHistoryBudget(softReached: Boolean, messages: List<UIMessage>): Boolean =
    softReached && messages.lastOrNull()?.let { message ->
        message.role == MessageRole.ASSISTANT && message.getTools().all { it.isExecuted && !it.isPending }
    } == true
