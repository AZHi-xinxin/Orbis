package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class HumanContinuationOrdinaryInputHandoffTest {
    private class Store : GenerationCheckpointStore {
        val values = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = values[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { values[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { values.remove(conversationId) }
    }
    private fun baseline() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = listOf(UIMessage.user("old input").toMessageNode(), UIMessage.assistant("old reply").toMessageNode()))

    @Test fun `cancelled first completion keeps both inputs and bridges only the recorded Room commit`() = runTest {
        val base = baseline()
        val firstInput = UIMessage.user("first accepted input").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + firstInput)
        val handoff = HumanContinuationOrdinaryInputHandoff(base, candidate, firstInput.currentMessage.id)
        var pending: HumanContinuationOrdinaryInputHandoff? = handoff
        var disk = base; var published = base
        val entered = CompletableDeferred<Unit>(); val receipt = CompletableDeferred<Unit>()
        val mutex = Mutex()
        val ordinary = async {
            try {
                mutex.withLock {
                    commitConversationBeforePublish(commit = {
                        disk = candidate
                        entered.complete(Unit); receipt.await()
                    }, publish = { published = candidate })
                }
            } finally { pending = null }
        }
        entered.await()
        val captured = checkNotNull(pending)
        val journal = HumanContinuationJournal(Store())
        lateinit var lane: HumanContinuationLane
        val storage = HumanContinuationContextStore(base, base, Uuid.random(), mutex, journal,
            read = { disk }, proveBaseline = captured::provesCommittedInput,
            commit = { expected, next -> if (sameDurableGenerationSnapshot(expected, disk)) next.also { disk = it } else null },
            canWrite = { true }, project = { lane.projectContext(it) }, latest = { lane.state.value },
            onStored = {}, onRebased = { a, b -> lane.acceptCompaction(a, b) }, retireOldJournal = {})
        lane = HumanContinuationLane(base, backgroundScope, { false }, { throw it },
            initial = checkNotNull(captured.initialFor(published)), contextStore = storage)
        lane.send(UIMessage.user("second accepted input"), answer = false) { _, _, _ -> error("no model or tool") }.join()
        ordinary.cancelAndJoin()
        assertNull(pending)
        assertEquals(base.messageNodes, published.messageNodes)
        assertEquals(listOf("first accepted input", "second accepted input"), lane.state.value.currentMessages.takeLast(2).map { it.toText() })
        assertFalse(sameDurableGenerationSnapshot(base, disk))
        storage.checkpoint(lane.state.value)
        assertTrue(storage.save(lane.state.value))
        assertEquals(lane.state.value.messageNodes, disk.messageNodes)
        lane.send(UIMessage.user("third accepted input"), answer = false) { _, _, _ -> error("no model or tool") }.join()
        assertTrue(storage.save(lane.state.value))
        assertEquals(listOf("first accepted input", "second accepted input", "third accepted input"), disk.currentMessages.takeLast(3).map { it.toText() })
    }

    @Test fun `rolled back first write saves both inputs against the unchanged durable anchor`() = runTest {
        val base = baseline(); var disk = base
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        val handoff = HumanContinuationOrdinaryInputHandoff(base, candidate, first.currentMessage.id)
        val latest = checkNotNull(handoff.initialFor(base)).let { it.copy(messageNodes = it.messageNodes + UIMessage.user("second").toMessageNode()) }
        assertNotNull(commitHumanContinuationSnapshot(base, latest, { disk }, handoff::provesCommittedInput) { expected, next ->
            if (sameDurableGenerationSnapshot(expected, disk)) next.also { disk = it } else null
        })
        assertEquals(latest.messageNodes, disk.messageNodes)
    }

    @Test fun `independent checkpoint reopens against either first write outcome without an old generation journal`() {
        val base = baseline()
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        val latest = candidate.copy(messageNodes = candidate.messageNodes + UIMessage.user("second").toMessageNode())
        for (disk in listOf(base, candidate)) {
            val store = Store(); val journal = HumanContinuationJournal(store)
            journal.checkpoint(Uuid.random(), base, latest)
            val recovered = checkNotNull(HumanContinuationJournal(store).recover(disk))
            assertEquals(latest.messageNodes, recovered.snapshot.messageNodes)
            assertEquals(disk.messageNodes, checkNotNull(recovered.durableBaseline).messageNodes)
        }
    }

    @Test fun `already published input is not seeded twice and current metadata is retained`() {
        val base = baseline()
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        val handoff = HumanContinuationOrdinaryInputHandoff(base, candidate, first.currentMessage.id)
        assertNull(handoff.initialFor(candidate))
        assertEquals("new title", checkNotNull(handoff.initialFor(base.copy(title = "new title"))).title)
        assertEquals(candidate.messageNodes, checkNotNull(handoff.initialFor(base)).messageNodes)
    }

    @Test fun `bridge refuses changed input prefix owner epoch and another subsequent write`() {
        val base = baseline()
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        val handoff = HumanContinuationOrdinaryInputHandoff(base, candidate, first.currentMessage.id)
        assertTrue(handoff.provesCommittedInput(candidate, base))
        for (changed in listOf(
            candidate.copy(assistantId = Uuid.random()), candidate.copy(compactionEpoch = 1L),
            candidate.copy(messageNodes = candidate.messageNodes + UIMessage.user("another commit").toMessageNode()),
            candidate.copy(messageNodes = candidate.messageNodes.dropLast(1) + first.copy(messages = listOf(
                first.currentMessage.copy(parts = listOf(UIMessagePart.Text("edited first input")))))),
        )) assertFalse(handoff.provesCommittedInput(changed, base))
        assertFalse(handoff.provesCommittedInput(candidate, base.copy(messageNodes = base.messageNodes.dropLast(1))))
    }

    @Test fun `concurrent history edit still wins the final CAS after handoff proof`() = runTest {
        val base = baseline()
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        val handoff = HumanContinuationOrdinaryInputHandoff(base, candidate, first.currentMessage.id)
        val latest = candidate.copy(messageNodes = candidate.messageNodes + UIMessage.user("second").toMessageNode())
        var disk = candidate
        assertNull(commitHumanContinuationSnapshot(base, latest, { disk }, handoff::provesCommittedInput) { expected, next ->
            disk = disk.copy(messageNodes = disk.messageNodes + UIMessage.user("unrelated edit").toMessageNode())
            if (sameDurableGenerationSnapshot(expected, disk)) next.also { disk = it } else null
        })
        assertEquals("unrelated edit", disk.currentMessages.last().toText())
    }

    @Test fun `handoff refuses synthetic events mismatched IDs and changed old history`() {
        val base = baseline()
        val first = UIMessage.user("first").toMessageNode()
        val candidate = base.copy(messageNodes = base.messageNodes + first)
        assertTrue(runCatching { HumanContinuationOrdinaryInputHandoff(base, candidate, Uuid.random()) }.isFailure)
        assertTrue(runCatching { HumanContinuationOrdinaryInputHandoff(base,
            candidate.copy(messageNodes = candidate.messageNodes.dropLast(1) + first.copy(messages = listOf(first.currentMessage.copy(isSynthetic = true)))),
            first.currentMessage.id) }.isFailure)
        assertTrue(runCatching { HumanContinuationOrdinaryInputHandoff(base,
            candidate.copy(messageNodes = listOf(UIMessage.user("replaced history").toMessageNode()) + candidate.messageNodes.drop(1)),
            first.currentMessage.id) }.isFailure)
    }
}
