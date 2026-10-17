package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConsultationConversationBinding
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatGenerationDoneTest {
    private fun reply(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
    private fun chat(vararg messages: UIMessage) = Conversation(assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() })

    @Test fun completionCarriesExactSnapshotNotThePreviouslyDisplayedMessage() {
        val old = reply("old")
        val current = reply("new")
        val conversation = chat(old, current)
        val done = requireNotNull(completedSpeechReply(conversation, true))
        assertEquals(current.id, done.messageId)
        assertEquals("new", done.text)
        assertTrue(done.matches(conversation))
        assertFalse(done.matches(conversation.copy(messageNodes = listOf(old.toMessageNode()))))
        assertFalse(done.matches(conversation.copy(id = Uuid.random())))
        assertFalse(done.matches(conversation.copy(messageNodes = listOf(current.copy(parts =
            listOf(UIMessagePart.Text("edited"))).toMessageNode()))))
    }

    @Test fun incompleteOrToolWaitingOrVoiceOrConsultationNeverSpeaks() {
        val answer = reply("answer")
        assertNull(completedSpeechReply(chat(answer), false))
        assertNull(completedSpeechReply(chat(answer.copy(orbisVoiceCallId = "call")), true))
        assertNull(completedSpeechReply(chat(answer).copy(consultation =
            ConsultationConversationBinding("session", "subject")), true))
        assertNull(completedSpeechReply(chat(answer.copy(role = MessageRole.USER)), true))
        assertNull(completedSpeechReply(chat(reply("  ")), true))
        val tool = UIMessagePart.Tool("tool-id", "tool-name", "{}")
        val waiting = answer.copy(parts = listOf(tool))
        assertNull(completedSpeechReply(chat(waiting, answer), true))
        assertNotNull(completedSpeechReply(chat(waiting.copy(parts = listOf(tool.copy(output =
            listOf(UIMessagePart.Text("tool output must not be read"))))), answer), true))
    }

    @Test fun oldPendingToolDoesNotSuppressACompletedFreshHumanTurn() {
        val waiting = reply("").copy(parts = listOf(UIMessagePart.Tool("old", "tool", "{}")))
        val human = reply("new question").copy(role = MessageRole.USER)
        assertEquals("new answer", completedSpeechReply(chat(waiting, human, reply("new answer")), true)?.text)
    }

    @Test fun speechOwnershipAndDuplicateEventsNeverCauseReplay() {
        val event = requireNotNull(completedSpeechReply(chat(reply("new")), true))
        val delivery = AutomaticSpeechDelivery()
        assertTrue(delivery.claim(event, true, true, false, false))
        assertFalse(delivery.claim(event, true, true, false, false))
        for (gate in 0..3) {
            val next = event.copy(messageId = Uuid.random())
            assertFalse(delivery.claim(next, gate != 0, gate != 1, gate == 2, gate == 3))
            assertFalse(delivery.claim(next, true, true, false, false))
        }
    }
}
