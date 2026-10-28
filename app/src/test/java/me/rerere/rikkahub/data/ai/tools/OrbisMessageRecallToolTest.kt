package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisMessageRecallToolTest {
    @Test fun `twenty AI replies excludes humans and tool only steps and indices stay fixed`() = runBlocking {
        val ai = (1..23).map { UIMessage.assistant("secret-$it") }
        val c = Conversation(assistantId = Uuid.random(), messageNodes = ai.flatMap { listOf(
            it.toMessageNode(), UIMessage.user("human").toMessageNode(), UIMessage.assistant("").toMessageNode()) })
        val targets = c.recallableAiReplies().map { it.id }
        assertEquals(20, targets.size); assertEquals(ai.last().id, targets.first()); assertEquals(ai[3].id, targets.last())
        val called = mutableListOf<Uuid>()
        val tool = createMessageRecallTool(targets) { called += it; true }
        suspend fun call(args: String) = tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        assertEquals("{\"success\":true}", call("{\"recent_index\":1}").text)
        assertEquals("{\"success\":true}", call("{\"recent_index\":1}").text)
        assertEquals(listOf(ai.last().id, ai.last().id), called)
        for (args in listOf("{}", "{\"recent_index\":21}", "{\"recent_index\":0}", "{\"recent_index\":\"1\"}", "{\"recent_index\":1,\"message_id\":\"human\"}"))
            assertEquals("{\"success\":false}", call(args).text)
        assertEquals(2, called.size)
    }
    @Test fun `failure never returns sensitive exception or recalled body`() = runBlocking {
        val tool = createMessageRecallTool(listOf(Uuid.random())) { error("private text and secret") }
        assertEquals(listOf(UIMessagePart.Text("{\"success\":false}")), tool.execute(buildJsonObject { put("recent_index", 1) }))
    }
    @Test fun `human recall unlimited and presentation strips every payload channel`() {
        val old = UIMessage.user("old secret").copy(translation = "secret translation", parts = listOf(
            UIMessagePart.Text("old secret"), UIMessagePart.Image("file:///private.png")))
        val c = Conversation(assistantId = Uuid.random(), messageNodes = (listOf(old) +
            (1..40).map { UIMessage.assistant("reply-$it") }).map { it.toMessageNode() })
        val saved = c.withMessageRecall(old.id, MessageRole.USER)
        val projected = saved.currentMessages.first().recallModelInput()
        assertEquals(HUMAN_RECALL_NOTICE, projected.toText()); assertEquals(1, projected.parts.size); assertNull(projected.translation)
        assertTrue(runCatching { c.withMessageRecall(old.id, MessageRole.ASSISTANT) }.isFailure)
    }

    @Test fun `recalled AI reply keeps own text but never a revoked quoted payload`() {
        val original = UIMessage.assistant("AI original").copy(orbisRecalled = true, orbisQuoteRecalled = true,
            parts = listOf(UIMessagePart.Text("AI original"), UIMessagePart.Text("quoted human secret",
                metadata = buildJsonObject { put("orbis_quoted_context_v1", true) })))
        val input = original.recallModelInput()
        assertEquals("AI original\n本消息已被你撤回，现在人类无法看到此条消息内容。", input.toText())
        assertEquals(AI_RECALL_NOTICE, original.recallPresentation().toText())
    }
}
