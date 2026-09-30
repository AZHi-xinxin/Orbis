package me.rerere.rikkahub.data.orbis

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisEventCompletionTest {
    private val time = LocalDateTime(2026, 9, 22, 10, 0)
    private val event = UIMessage(
        role = MessageRole.USER, parts = listOf(UIMessagePart.Text("event body")), createdAt = time,
        orbisEvent = OrbisEventMetadata("record-1", "lc_sentinel", "event-1", 123),
    )
    private fun answer(vararg parts: UIMessagePart, finished: Boolean = true) = UIMessage(
        role = MessageRole.ASSISTANT, parts = parts.toList(), createdAt = time,
        finishedAt = if (finished) time else null,
    )
    private fun tool(pending: Boolean = false, executed: Boolean = false) = UIMessagePart.Tool(
        toolCallId = "call-1", toolName = "test_tool", input = "{}",
        approvalState = if (pending) ToolApprovalState.Pending else ToolApprovalState.Auto,
        output = if (executed) listOf(UIMessagePart.Text("tool result is not a reply")) else emptyList(),
    )
    private fun state(vararg messages: UIMessage, error: Boolean = false) =
        orbisEventCompletionState(messages.toList(), "record-1", error)

    @Test
    fun `no reply or finished empty message remains unknown`() {
        assertEquals("unknown", state(event))
        assertEquals("unknown", state(event, answer()))
        assertEquals("unknown", state(event, answer(UIMessagePart.Text(" \n\t"))))
    }

    @Test
    fun `reasoning alone is not a completed user facing reply`() {
        assertEquals("unknown", state(event, answer(UIMessagePart.Reasoning("thinking content"))))
        assertEquals("unknown", state(event, answer(UIMessagePart.Reasoning("done thinking"), UIMessagePart.Text(" "))))
    }

    @Test
    fun `tool call or tool output alone is not a completed reply`() {
        assertEquals("unknown", state(event, answer(tool())))
        assertEquals("unknown", state(event, answer(tool(executed = true))))
        assertEquals("unknown", state(event, answer(UIMessagePart.Text("result")).copy(role = MessageRole.TOOL)))
    }

    @Test
    fun `visible text and media still need assistant finishedAt`() {
        listOf(UIMessagePart.Text("streaming"), UIMessagePart.Image("file:///image.png"),
            UIMessagePart.Audio("file:///audio.wav")).forEach { part ->
            assertEquals("unknown", state(event, answer(part, finished = false)))
        }
    }

    @Test
    fun `finished assistant text or media is a completed reply`() {
        listOf(
            UIMessagePart.Text("reply"),
            UIMessagePart.Image("file:///image.png"),
            UIMessagePart.Audio("file:///audio.wav"),
            UIMessagePart.Video("file:///video.mp4"),
            UIMessagePart.Document("file:///document.txt", "document.txt"),
        ).forEach { part -> assertEquals("replied", state(event, answer(part))) }
    }

    @Test
    fun `finished messages with empty media urls are not completed replies`() {
        listOf(UIMessagePart.Image(" "), UIMessagePart.Audio(""), UIMessagePart.Video("\n"),
            UIMessagePart.Document("", "name without file")).forEach { part ->
            assertEquals("unknown", state(event, answer(part)))
        }
    }

    @Test
    fun `pending approval takes priority over a finished visible reply in the event turn`() {
        assertEquals("pending_tool", state(event, answer(UIMessagePart.Text("partial reply")), answer(tool(pending = true))))
        assertEquals("pending_tool", state(event, answer(UIMessagePart.Text("partial reply"), tool(pending = true))))
        // A stale Pending tag on a tool that already has output is not awaiting approval.
        assertEquals("replied", state(event, answer(UIMessagePart.Text("done"), tool(pending = true, executed = true))))
    }

    @Test
    fun `generation error is unknown even if reply text or pending tool exists`() {
        assertEquals("unknown", state(event, answer(UIMessagePart.Text("partial")), error = true))
        assertEquals("unknown", state(event, answer(tool(pending = true)), error = true))
    }

    @Test
    fun `a later human turn cannot provide the event reply`() {
        val human = event.copy(orbisEvent = null, parts = listOf(UIMessagePart.Text("new human turn")))
        assertEquals("unknown", state(event, answer(UIMessagePart.Reasoning("unfinished")), human,
            answer(UIMessagePart.Text("answer to the human"))))
    }

    @Test
    fun `a later different event cannot provide this event reply`() {
        val later = event.copy(orbisEvent = OrbisEventMetadata("record-2", "self_reminder", "event-2", 456))
        assertEquals("unknown", state(event, later, answer(UIMessagePart.Text("answer to another event"))))
    }

    @Test
    fun `missing event and earlier assistant replies cannot be mistaken for completion`() {
        val earlier = answer(UIMessagePart.Text("earlier answer"))
        assertEquals("unknown", state(earlier))
        assertEquals("unknown", state(earlier, event))
        assertEquals("unknown", orbisEventCompletionState(listOf(event, earlier), "missing-record", false))
    }

    @Test
    fun `a later human pending tool does not replace an already completed event state`() {
        val human = event.copy(orbisEvent = null, parts = listOf(UIMessagePart.Text("new human turn")))
        assertEquals("replied", state(event, answer(UIMessagePart.Text("event answer")), human, answer(tool(pending = true))))
    }
}
