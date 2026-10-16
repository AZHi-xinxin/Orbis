package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LocalGenerationRecoveryPolicyTest {
    private fun saved() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("question").toMessageNode(), UIMessage.assistant("answer").toMessageNode()))
    @Test fun `exact saved page including favorite-only UI change may release stale local latch`() {
        val stored = saved()
        assertTrue(sameDurableGenerationSnapshot(stored, stored))
        assertTrue(sameDurableGenerationSnapshot(stored.copy(messageNodes = stored.messageNodes.map { it.copy(isFavorite = true) }), stored))
    }
    @Test fun `unsaved reply edited content and changed identity never count as a saved page`() {
        val stored = saved()
        listOf(stored.copy(id = Uuid.random()), stored.copy(assistantId = Uuid.random()),
            stored.copy(compactionEpoch = 1), stored.copy(messageNodes = stored.messageNodes.dropLast(1)),
            stored.copy(messageNodes = stored.messageNodes + UIMessage.user("not saved").toMessageNode()),
            stored.copy(messageNodes = stored.messageNodes.map { n -> n.copy(messages = n.messages.map {
                it.copy(parts = listOf(UIMessagePart.Text("changed"))) }) })).forEach {
            assertFalse(sameDurableGenerationSnapshot(it, stored))
        }
    }
}
