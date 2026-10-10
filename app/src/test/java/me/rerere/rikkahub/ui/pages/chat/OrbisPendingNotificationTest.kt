package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisPendingNotificationTest {
    private val conversation = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = emptyList())
    private val event = OrbisInboxEvent(eventId = "test", source = "lc_sentinel", text = "synthetic notification",
        wake = true, assistantId = conversation.assistantId.toString(), conversationId = conversation.id.toString(),
        receivedAt = 1, independentDelivery = true)

    @Test fun appearsBeforeModelAdmissionAndDeduplicatesDuringHistorySave() {
        assertEquals(listOf(event), orbisPendingConversationNotifications(listOf(event), conversation))
        val saved = conversation.copy(messageNodes = listOf(UIMessage.user(event.text).copy(id = Uuid.parse(event.id)).toMessageNode()))
        assertTrue(orbisPendingConversationNotifications(listOf(event), saved).isEmpty())
        assertTrue(orbisPendingConversationNotifications(listOf(event.copy(historyCommitted = true)), conversation).isEmpty())
    }

    @Test fun neverLeaksToAnotherAssistantOrChatAndDoesNotResurrectLegacy() {
        assertTrue(orbisPendingConversationNotifications(listOf(event), conversation.copy(assistantId = Uuid.random())).isEmpty())
        assertTrue(orbisPendingConversationNotifications(listOf(event), conversation.copy(id = Uuid.random())).isEmpty())
        assertTrue(orbisPendingConversationNotifications(listOf(event.copy(independentDelivery = false)), conversation).isEmpty())
        assertTrue(orbisPendingConversationNotifications(listOf(event.copy(state = "suppressed")), conversation).isEmpty())
    }

    @Test fun failedEntryRemainsVisibleWithoutHidingLaterPendingNotice() {
        val next = event.copy(id = Uuid.random().toString(), receivedAt = 2)
        val failed = event.copy(state = "failed", attemptStarted = true)
        assertEquals(listOf(failed, next), orbisPendingConversationNotifications(listOf(next, failed), conversation))
        assertTrue(independentNotificationLabel(failed).contains("后续通知继续"))
        assertTrue(independentNotificationLabel(next).contains("待 AI 处理"))
    }
}
