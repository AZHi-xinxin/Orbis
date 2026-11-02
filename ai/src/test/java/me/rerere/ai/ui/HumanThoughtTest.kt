package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class HumanThoughtTest {
    @Test fun `empty thought survives storage and projects into one human message`() {
        val original = UIMessage.user("正文").copy(parts = listOf(humanThoughtPart("  \n"), UIMessagePart.Text("正文")))
        val saved = json.decodeFromString<UIMessage>(json.encodeToString(original))
        assertEquals(original, saved)
        val sent = saved.projectHumanThought()
        assertEquals(original.id, sent.id)
        assertEquals(MessageRole.USER, sent.role)
        assertEquals("【人类思考链】\n（为空）\n\n【正文回复】\n正文", sent.toText())
        assertFalse(sent.parts.any { it is UIMessagePart.Reasoning })
        assertEquals("  \n", (original.parts.first() as UIMessagePart.Text).text)
    }

    @Test fun `history replay and retries do not accumulate labels or alter other turns`() {
        val history = listOf(UIMessage.system("stable system"), UIMessage.user("old"), UIMessage.assistant("reply"))
        val current = UIMessage.user("main").copy(parts = listOf(humanThoughtPart("aside"), UIMessagePart.Text("main"), UIMessagePart.Image("synthetic-image")))
        val sent = (history + current).projectHumanThoughts()
        assertEquals(history, sent.take(3))
        assertEquals(sent, sent.projectHumanThoughts())
        assertEquals(4, sent.size)
        assertEquals(current.parts.last(), sent.last().parts.last())
        assertTrue(current.toText().contains("【人类思考链】\naside"))
    }

    @Test fun `ordinary text with similar labels is not reinterpreted and assistant reasoning stays intact`() {
        val ordinary = UIMessage.user("【人类思考链】plain user prose")
        assertSame(ordinary, ordinary.projectHumanThought())
        val ai = UIMessage.assistant("answer").copy(parts = listOf(UIMessagePart.Reasoning("model thought"), humanThoughtPart("not a user")))
        assertSame(ai, ai.projectHumanThought())
    }
}
