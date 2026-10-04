package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import org.junit.Assert.*
import org.junit.Test

class GenerationHistoryAdmissionTest {
    private fun tool(input: String = "{}") = UIMessagePart.Tool(toolCallId = "fixture", toolName = "gallery_append", input = input)
    private fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test fun `oversized single stream argument is refused before merging history`() {
        val huge = "x".repeat(MessageNodeBudget.MAX_GENERATION_NODE_BYTES + 1)
        for (chunk in listOf(StreamChunk.ToolCallDelta("tool", inputDelta = huge),
            StreamChunk.TextDelta("text", huge), StreamChunk.ReasoningDelta("reason", huge),
            StreamChunk.ServerToolInputDelta("server", huge))) {
            assertThrows(MessageNodeCapacityException::class.java) { requireBoundedGenerationChunk(chunk) }
        }
    }

    @Test fun `multiple argument chunks and mixed text share one working message bound`() {
        val chunk = "x".repeat(32 * 1024)
        requireBoundedGenerationChunk(StreamChunk.ToolCallDelta("tool", inputDelta = chunk))
        requireBoundedWorkingMessage(message(tool(chunk)))
        assertThrows(MessageNodeCapacityException::class.java) {
            requireBoundedWorkingMessage(message(*List(23) { tool(chunk).copy(toolCallId = "$it") }.toTypedArray()))
        }
        assertThrows(MessageNodeCapacityException::class.java) {
            requireBoundedWorkingMessage(message(UIMessagePart.Text(chunk.repeat(12)), UIMessagePart.Reasoning(chunk.repeat(11))))
        }
    }

    @Test fun `soft pause only after completed tools never pending approval or unknown outcome`() {
        assertFalse(canStopAtHistoryBudget(true, listOf(UIMessage.user("human"))))
        assertFalse(canStopAtHistoryBudget(true, listOf(message(tool()))))
        assertFalse(canStopAtHistoryBudget(true, listOf(message(tool().copy(approvalState = ToolApprovalState.Pending)))))
        val completed = message(tool().copy(output = listOf(UIMessagePart.Text("durable result"))))
        assertFalse(canStopAtHistoryBudget(false, listOf(completed)))
        assertTrue(canStopAtHistoryBudget(true, listOf(completed)))
        assertTrue(canStopAtHistoryBudget(true, listOf(UIMessage.assistant("finished text"))))
        assertFalse(canStopAtHistoryBudget(true, emptyList()))
    }
}
