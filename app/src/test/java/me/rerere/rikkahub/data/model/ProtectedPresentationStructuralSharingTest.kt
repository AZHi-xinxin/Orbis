package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Protection invariants and object reuse under synthetic long-window streaming. */
class ProtectedPresentationStructuralSharingTest {
    private val now = LocalDateTime(2026, 9, 26, 15, 0)
    private val event = OrbisEventMetadata("synthetic-record", "lc_sentinel", "synthetic-event", 1_000L,
        occurredAt = 900L)
    private val deletedTool = UIMessagePart.Tool("synthetic-call", "synthetic-tool", "{}",
        listOf(UIMessagePart.Text("synthetic receipt")))

    private fun toolMessage() = UIMessage.assistant("synthetic prose").copy(
        toolRecordRevision = 2L, toolRecordsUpdatedAt = now,
        deletedToolRecords = listOf(DeletedToolRecord(deletedTool, 1, now)),
    )

    private fun history(count: Int) = Conversation(
        assistantId = Uuid.random(), compactionEpoch = 11L,
        messageNodes = List(count) { UIMessage.assistant("synthetic-$it").toMessageNode() },
    )

    private fun reject(block: () -> Unit) {
        try { block(); fail("protected mismatch must be rejected") } catch (_: IllegalStateException) { }
    }

    @Test fun unchangedProtectedNodeAndSharedMessageListDoNotReadHistoricalParts() {
        val unreadableParts = object : AbstractList<UIMessagePart>() {
            override val size = 100_000
            override fun get(index: Int): UIMessagePart = error("unchanged history payload must not be visited")
        }
        val message = toolMessage().copy(parts = unreadableParts, orbisEvent = event)
        val node = message.toMessageNode()
        assertSame(node, preserveToolRecordEdits(node, node))
        assertSame(node, preserveEventPresentation(node, node))
        val changedNodeMetadata = node.copy(isFavorite = true)
        assertSame(changedNodeMetadata, preserveToolRecordEdits(changedNodeMetadata, node))
        assertSame(changedNodeMetadata, preserveEventPresentation(changedNodeMetadata, node))
        val newBranchList = node.copy(messages = listOf(message))
        assertSame(newBranchList, preserveToolRecordEdits(newBranchList, node))
        assertSame(newBranchList, preserveEventPresentation(newBranchList, node))
    }

    @Test fun noProtectedFieldsKeepTheIncomingNodeAndConversationReferences() {
        listOf(5_000, 8_400).forEach { count ->
            val current = history(count)
            val incomingMessages = current.currentMessages.toMutableList()
            incomingMessages[2] = incomingMessages[2].copy(translation = "historical edit")
            incomingMessages[incomingMessages.lastIndex] = incomingMessages.last().copy(translation = "stream update")
            val incoming = current.updateCurrentMessages(incomingMessages)
            assertSame(incoming, withCommittedToolRecordEdits(incoming, current))
            assertSame(incoming, withCommittedEventPresentation(incoming, current))
            assertSame(incoming.messageNodes[2], preserveToolRecordEdits(incoming.messageNodes[2], current.messageNodes[2]))
            assertSame(incoming.messageNodes[2], preserveEventPresentation(incoming.messageNodes[2], current.messageNodes[2]))
            assertEquals(11L, incoming.compactionEpoch)
        }
    }

    @Test fun longHistoryWithProtectedEarlySlotsOnlyCopiesActuallyReconciledNodes() {
        listOf(5_000, 8_400).forEach { count ->
            val protectedTool = toolMessage()
            val protectedEvent = UIMessage.user("  synthetic event\nexact whitespace ").copy(
                orbisEvent = event.copy(read = true, collapsed = false),
            )
            val seed = history(count)
            val committed = seed.copy(messageNodes = seed.messageNodes.toMutableList().apply {
                set(31, protectedTool.toMessageNode())
                set(43, protectedEvent.toMessageNode())
            })
            val staleTool = protectedTool.copy(toolRecordRevision = 0L, deletedToolRecords = emptyList(),
                toolRecordsUpdatedAt = null, parts = protectedTool.parts + deletedTool, translation = "new translation")
            val staleEvent = protectedEvent.copy(orbisEvent = event)
            val messages = committed.currentMessages.toMutableList().apply {
                set(31, staleTool)
                set(43, staleEvent)
                set(lastIndex, last().copy(parts = listOf(UIMessagePart.Text("streaming answer"))))
            }
            val incoming = committed.updateCurrentMessages(messages)
            val merged = withCommittedEventPresentation(withCommittedToolRecordEdits(incoming, committed), committed)
            assertSame(protectedTool.parts, merged.messageNodes[31].currentMessage.parts)
            assertSame(protectedTool.deletedToolRecords, merged.messageNodes[31].currentMessage.deletedToolRecords)
            assertEquals("new translation", merged.messageNodes[31].currentMessage.translation)
            assertEquals(2L, merged.messageNodes[31].currentMessage.toolRecordRevision)
            assertEquals(protectedEvent.orbisEvent, merged.messageNodes[43].currentMessage.orbisEvent)
            assertEquals(11L, merged.compactionEpoch)
            incoming.messageNodes.indices.forEach { index ->
                if (index != 31 && index != 43) assertSame(incoming.messageNodes[index], merged.messageNodes[index])
            }
            assertSame(merged, withCommittedToolRecordEdits(merged, merged))
            assertSame(merged, withCommittedEventPresentation(merged, merged))
            assertSame(merged, withCommittedToolRecordEdits(merged, committed))
            assertSame(merged, withCommittedEventPresentation(merged, committed))
        }
    }

    @Test fun sameRevisionCannotReintroduceDeletedCallOrReplaceItsUndoMetadata() {
        val stored = toolMessage()
        val node = stored.toMessageNode()
        val incoming = node.copy(messages = listOf(stored.copy(parts = stored.parts + deletedTool,
            deletedToolRecords = emptyList(), toolRecordsUpdatedAt = null)))
        val merged = preserveToolRecordEdits(incoming, node).currentMessage
        assertEquals(stored.parts, merged.parts)
        assertSame(stored.deletedToolRecords, merged.deletedToolRecords)
        assertEquals(stored.toolRecordsUpdatedAt, merged.toolRecordsUpdatedAt)
        assertEquals(stored.toolRecordRevision, merged.toolRecordRevision)
    }

    @Test fun roleAndFutureRevisionStillRejectEvenWhenPartsAreTheSameReference() {
        val stored = toolMessage()
        val node = stored.toMessageNode()
        listOf(stored.copy(role = MessageRole.USER), stored.copy(toolRecordRevision = 3L)).forEach { invalid ->
            assertSame(stored.parts, invalid.parts)
            reject { preserveToolRecordEdits(node.copy(messages = listOf(invalid)), node) }
        }
    }

    @Test fun staleRevisionWithChangedProseStillRejectsInsteadOfDiscardingText() {
        val stored = toolMessage()
        val node = stored.toMessageNode()
        val stale = stored.copy(toolRecordRevision = 0L,
            parts = listOf(UIMessagePart.Text("conflicting prose"), deletedTool))
        reject { preserveToolRecordEdits(node.copy(messages = listOf(stale)), node) }
    }

    @Test fun noOpProtectedBranchesKeepTheirListAndUnrelatedBranchMetadata() {
        val stored = toolMessage()
        val unrelated = UIMessage.assistant("hidden branch")
        val node = MessageNode(messages = listOf(unrelated, stored), selectIndex = 1)
        val translated = stored.copy(translation = "updated translation")
        val incoming = node.copy(messages = listOf(unrelated, translated))
        assertSame(incoming, preserveToolRecordEdits(incoming, node))
        assertSame(unrelated, incoming.messages[0])
        assertEquals(1, incoming.selectIndex)
    }

    @Test fun eventFlagsAreNotTransferredAcrossAnyPayloadOrProvenanceDifference() {
        val message = UIMessage.user(" exact synthetic payload\n").copy(orbisEvent = event)
        val node = message.toMessageNode()
        val committed = node.copy(messages = listOf(message.copy(orbisEvent = event.copy(read = true, collapsed = false))))
        val changes = listOf(
            message.copy(id = Uuid.random()), message.copy(role = MessageRole.ASSISTANT),
            message.copy(parts = listOf(UIMessagePart.Text("exact synthetic payload"))),
            message.copy(orbisEvent = null),
            message.copy(orbisEvent = event.copy(recordId = "other")),
            message.copy(orbisEvent = event.copy(source = "other")),
            message.copy(orbisEvent = event.copy(eventId = "other")),
            message.copy(orbisEvent = event.copy(receivedAt = 1_001L)),
            message.copy(orbisEvent = event.copy(occurredAt = null)),
            message.copy(orbisEvent = event.copy(occurredAt = 901L)),
        )
        changes.forEach { changed ->
            val incoming = node.copy(messages = listOf(changed))
            assertSame(incoming, preserveEventPresentation(incoming, committed))
        }
        val foreignNode = node.copy(id = Uuid.random())
        assertSame(foreignNode, preserveEventPresentation(foreignNode, committed))
    }

    @Test fun unrelatedOwnerOrConversationCannotReceiveAnyProtectedMetadata() {
        val base = history(1).copy(messageNodes = listOf(toolMessage().copy(orbisEvent = event).toMessageNode()))
        val incoming = base.copy(messageNodes = listOf(base.messageNodes.single().copy(messages = listOf(
            base.currentMessages.single().copy(toolRecordRevision = 0L, deletedToolRecords = emptyList(),
                orbisEvent = event.copy(read = true, collapsed = false)),
        ))))
        listOf(incoming.copy(assistantId = Uuid.random()), incoming.copy(id = Uuid.random())).forEach { foreign ->
            assertSame(foreign, withCommittedToolRecordEdits(foreign, base))
            assertSame(foreign, withCommittedEventPresentation(foreign, base))
        }
    }

    @Test fun removedNodesAreNotResurrectedAndSurvivingEpochIsNotReplacedByTheMerge() {
        val base = history(2).copy(messageNodes = listOf(toolMessage().toMessageNode(),
            UIMessage.user("event").copy(orbisEvent = event).toMessageNode()))
        val removed = base.copy(messageNodes = emptyList(), compactionEpoch = 12L)
        assertSame(removed, withCommittedToolRecordEdits(removed, base))
        assertSame(removed, withCommittedEventPresentation(removed, base))
        assertEquals(12L, removed.compactionEpoch)
    }
}
