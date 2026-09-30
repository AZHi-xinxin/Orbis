package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KnownEmptyCompletionFailureTest {
    private fun error() = HttpException("No answer", "upstream_empty_completion", "stiller_gateway_error")
    private fun response(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test fun `structured gateway empty response is recognized with no streamed output`() {
        assertTrue(KnownEmptyCompletionFailure.classify(error(), null) is KnownEmptyCompletionFailure)
        assertTrue(KnownEmptyCompletionFailure.classify(error(), response()) is KnownEmptyCompletionFailure)
    }

    @Test fun `reasoning and whitespace alone can release future input without replaying this turn`() {
        assertTrue(KnownEmptyCompletionFailure.classify(error(), response(
            UIMessagePart.Reasoning("synthetic reasoning"), UIMessagePart.Text(" \n"),
        )) is KnownEmptyCompletionFailure)
    }

    @Test fun `visible content or tool fragments are not classified as empty`() {
        listOf(
            UIMessagePart.Text("partial answer"),
            UIMessagePart.Tool(toolCallId = "partial", toolName = "", input = "", output = emptyList()),
            UIMessagePart.Image("synthetic.png"),
        ).forEach { part ->
            val error = error()
            assertSame(error, KnownEmptyCompletionFailure.classify(error, response(part)))
        }
    }

    @Test fun `matching human readable message is insufficient`() {
        val error = HttpException("[ST 网关 · upstream_empty_completion] empty")
        assertSame(error, KnownEmptyCompletionFailure.classify(error, null))
    }

    @Test fun `cancellation transport and unknown failures keep their original identity`() {
        listOf(CancellationException("stop"), IOException("disconnected"),
            HttpException("Unknown", "upstream_error", "stiller_gateway_error"),
            HttpException("Empty", "upstream_empty_completion", "other_error"),
        ).forEach { error -> assertSame(error, KnownEmptyCompletionFailure.classify(error, null)) }
    }
}
