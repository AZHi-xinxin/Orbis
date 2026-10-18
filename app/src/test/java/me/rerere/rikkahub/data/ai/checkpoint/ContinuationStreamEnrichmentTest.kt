package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContinuationStreamEnrichmentTest {
    private val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Reasoning("partial", finishedAt = null)))
    private val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode()))
    private fun page(m: UIMessage) = before.copy(messageNodes = listOf(before.messageNodes.single().copy(messages = listOf(m))))
    private fun text(s: String) = message.copy(parts = listOf((message.parts.single() as UIMessagePart.Reasoning).copy(reasoning = s)))

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
