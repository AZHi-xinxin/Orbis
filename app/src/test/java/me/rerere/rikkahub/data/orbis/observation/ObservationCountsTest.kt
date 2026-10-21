package me.rerere.rikkahub.data.orbis.observation

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConsultationConversationBinding
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisMessageBatchOperation
import me.rerere.rikkahub.data.model.prepareOrbisMessageBatch
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.memory.latestMemoryHuman
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Immutable synthetic records only; no app state, provider, database or counters. */
class ObservationCountsTest {
    private fun conversation(vararg nodes: MessageNode) =
        Conversation(assistantId = Uuid.random(), messageNodes = nodes.toList())

    @Test fun batchDeleteCountsAllAlternativesNotJustSelectedNodes() {
        val human = UIMessage.user("human").toMessageNode()
        val replies = MessageNode(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two")), selectIndex = 1)
        val retained = UIMessage.user("retained").toMessageNode()
        val before = conversation(human, replies, retained)
        val preview = prepareOrbisMessageBatch(before, setOf(human.id, replies.id), OrbisMessageBatchOperation.DELETE)
        assertEquals(2, preview.affectedCount)
        assertEquals(3L, observedBatchDeletedCount(before, preview))
    }

    @Test fun archiveLeavesDeletionCountZeroEvenWhenItMovesEveryMessage() {
        val before = conversation(UIMessage.user("a").toMessageNode(), UIMessage.assistant("b").toMessageNode())
        val preview = prepareOrbisMessageBatch(before, before.messageNodes.map { it.id }.toSet(),
            OrbisMessageBatchOperation.ARCHIVE)
        assertEquals(0L, observedBatchDeletedCount(before, preview))
    }

    @Test fun storedRowsAfterSingleDeletionDoNotCountStaleUiMessageAgain() {
        val first = UIMessage.user("already removed")
        val second = UIMessage.assistant("still stored")
        val oldUiSnapshot = listOf(first, second)
        val storedAtDeleteTransaction = oldUiSnapshot.filterNot { it.id == first.id }
        assertEquals(2L, observedMessageCount(oldUiSnapshot))
        assertEquals(1L, observedMessageCount(storedAtDeleteTransaction))
        assertEquals(0L, observedMessageCount(emptyList()))
    }

    @Test fun newerStoredBranchesCountAndProtocolRowsAreNotHumanMessages() {
        val stored = listOf(UIMessage.user("a"), UIMessage.assistant("b"), UIMessage.assistant("new branch"),
            UIMessage.system("system"), UIMessage(role = MessageRole.TOOL, parts = emptyList()))
        assertEquals(3L, observedMessageCount(stored))
    }

    @Test fun refreshWriterAndReportShareHumanAnchorAcrossSyntheticAndDeviceEvents() {
        val human = UIMessage.user("human")
        val reminder = UIMessage.user("event").copy(orbisEvent =
            OrbisEventMetadata("synthetic-record", "synthetic-source", "synthetic-event", 1L))
        val synthetic = UIMessage.user("synthetic").copy(isSynthetic = true)
        val voiceEvent = UIMessage.user("call start").copy(orbisVoiceCallId = "synthetic-call", orbisVoiceCallKind = "start")
        val answer = UIMessage.assistant("answer")
        val before = conversation(*listOf(human, reminder, synthetic, voiceEvent, answer).map { it.toMessageNode() }.toTypedArray())
        assertEquals(human.id, observedRegenerationHuman(before, answer.id)?.id)
        assertEquals(latestMemoryHuman(before.currentMessages)?.id, observedRegenerationHuman(before, answer.id)?.id)
    }

    @Test fun earlierReplyRefreshDoesNotBorrowALaterHumansCounter() {
        val early = UIMessage.user("earlier")
        val answer = UIMessage.assistant("answer")
        val later = UIMessage.user("later")
        val before = conversation(early.toMessageNode(), answer.toMessageNode(), later.toMessageNode())
        assertEquals(early.id, observedRegenerationHuman(before, answer.id)?.id)
        assertNull(observedRegenerationHuman(before, Uuid.random()))
        val orphan = UIMessage.assistant("no human")
        assertNull(observedRegenerationHuman(conversation(orphan.toMessageNode()), orphan.id))
    }

    @Test fun consultationMessagesDoNotEnterOrdinaryObservationCounters() {
        val human = UIMessage.user("synthetic human")
        val before = conversation(human.toMessageNode())
        val preview = prepareOrbisMessageBatch(before, setOf(before.messageNodes.single().id), OrbisMessageBatchOperation.DELETE)
        val consultation = before.copy(consultation = ConsultationConversationBinding("synthetic-session", "synthetic"))
        assertEquals(0L, observedBatchDeletedCount(consultation, preview))
        assertNull(observedRegenerationHuman(consultation, human.id))
    }
}
