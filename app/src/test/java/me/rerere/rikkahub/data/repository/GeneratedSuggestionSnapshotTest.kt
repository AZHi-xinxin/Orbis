package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic immutable snapshots only; no database, provider, settings or network. */
class GeneratedSuggestionSnapshotTest {
    private fun original() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("synthetic question").toMessageNode(),
        UIMessage.assistant("synthetic answer").toMessageNode(),
    ))

    @Test fun displayMetadataChangesDoNotReplaceTheCurrentSnapshot() {
        val expected = original()
        val current = expected.copy(title = "new title", isPinned = true, folderId = Uuid.random())
        assertTrue(matchesGeneratedSuggestionSnapshot(expected, current))
        val published = current.copy(chatSuggestions = listOf("synthetic next question"))
        assertSame(current.messageNodes, published.messageNodes)
        assertEquals(current.title, published.title)
        assertEquals(current.folderId, published.folderId)
        assertTrue(published.isPinned)
    }

    @Test fun changedConversationOwnerEpochOrGenerationTimeIsRejected() {
        val expected = original()
        for (changed in listOf(expected.copy(id = Uuid.random()), expected.copy(assistantId = Uuid.random()),
            expected.copy(compactionEpoch = 1), expected.copy(updateAt = expected.updateAt.plusNanos(1)))) {
            assertFalse(matchesGeneratedSuggestionSnapshot(expected, changed))
        }
    }

    @Test fun newTurnOrRemovedHistoryIsRejectedEvenWithUnchangedEpochAndTimestamp() {
        val expected = original()
        assertFalse(matchesGeneratedSuggestionSnapshot(expected,
            expected.copy(messageNodes = expected.messageNodes + UIMessage.user("new turn").toMessageNode())))
        assertFalse(matchesGeneratedSuggestionSnapshot(expected,
            expected.copy(messageNodes = expected.messageNodes.drop(1))))
    }

    @Test fun historicalTextOrBranchChangesAreRejectedWithTheSameLastNode() {
        val expected = original()
        val first = expected.messageNodes.first()
        val alternative = UIMessage.user("edited question")
        for (changedFirst in listOf(first.copy(messages = listOf(alternative)),
            first.copy(messages = first.messages + alternative),
            first.copy(messages = first.messages + alternative, selectIndex = 1))) {
            assertFalse(matchesGeneratedSuggestionSnapshot(expected,
                expected.copy(messageNodes = listOf(changedFirst, expected.messageNodes.last()))))
        }
    }

    @Test fun delayedResponseCanNeverRevertHistoryOrOtherMetadata() {
        val request = original()
        val latest = request.copy(title = "latest", messageNodes = request.messageNodes +
            UIMessage.user("newer input").toMessageNode())
        val result = if (matchesGeneratedSuggestionSnapshot(request, latest))
            latest.copy(chatSuggestions = listOf("outdated")) else latest
        assertSame(latest, result)
        assertEquals(3, result.messageNodes.size)
        assertTrue(result.chatSuggestions.isEmpty())
    }
}
