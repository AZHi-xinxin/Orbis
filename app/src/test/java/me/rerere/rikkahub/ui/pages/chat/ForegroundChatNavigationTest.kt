package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.*
import org.junit.Test

class ForegroundChatNavigationTest {
    private val first = "71b83d76-9448-434d-b7d8-0f1fe155a0e0"
    private val second = "71b83d76-9448-434d-b7d8-0f1fe155a0e1"

    @Test fun returningToSameConversationDoesNotStackAnotherRoute() {
        assertEquals(ForegroundChatNavigation(first, false), foregroundChatNavigation(first, first))
        assertFalse(foregroundChatNavigation(first.uppercase(), first)!!.appendRoute)
    }

    @Test fun returningFromAnotherPageNavigatesToTheBoundConversation() {
        assertEquals(ForegroundChatNavigation(first, true), foregroundChatNavigation(first, second))
        assertTrue(foregroundChatNavigation(first, null)!!.appendRoute)
    }

    @Test fun missingOrMalformedIdNeverReachesUuidParseInsideChatPage() {
        listOf(null, "", "not-a-window", "../chat", "71b83d76").forEach {
            assertNull(foregroundChatNavigation(it, first))
        }
    }
}
