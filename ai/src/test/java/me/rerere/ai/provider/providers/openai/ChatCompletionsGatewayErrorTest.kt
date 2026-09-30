package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsGatewayErrorTest {
    @Test fun `streamed structured gateway error preserves identity after reasoning`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"choices":[{"delta":{"reasoning_content":"synthetic reasoning"}}]}"""))
        val failure = runCatching {
            decoder.accept(SseEvent(data = """{"error":{"message":"Only reasoning returned","type":"stiller_gateway_error","code":"upstream_empty_completion"}}"""))
        }.exceptionOrNull()
        assertTrue(failure is HttpException)
        assertEquals("upstream_empty_completion", (failure as HttpException).code)
        assertEquals("stiller_gateway_error", failure.errorType)
    }

    @Test fun `gateway bounded retry joins attempts without duplicate finish or usage`() {
        val decoder = ChatCompletionsStreamDecoder()
        // A gateway SSE comment is ignored by the transport before this decoder sees it.
        // The first attempt's terminal and DONE are withheld, while its reasoning is preserved.
        val events = listOf(
            """{"id":"attempt-one","choices":[{"delta":{"role":"assistant","reasoning_content":"first reasoning; "}}]}""",
            """{"id":"attempt-two","choices":[{"delta":{"role":"assistant","reasoning_content":"second reasoning"}}]}""",
            """{"id":"attempt-two","choices":[{"delta":{"content":"synthetic final answer"}}]}""",
            """{"id":"attempt-two","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":101,"completion_tokens":23,"total_tokens":124},"st_gateway_retry":{"attempts":2,"first_completion_tokens":7}}""",
            "[DONE]",
        )
        val decoded = events.map { decoder.accept(SseEvent(data = it)) }
        val chunks = decoded.flatMap { it.chunks } + decoder.onClosed()
        val handler = StreamChunkHandler()
        val messages = chunks.fold(listOf(UIMessage(role = MessageRole.USER, parts = emptyList()))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        assertEquals("synthetic final answer", messages.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertEquals("first reasoning; second reasoning", messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning })
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Finish>().size)
        assertEquals("attempt-two", chunks.filterIsInstance<StreamChunk.Finish>().single().responseId)
        assertEquals("stop", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Usage>().size)
        assertEquals(124, chunks.filterIsInstance<StreamChunk.Usage>().single().usage.totalTokens)
        assertTrue(decoded.last().completed)
        assertTrue(decoded.dropLast(1).none { it.completed })
        assertTrue(decoder.onClosed().isEmpty())
    }
}
