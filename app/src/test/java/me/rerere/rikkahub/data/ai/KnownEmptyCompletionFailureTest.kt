package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    @Test fun `an unfinished tool instruction is never silently executed`() {
        val fragment = response(UIMessagePart.Tool(toolCallId = "partial", toolName = "", input = "", output = emptyList()))
        assertNull(KnownEmptyCompletionFailure.completionOrNull(error(), fragment))
    }

    @Test fun `completed silence is a normal assistant message without invented prose`() {
        val empty = KnownEmptyCompletionFailure.completionOrNull(error(), null)!!
        assertEquals(MessageRole.ASSISTANT, empty.role)
        assertTrue(empty.parts.isEmpty())
        val source = response(UIMessagePart.Reasoning("synthetic reasoning"), UIMessagePart.Text(" \n"))
        val result = KnownEmptyCompletionFailure.completionOrNull(error(), source)!!
        assertEquals(source.id, result.id)
        assertEquals("synthetic reasoning", result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals(" \n", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test fun `legacy synthetic errors do not judge the intent of received content`() {
        val source = response(UIMessagePart.Text("received answer"), UIMessagePart.Image("synthetic.png"))
        assertEquals(source.parts, KnownEmptyCompletionFailure.completionOrNull(error(), source)!!.parts)
        assertNotNull(KnownEmptyCompletionFailure.completionOrNull(
            HttpException("legacy synthetic length error", "upstream_output_limit_reached", "stiller_gateway_error"), null))
    }

    @Test fun `matching human readable message is insufficient`() {
        val error = HttpException("[ST 网关 · upstream_empty_completion] empty")
        assertSame(error, KnownEmptyCompletionFailure.classify(error, null))
    }

    @Test fun `cancellation transport and unknown failures keep their original identity`() {
        listOf(CancellationException("stop"), IOException("disconnected"),
            HttpException("Unknown", "upstream_error", "stiller_gateway_error"),
            HttpException("Empty", "upstream_empty_completion", "other_error"),
        ).forEach { error ->
            assertSame(error, KnownEmptyCompletionFailure.classify(error, null))
            assertNull(KnownEmptyCompletionFailure.completionOrNull(error, null))
        }
    }
}
