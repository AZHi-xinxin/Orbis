package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

class ContinuationStreamEnrichmentTest {
    private val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Reasoning("partial", finishedAt = null)))
    private val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode()))
    private fun page(m: UIMessage) = before.copy(messageNodes = listOf(before.messageNodes.single().copy(messages = listOf(m))))
    private fun text(s: String) = message.copy(parts = listOf((message.parts.single() as UIMessagePart.Reasoning).copy(reasoning = s)))

    @Test fun `late durable final chunk and completion receipt can join a stopped visible tail`() {
        val finished = (message.createdAt.toInstant(TimeZone.UTC) + 2.seconds).toLocalDateTime(TimeZone.UTC)
        val reasoningFinished = (message.parts.single() as UIMessagePart.Reasoning).createdAt + 2.seconds
        val completed = text("partial last chunk").copy(finishedAt = finished,
            usage = me.rerere.ai.core.TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            parts = listOf((message.parts.single() as UIMessagePart.Reasoning).copy(
                reasoning = "partial last chunk", finishedAt = reasoningFinished), UIMessagePart.Text("last answer")))
        val enrichment = assertNotNullValue(ContinuationStreamEnrichment.create(before, page(completed)))
        val stopped = message.finishReasoning()
        val alternative = UIMessage.assistant("another branch")
        val newInput = UIMessage.user("new human input").toMessageNode()
        val continued = before.copy(messageNodes = listOf(before.messageNodes.single().copy(
            messages = listOf(stopped, alternative), selectIndex = 1), newInput))
        val result = assertNotNullValue(enrichment.project(continued))
        val reply = result.messageNodes.first().messages.first()
        assertEquals("partial last chunk", (reply.parts.first() as UIMessagePart.Reasoning).reasoning)
        assertEquals("last answer", (reply.parts.last() as UIMessagePart.Text).text)
        assertEquals((stopped.parts.first() as UIMessagePart.Reasoning).finishedAt,
            (reply.parts.first() as UIMessagePart.Reasoning).finishedAt)
        assertEquals(finished, reply.finishedAt)
        assertEquals(completed.usage, reply.usage)
        assertEquals(alternative, result.messageNodes.first().messages[1])
        assertEquals(1, result.messageNodes.first().selectIndex)
        assertEquals(newInput, result.messageNodes.last())
        assertEquals(result, enrichment.project(result))
        assertNull(enrichment.project(page(completed.copy(parts = completed.parts.dropLast(1) + UIMessagePart.Text("human edit")))))
        assertNull(enrichment.project(page(message.copy(translation = "unrelated metadata"))))
    }

    @Test fun `completed messages other branches and newly appended tools cannot be enriched`() {
        val finished = message.copy(finishedAt = message.createdAt)
        assertNull(ContinuationStreamEnrichment.create(page(finished), page(finished.copy(parts = text("partial more").parts))))
        val alternative = UIMessage.assistant("selected")
        val a = before.copy(messageNodes = listOf(before.messageNodes.single().copy(messages = listOf(message, alternative), selectIndex = 1)))
        val b = a.copy(messageNodes = listOf(a.messageNodes.single().copy(messages = listOf(text("partial more"), alternative))))
        assertNull(ContinuationStreamEnrichment.create(a, b))
        val tool = UIMessagePart.Tool("new-call", "write_file", "{}")
        assertNull(ContinuationStreamEnrichment.create(before, page(message.copy(parts = message.parts + tool))))
        assertNull(ContinuationStreamEnrichment.create(before, page(message.copy(parts = message.parts + UIMessagePart.Image("file:///private")))))
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return checkNotNull(value)
    }

    @Test fun `only monotone append of unfinished text can enrich checkpoint`() {
        assertNotNull(ContinuationStreamEnrichment.create(before, page(text("partial tail"))))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("divergent"))))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("part"))))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("partial tail").copy(id = Uuid.random()))))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("partial tail")).copy(compactionEpoch = 1)))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("partial tail")).copy(assistantId = Uuid.random())))
        assertNull(ContinuationStreamEnrichment.create(before, page(text("partial tail").copy(parts = listOf(UIMessagePart.Text("partial tail"))))))
        assertNull(ContinuationStreamEnrichment.create(page(message.copy(role = me.rerere.ai.core.MessageRole.USER)),
            page(text("partial tail").copy(role = me.rerere.ai.core.MessageRole.USER))))
        assertNull(ContinuationStreamEnrichment.create(page(message.copy(isSynthetic = true)), page(text("partial tail").copy(isSynthetic = true))))
    }
    @Test fun `projection preserves finished timer late input and all branch alternatives`() {
        val enrichment = ContinuationStreamEnrichment.create(before, page(text("partial tail")))!!
        val stopped = message.finishReasoning()
        val another = UIMessage.assistant("another version")
        val snapshot = before.copy(messageNodes = listOf(before.messageNodes.single().copy(
            messages = listOf(stopped, another), selectIndex = 1), UIMessage.user("new turn").toMessageNode()))
        val result = enrichment.project(snapshot)!!
        assertEquals(1, result.messageNodes.first().selectIndex)
        assertEquals(another, result.messageNodes.first().messages[1])
        assertEquals(snapshot.messageNodes.last(), result.messageNodes.last())
        assertEquals((stopped.parts.single() as UIMessagePart.Reasoning).finishedAt,
            (result.messageNodes.first().messages.first().parts.single() as UIMessagePart.Reasoning).finishedAt)
        assertEquals("partial tail", (result.messageNodes.first().messages.first().parts.single() as UIMessagePart.Reasoning).reasoning)
        assertEquals(result, enrichment.project(result))
        assertNull(enrichment.project(page(text("edited"))))
    }
    @Test fun `tool inputs and successful output changes are never eligible for stream merge`() {
        val tool = UIMessagePart.Tool("call", "write_file", "{}", output = listOf(UIMessagePart.Text("done")))
        val a = page(message.copy(parts = listOf(tool)))
        val b = page(message.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("different"))))))
        assertNull(ContinuationStreamEnrichment.create(a,b))
        assertNull(ContinuationStreamEnrichment.create(a,page(message.copy(parts=listOf(tool.copy(input="{\"changed\":true}"))))))
    }
}
