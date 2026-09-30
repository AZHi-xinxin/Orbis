package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic histories only: no database, network, files or real conversation bodies. */
class ConversationStructuralSharingTest {
    private fun history(size: Int) = Conversation(
        assistantId = Uuid.random(), title = "synthetic long conversation", compactionEpoch = 7L,
        messageNodes = List(size) { index -> UIMessage.assistant("synthetic-$index").toMessageNode() },
    )

    @Test fun unchangedFiveThousandAndEightThousandFourHundredMessagesKeepTheWholeSnapshot() {
        listOf(5_000, 8_400).forEach { size ->
            val original = history(size)
            assertSame(original, original.updateCurrentMessages(original.currentMessages))
            assertSame(original, original.updateCurrentMessages(emptyList()))
        }
    }

    @Test fun streamedTailRetainsEveryUnchangedNodeAndBranchForBothLongHistorySizes() {
        listOf(5_000, 8_400).forEach { size ->
            val original = history(size)
            var current = original
            repeat(8) { chunk ->
                val messages = current.currentMessages.toMutableList()
                messages[messages.lastIndex] = messages.last().copy(parts = listOf(UIMessagePart.Text("chunk-$chunk")))
                val updated = current.updateCurrentMessages(messages)
                assertEquals(original.id, updated.id)
                assertEquals(original.assistantId, updated.assistantId)
                assertEquals(7L, updated.compactionEpoch)
                assertEquals(original.title, updated.title)
                for (index in 0 until size - 1) {
                    assertSame(original.messageNodes[index], updated.messageNodes[index])
                    assertSame(original.messageNodes[index].messages, updated.messageNodes[index].messages)
                }
                assertSame(messages.last(), updated.messageNodes.last().currentMessage)
                current = updated
            }
            assertEquals("synthetic-${size - 1}", original.currentMessages.last().toText())
        }
    }

    @Test fun aTransformerCanStillChangeAnEarlyHistoricalSlotTogetherWithTheTail() {
        val original = history(8_400)
        val messages = original.currentMessages.toMutableList()
        messages[3] = messages[3].copy(parts = listOf(UIMessagePart.Text("transformed historical message")))
        messages[messages.lastIndex] = messages.last().copy(parts = listOf(UIMessagePart.Text("new tail")))
        val updated = original.updateCurrentMessages(messages)
        assertSame(messages[3], updated.messageNodes[3].currentMessage)
        assertSame(messages.last(), updated.messageNodes.last().currentMessage)
        for (index in 0 until messages.lastIndex) {
            if (index != 3) assertSame(original.messageNodes[index], updated.messageNodes[index])
        }
        assertEquals("synthetic-3", original.messageNodes[3].currentMessage.toText())
    }

    @Test fun selectingAnExistingHiddenBranchReusesTheBranchListAndKeepsNodeMetadata() {
        val selected = UIMessage.assistant("selected")
        val hidden = UIMessage.assistant("hidden")
        val node = MessageNode(messages = listOf(selected, hidden), isFavorite = true)
        val original = history(1).copy(messageNodes = listOf(node))
        val updated = original.updateCurrentMessages(listOf(hidden))
        assertEquals(node.id, updated.messageNodes.single().id)
        assertTrue(updated.messageNodes.single().isFavorite)
        assertEquals(1, updated.messageNodes.single().selectIndex)
        assertSame(node.messages, updated.messageNodes.single().messages)
        assertSame(selected, updated.messageNodes.single().messages[0])
    }

    @Test fun editingOrAppendingABranchPreservesOtherBranchesAndOriginalSelection() {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val node = MessageNode(messages = listOf(first, second), selectIndex = 1, isFavorite = true)
        val original = history(1).copy(messageNodes = listOf(node))
        val changedFirst = first.copy(translation = "translation")
        val edited = original.updateCurrentMessages(listOf(changedFirst)).messageNodes.single()
        assertSame(changedFirst, edited.messages.first())
        assertSame(second, edited.messages[1])
        assertEquals(0, edited.selectIndex)
        val third = UIMessage.assistant("third")
        val appended = original.updateCurrentMessages(listOf(third)).messageNodes.single()
        assertEquals(node.id, appended.id)
        assertTrue(appended.isFavorite)
        assertSame(first, appended.messages[0])
        assertSame(second, appended.messages[1])
        assertSame(third, appended.messages[2])
        assertEquals(2, appended.selectIndex)
        assertEquals(1, node.selectIndex)
        assertEquals(2, node.messages.size)
    }

    @Test fun shorterUpdatesDoNotDeleteSuffixAndLongerUpdatesAppendExactlyOneNodePerMessage() {
        val original = history(5_000)
        val newFirst = original.currentMessages.first().copy(translation = "first translation")
        val shortened = original.updateCurrentMessages(listOf(newFirst))
        assertEquals(5_000, shortened.messageNodes.size)
        assertSame(original.messageNodes.last(), shortened.messageNodes.last())
        val additions = listOf(UIMessage.user("new user"), UIMessage.assistant("new answer"))
        val appended = original.updateCurrentMessages(original.currentMessages + additions)
        assertEquals(5_002, appended.messageNodes.size)
        original.messageNodes.indices.forEach { assertSame(original.messageNodes[it], appended.messageNodes[it]) }
        additions.forEachIndexed { index, message ->
            assertSame(message, appended.messageNodes[5_000 + index].messages.single())
        }
    }

    @Test fun aDistinctIncomingMessageObjectIsAcceptedWithoutDeepComparingHistoricalPayloads() {
        val parts = object : AbstractList<UIMessagePart>() {
            override val size: Int = 100_000
            override fun get(index: Int): UIMessagePart = error("historical payload must not be inspected")
        }
        val message = UIMessage.assistant("").copy(parts = parts)
        val original = history(1).copy(messageNodes = listOf(message.toMessageNode()))
        assertSame(original, original.updateCurrentMessages(listOf(message)))
        val replacement = message.copy()
        assertSame(replacement, original.updateCurrentMessages(listOf(replacement)).messageNodes.single().currentMessage)
    }

    @Test fun identityMapDoesNotCopyNoOpsAndVisitsChangesOutsideTheLastSlot() {
        val values = List(8_400) { Any() }
        assertSame(values, values.mapPreservingIdentity { it })
        val early = Any()
        val late = Any()
        val mapped = values.mapPreservingIdentity {
            when (it) { values[2] -> early; values.last() -> late; else -> it }
        }
        assertSame(early, mapped[2])
        assertSame(late, mapped.last())
        assertSame(values[0], mapped[0])
        assertNotSame(early, values[2])
        assertNotSame(late, values.last())
    }
}
