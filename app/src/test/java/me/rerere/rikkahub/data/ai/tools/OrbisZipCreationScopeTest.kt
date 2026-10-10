package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisZipCreationScopeTest {
    private val owner = Uuid.random()
    private val historical = UIMessage.assistant("earlier")
    private val user = UIMessage.user("make a zip")
    private val live = Conversation(assistantId = owner, messageNodes = listOf(historical, user).map { it.toMessageNode() })
    private val source = setOf(historical.id, user.id)
    private fun permits(value: Conversation?) = isZipCreationScopeCurrent(owner, live.id, user.id, source, value)

    @Test fun exactConversationAndOwnerAreRequired() {
        assertTrue(permits(live))
        assertFalse(permits(null))
        assertFalse(permits(live.copy(id = Uuid.random())))
        assertFalse(permits(live.copy(assistantId = Uuid.random())))
    }

    @Test fun newUserTurnInvalidatesEvenIfAllOldSourceIdsRemain() {
        assertFalse(permits(live.copy(messageNodes = live.messageNodes + UIMessage.user("new turn").toMessageNode())))
    }

    @Test fun selectingDifferentHistoricalBranchInvalidatesWithSameLastUser() {
        val first = live.messageNodes.first()
        val other = first.copy(messages = first.messages + UIMessage.assistant("other branch"), selectIndex = 1)
        assertFalse(permits(live.copy(messageNodes = listOf(other, live.messageNodes.last()))))
    }

    @Test fun sameRunAssistantAndToolOutputsMayBeAppended() {
        assertTrue(permits(live.copy(messageNodes = live.messageNodes + UIMessage.assistant("tool is running").toMessageNode())))
        assertTrue(permits(live.copy(title = "renamed")))
    }

    @Test fun sourceRemovalAndCorruptSelectionFailClosed() {
        assertFalse(permits(live.copy(messageNodes = live.messageNodes.drop(1))))
        assertFalse(permits(live.copy(messageNodes = listOf(live.messageNodes.first().copy(selectIndex = 99), live.messageNodes.last()))))
    }
}
