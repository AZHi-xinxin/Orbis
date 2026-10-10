package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic inbox projection only. No model, real chat, phone observer or Koin services. */
@RunWith(AndroidJUnit4::class)
class OrbisPendingNotificationCardTest {
    @get:Rule val compose = createShellComposeRule()
    private val conversation = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = emptyList())
    private val event = OrbisInboxEvent(eventId = "synthetic", source = "lc_sentinel", text = "合成通知原文",
        wake = true, assistantId = conversation.assistantId.toString(), conversationId = conversation.id.toString(),
        receivedAt = 1, independentDelivery = true)

    @Test fun pendingCardIsVisibleThenChangesOnlyItsOwnFailureLabel() {
        val events = mutableStateOf(listOf(event))
        compose.setContent { MaterialTheme { Column {
            orbisPendingConversationNotifications(events.value, conversation).forEach { OrbisPendingNotificationCard(it) }
        } } }
        compose.onNodeWithText("合成通知原文").assertIsDisplayed()
        compose.onNodeWithText("哨兵通知已到 · 待 AI 处理").assertIsDisplayed()
        compose.runOnIdle { events.value = listOf(event.copy(state = "failed", attemptStarted = true),
            event.copy(id = "second", text = "下一条合成通知", receivedAt = 2)) }
        compose.onNodeWithText("本条回复未完成", substring = true).assertIsDisplayed()
        compose.onNodeWithText("下一条合成通知").assertIsDisplayed()
        compose.onNodeWithText("哨兵通知已到 · 待 AI 处理").assertIsDisplayed()
    }

    @Test fun committedHistoryAndOtherOwnersDoNotLeaveGhostCards() {
        val events = mutableStateOf(listOf(event))
        compose.setContent { MaterialTheme { Column {
            orbisPendingConversationNotifications(events.value, conversation).forEach { OrbisPendingNotificationCard(it) }
        } } }
        compose.onNodeWithTag("sentinel-notice-${event.id}").assertExists()
        compose.runOnIdle { events.value = listOf(event.copy(historyCommitted = true),
            event.copy(id = "foreign", assistantId = Uuid.random().toString())) }
        compose.onNodeWithTag("sentinel-notice-${event.id}").assertDoesNotExist()
        compose.onNodeWithTag("sentinel-notice-foreign").assertDoesNotExist()
    }
}
