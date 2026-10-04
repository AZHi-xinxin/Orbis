package me.rerere.rikkahub.data.orbis.privateroom

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomSecondaryOutputTest {
    private fun toolMessage() = UIMessage.assistant("给人类的公开回复").copy(parts = listOf(
        UIMessagePart.Text("给人类的公开回复"),
        UIMessagePart.Reasoning("PRIVATE_REASONING"),
        UIMessagePart.Tool(toolCallId = "synthetic-private", toolName = "orbis_private_room_read",
            input = "PRIVATE_ARGUMENT", output = listOf(UIMessagePart.Text("PRIVATE_RESULT"))),
    ))

    @Test fun `automatic speech keeps public reply for hidden pending and private tool messages without tool details`() {
        val originals = listOf(
            UIMessage.assistant("公开回复一").copy(privateRoomContentHidden = true),
            UIMessage.assistant("公开回复二").copy(privateRoomPendingPresentation = true),
            toolMessage(),
        )
        originals.forEach { message ->
            val expected = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            assertEquals(expected, message.privateRoomAutomaticSpeechText())
            assertEquals(expected, message.privateRoomSafePresentation().privateRoomAutomaticSpeechText())
            assertFalse(message.privateRoomAutomaticSpeechText()!!.contains("PRIVATE_"))
        }
        assertTrue(originals.last().parts.any { it is UIMessagePart.Tool })
        assertTrue(originals.last().parts.any { it is UIMessagePart.Reasoning })
    }

    @Test fun `ordinary assistant speech remains exact and human text is not automatically spoken`() {
        assertEquals("公开的回复", UIMessage.assistant("公开的回复").privateRoomAutomaticSpeechText())
        assertNull(UIMessage.user("人类的话").privateRoomAutomaticSpeechText())
    }

    @Test fun `public summary preserves private turn public Text and recent message limit`() {
        val first = UIMessage.user("最早的公开内容")
        val last = UIMessage.assistant("最近的公开内容")
        val pending = UIMessage.assistant("正在给人的回复").copy(privateRoomPendingPresentation = true)
        val messages = listOf(first, last, toolMessage(), pending)
        assertEquals(pending.summaryAsText(), privateRoomPublicSummaryInput(messages, maxMessages = 1))
        assertEquals(UIMessage.assistant("给人类的公开回复").summaryAsText() + "\n\n" + pending.summaryAsText(),
            privateRoomPublicSummaryInput(messages, maxMessages = 2))
        assertFalse(privateRoomPublicSummaryInput(messages).contains("PRIVATE_"))
        assertTrue(messages[2].parts.any { it is UIMessagePart.Tool })
    }

    @Test fun `tool and reasoning without public Text produce no automatic speech or public generation input`() {
        val toolOnly = toolMessage().copy(parts = toolMessage().parts.filterNot { it is UIMessagePart.Text })
        val pendingOnly = UIMessage.assistant("").copy(privateRoomPendingPresentation = true,
            parts = listOf(UIMessagePart.Reasoning("PRIVATE_PENDING_REASONING")))
        assertNull(toolOnly.privateRoomAutomaticSpeechText())
        assertNull(pendingOnly.privateRoomAutomaticSpeechText())
        assertEquals("", privateRoomPublicSummaryInput(listOf(toolOnly, pendingOnly)))
        assertEquals("", privateRoomPublicSummaryInput(emptyList()))
    }

    @Test fun `ordinary prose mentioning tool names is not treated as a private tool invocation`() {
        val message = UIMessage.assistant("我没有调用 orbis_private_room_read")
        assertEquals(message.toText(), message.privateRoomAutomaticSpeechText())
        assertEquals(message.summaryAsText(), privateRoomPublicSummaryInput(listOf(message)))
    }

    @Test fun `public summary keeps the existing per-message length boundary`() {
        val message = UIMessage.assistant("公开长句").copy(translation = "not a summary input")
        assertEquals(message.summaryAsText(maxLength = 5),
            privateRoomPublicSummaryInput(listOf(message), maxLength = 5))
        assertEquals("公开长句", message.toText())
    }

    @Test fun `think-only closed unclosed and partial private Text never becomes a placeholder in secondary output`() {
        val texts = listOf("<think>PRIVATE_THOUGHT</think>", "<think>PRIVATE_THOUGHT", "<", "<thi", " <think")
        for (text in texts) {
            val source = toolMessage().copy(parts = toolMessage().parts.filterNot { it is UIMessagePart.Text } +
                UIMessagePart.Text(text), privateRoomPendingPresentation = true)
            assertNull(source.privateRoomPublicReplyText())
            assertNull(source.privateRoomAutomaticSpeechText())
            assertEquals("", privateRoomPublicSummaryInput(listOf(source)))
            assertTrue(source.parts.filterIsInstance<UIMessagePart.Text>().single().text == text)
        }
    }

    @Test fun `public prose after think is preserved but thought and metadata never become speech or summary`() {
        val source = toolMessage().copy(parts = toolMessage().parts.filterNot { it is UIMessagePart.Text } +
            UIMessagePart.Text("<think>PRIVATE_THOUGHT</think>这是给你的回复"))
        assertEquals("这是给你的回复", source.privateRoomAutomaticSpeechText())
        assertEquals(UIMessage.assistant("这是给你的回复").summaryAsText(), privateRoomPublicSummaryInput(listOf(source)))
        assertTrue(source.toText().contains("PRIVATE_THOUGHT"))
    }

    @Test fun `a genuine public reply identical to the visual notice is not discarded by string comparison`() {
        val source = UIMessage.assistant(PRIVATE_ROOM_CONTENT_HIDDEN).copy(privateRoomContentHidden = true)
        assertEquals(PRIVATE_ROOM_CONTENT_HIDDEN, source.privateRoomAutomaticSpeechText())
        assertEquals(source.summaryAsText(), privateRoomPublicSummaryInput(listOf(source)))
    }

    @Test fun `summary applies recent limit after removing think-only messages rather than selecting a notice`() {
        val public = UIMessage.assistant("保留最后一条真正正文")
        val thought = UIMessage.assistant("<think>PRIVATE_ONLY</think>").copy(privateRoomContentHidden = true)
        assertEquals(public.summaryAsText(), privateRoomPublicSummaryInput(listOf(public, thought), maxMessages = 1))
    }
}
