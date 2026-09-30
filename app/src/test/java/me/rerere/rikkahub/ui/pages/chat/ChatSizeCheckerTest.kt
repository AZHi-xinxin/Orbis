package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSizeCheckerTest {
    @Test fun newestAssistantStopsTheScanBeforeThousandsOfHistoricalNodes() {
        listOf(5_000, 8_400).forEach { count ->
            var reads = 0
            val latest = UIMessage.assistant("synthetic last response").copy(
                usage = TokenUsage(promptTokens = 400_000),
            ).toMessageNode()
            val nodes = object : AbstractList<MessageNode>() {
                override val size = count
                override fun get(index: Int): MessageNode {
                    reads++
                    check(index == count - 1) { "must not scan older nodes after finding the newest assistant" }
                    return latest
                }
            }
            val info = conversationSizeInfo(Conversation(assistantId = Uuid.random(), messageNodes = nodes))
            assertEquals(count, info.nodeCount)
            assertEquals(400_000, info.lastAssistantInputTokens)
            assertTrue(info.showWarning)
            assertEquals(1, reads)
        }
    }

    @Test fun latestAssistantWithMissingUsageDoesNotBorrowAnOlderCompletedResponse() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.assistant("old").copy(usage = TokenUsage(promptTokens = 400_000)).toMessageNode(),
            UIMessage.assistant("new unfinished").toMessageNode(),
            UIMessage.user("after response").toMessageNode(),
        ))
        assertEquals(0, conversationSizeInfo(conversation).lastAssistantInputTokens)
        assertFalse(conversationSizeInfo(conversation).showWarning)
    }

    @Test fun emptyAndUserOnlyConversationsKeepTheExistingZeroUsageSemantics() {
        val empty = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        assertEquals(0, conversationSizeInfo(empty).nodeCount)
        assertEquals(0, conversationSizeInfo(empty).lastAssistantInputTokens)
        val userOnly = empty.copy(messageNodes = listOf(UIMessage.user("synthetic").toMessageNode()))
        assertEquals(0, conversationSizeInfo(userOnly).lastAssistantInputTokens)
    }
}
