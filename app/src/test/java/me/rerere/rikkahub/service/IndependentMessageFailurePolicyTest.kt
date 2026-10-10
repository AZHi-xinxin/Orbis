package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IndependentMessageFailurePolicyTest {
    private val before = listOf(UIMessage.user("synthetic input"))
    private val after = before + UIMessage.assistant("partial synthetic text")
    private fun http(status: Int) = HttpException("PRIVATE_SERVER_BODY", httpStatus = status)
    private fun allowed(error: Throwable, messages: List<UIMessage> = after, blocked: Boolean = false) =
        canContinueAfterIndependentModelFailure(error, before, messages, blocked)

    @Test fun `known provider rejection of text only turn releases subsequent independent work`() {
        listOf(400, 401, 403, 404, 413, 422, 429, 500, 502, 503, 504).forEach { assertTrue("$it", allowed(http(it))) }
    }
    @Test fun `409 and arbitrary error prose never establish a safe provider rejection`() {
        assertFalse(allowed(http(409)))
        assertFalse(allowed(HttpException("502 but previous tool unknown")))
        assertFalse(allowed(IOException("network result unknown")))
        assertFalse(allowed(CancellationException("user stopped")))
        assertFalse(allowed(http(503), blocked = true))
    }
    @Test fun `a completed tool in this failed generation still prevents automatic queue continuation`() {
        val tool = UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy", input = "{}", output = listOf(UIMessagePart.Text("done")))
        assertFalse(allowed(http(502), before + UIMessage.assistant("").copy(parts = listOf(tool))))
    }
    @Test fun `pending and host unknown tools keep safety hold even if they predate current input`() {
        val pending = UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy", input = "{}")
        for (tool in listOf(pending, pending.withHostToolFailure(HostToolFailure.INTERRUPTED))) {
            val previous = listOf(UIMessage.assistant("").copy(parts = listOf(tool))) + before
            assertFalse(canContinueAfterIndependentModelFailure(http(503), previous, previous + UIMessage.assistant("text"), false))
        }
    }
    @Test fun `policy is classification only and never resumes an existing paused queue`() {
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(listOf(UIMessagePart.Text("next")))
        val snapshot = queue.state.value
        assertTrue(allowed(http(502)))
        assertEquals(snapshot, queue.state.value)
    }
    @Test fun `historical unfinished server tool is still an unknown effect and cannot release queue`() {
        val tool = UIMessagePart.ServerTool("server-call", "server-action", status = ServerToolStatus.IN_PROGRESS)
        val previous = listOf(UIMessage.assistant("").copy(parts = listOf(tool))) + before
        assertFalse(canContinueAfterIndependentModelFailure(http(503), previous, previous + UIMessage.assistant("text"), false))
    }

    @Test fun `local picture revocation releases independent input even after completed tools without replay`() {
        val error = me.rerere.rikkahub.data.ai.transformers.ScreenShareFrameRevokedException()
        val tool = UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy", input = "{}", output = listOf(UIMessagePart.Text("done")))
        val completed = before + UIMessage.assistant("").copy(parts = listOf(tool))
        assertTrue(allowed(error, completed))
        assertEquals("done", completed.last().getTools().single().output.filterIsInstance<UIMessagePart.Text>().single().text)
        assertFalse(allowed(error, completed, blocked = true))
    }

    @Test fun `picture revocation never bypasses uncertain tool execution`() {
        val error = me.rerere.rikkahub.data.ai.transformers.ScreenShareFrameRevokedException()
        val pending = UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy", input = "{}")
        listOf(pending, pending.withHostToolFailure(HostToolFailure.INTERRUPTED)).forEach {
            assertFalse(allowed(error, before + UIMessage.assistant("").copy(parts = listOf(it))))
        }
        val server = UIMessagePart.ServerTool("server-call", "server-action", status = ServerToolStatus.IN_PROGRESS)
        assertFalse(allowed(error, before + UIMessage.assistant("").copy(parts = listOf(server))))
    }
}
