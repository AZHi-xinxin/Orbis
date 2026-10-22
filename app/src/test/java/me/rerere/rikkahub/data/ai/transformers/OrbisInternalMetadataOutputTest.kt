package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.OutputGenerationScope
import org.junit.Assert.*
import org.junit.Test

class OrbisInternalMetadataOutputTest {
    private val envelope = "<orbis_user_message_time>发送时刻：2026-10-22T23:14:27.35+08:00 [Asia/Shanghai]。此为本条人类消息的历史时点，不是当前时间。</orbis_user_message_time>"

    @Test fun `host envelope is removed without losing answer or echoed human prose`() {
        val text = "answer\nuser quoted prose\n${envelope}\nafter"
        assertEquals("answer\nuser quoted prose\n\nafter", hideOrbisTimeMetadata(UIMessage.assistant(text), false).toText())
    }
    @Test fun `every streaming split holds internal marker once identified and final text is clean`() {
        for (length in 7..envelope.length) {
            assertEquals("answer", hideOrbisTimeMetadata(UIMessage.assistant("answer" + envelope.take(length)), true).toText())
        }
        assertEquals("answer", hideOrbisTimeMetadata(UIMessage.assistant("answer" + envelope), false).toText())
    }
    @Test fun `split adjacent text parts preserve non-text parts and part metadata`() {
        for (split in 1 until envelope.length) {
            val tool = UIMessagePart.Tool("id", "tool", "{}", output = listOf(UIMessagePart.Text(envelope)))
            val source = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("before" + envelope.take(split)),
                UIMessagePart.Text(envelope.drop(split) + "after"), tool))
            val clean = hideOrbisTimeMetadata(source, false)
            assertEquals("beforeafter", clean.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            assertEquals(tool, clean.parts.last())
        }
    }
    @Test fun `user literals ordinary XML and non-host examples remain unchanged`() {
        val user = UIMessage.user(envelope)
        assertSame(user, hideOrbisTimeMetadata(user, true))
        for (literal in listOf("<orbis_user_message_time>user literal</orbis_user_message_time>", "<other>hello</other>",
            "<orbis_user_message_time>an example", "html <ol>")) {
            assertEquals(literal, hideOrbisTimeMetadata(UIMessage.assistant(literal), true).toText())
        }
    }
    @Test fun `new internal-only instruction and repeated envelopes are also removed`() {
        val next = envelope.replace("</orbis_user_message_time>", "仅供内部理解，不要在回复中复述本标签或其中的元数据。</orbis_user_message_time>")
        assertEquals("okay", hideOrbisTimeMetadata(UIMessage.assistant(envelope + "okay" + next), false).toText())
    }
    @Test fun `output scope does not rewrite old saved history or human content`() = runBlocking {
        val history = listOf(UIMessage.assistant(envelope), UIMessage.user(envelope))
        val response = UIMessage.assistant("new" + envelope)
        val output = OutputGenerationScope(history).apply(history + response) { active ->
            active.map { hideOrbisTimeMetadata(it, false) }
        }
        assertEquals(history, output.take(history.size))
        assertEquals(response.id, output.last().id)
        assertEquals("new", output.last().toText())
    }
}
