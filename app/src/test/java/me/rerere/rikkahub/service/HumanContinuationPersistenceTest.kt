package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Real production commit coordinator + journals, synthetic CAS repository, no network/tools. */
class HumanContinuationPersistenceTest {
    private class Store : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes.remove(conversationId) }
    }
    private class Fixture {
        val store = Store()
        val journal = GenerationCheckpointJournal(store)
        val disk = Conversation(assistantId = Uuid.random(), title = "synthetic", messageNodes = listOf(
            UIMessage.user("old question").toMessageNode(), UIMessage.assistant("old answer").toMessageNode(),
            UIMessage.user("MCP request").toMessageNode()))
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("thinking", finishedAt = null),
            UIMessagePart.Tool("synthetic-call", "synthetic_mcp", "{}"))).toMessageNode()
        val visible = disk.copy(messageNodes = disk.messageNodes + pending)
        val continued = visible.copy(messageNodes = visible.messageNodes.map { n ->
            n.copy(messages = n.messages.map { it.finishInterruptedHostTools().finishReasoning() })
        } + UIMessage.user("new input").toMessageNode() + UIMessage.assistant("new answer").toMessageNode())
        var stored = disk
        var writes = 0
        init {
            val h = journal.begin(disk)
            journal.checkpoint(h, disk.assistantId, disk.compactionEpoch, disk.messageNodes.takeLast(1) + pending,
                GenerationToolTransition("synthetic-call", "synthetic_mcp", GenerationToolStatus.STARTED))
        }
        suspend fun commit(expected: Conversation, snapshot: Conversation): Conversation? {
            if (!sameDurableGenerationSnapshot(expected, stored)) return null
            writes++
            stored = stored.copy(messageNodes = snapshot.messageNodes)
            return stored
        }
        suspend fun save(expected: Conversation = visible, snapshot: Conversation = continued) =
            commitHumanContinuationSnapshot(expected, snapshot, { stored }, journal::provesInFlightBaseline, ::commit)
    }

    @Test fun `MCP interruption bridges visible four nodes to durable three before saving fresh turns`() = runBlocking {
        val f = Fixture()
        // This is the old defect: comparing the streamed UI baseline to Room always refuses.
        assertNull(f.commit(f.visible, f.continued))
        assertEquals(0, f.writes)
        f.stored = f.stored.copy(title = "newer metadata")
        val saved = f.save()!!
        assertEquals(f.continued.messageNodes, saved.messageNodes)
        assertEquals("newer metadata", saved.title)
        assertEquals(1, f.writes)
        assertTrue(f.journal.hasCheckpoint(f.disk.id))
        // Unknown MCP stays a receipt; never convert it into success or replay permission.
        assertFalse(f.journal.clearSettledInDurableContinuation(saved, false))
        assertTrue(f.journal.clearSettledInDurableContinuation(saved, true))
        assertFalse(f.journal.hasCheckpoint(f.disk.id))
    }

    @Test fun `after process death independent journal uses proven Room baseline not stale UI baseline`() = runBlocking {
        val f = Fixture(); val store = Store(); val id = Uuid.random()
        HumanContinuationJournal(store).checkpoint(id, f.visible, f.continued)
        val recovered = HumanContinuationJournal(store).recover(f.disk, f.journal::provesInFlightBaseline)!!
        assertEquals(f.visible.messageNodes, recovered.baseline.messageNodes)
        assertEquals(f.disk.messageNodes, recovered.durableBaseline!!.messageNodes)
        assertNotNull(f.save(recovered.durableBaseline, recovered.snapshot))
        assertEquals(f.continued.messageNodes, f.stored.messageNodes)
    }

    @Test fun `committed prefix after restart advances CAS baseline without dropping later saved tail`() = runBlocking {
        val f = Fixture(); val store = Store(); val id = Uuid.random()
        val latest = f.continued.copy(messageNodes = f.continued.messageNodes + UIMessage.user("later input").toMessageNode())
        HumanContinuationJournal(store).checkpoint(id, f.visible, latest)
        val recovery = HumanContinuationJournal(store).recover(f.continued)!!
        assertEquals(f.continued.messageNodes, recovery.durableBaseline!!.messageNodes)
        f.stored = f.continued
        assertNotNull(f.save(recovery.durableBaseline, recovery.snapshot))
        assertEquals(latest.messageNodes, f.stored.messageNodes)
    }

    @Test fun `unchanged fresh save and timeout retry do not duplicate messages`() = runBlocking {
        val f = Fixture(); val first = f.save()!!
        assertNotNull(f.save())
        assertEquals(1, f.writes)
        val next = first.copy(messageNodes = first.messageNodes + UIMessage.user("another").toMessageNode())
        assertNotNull(f.save(first, next))
        assertEquals(2, f.writes)
    }

    @Test fun `proof rejects changed prefix owner epoch and arbitrary old tail contents`() = runBlocking {
        val f = Fixture(); val bytes = f.store.read(f.disk.id)
        for (changed in listOf(f.disk.copy(assistantId = Uuid.random()), f.disk.copy(compactionEpoch = 1),
            f.disk.copy(messageNodes = f.disk.messageNodes.dropLast(1) + UIMessage.user("different").toMessageNode()))) {
            f.stored = changed
            try { assertNull(f.save()) } catch (_: GenerationCheckpointException) { }
            assertEquals(changed, f.stored)
            assertEquals(0, f.writes)
            assertArrayEquals(bytes, f.store.read(f.disk.id))
        }
    }

    @Test fun `Room race after proof still refuses stale CAS`() = runBlocking {
        val f = Fixture()
        val result = commitHumanContinuationSnapshot(f.visible, f.continued, { f.stored },
            f.journal::provesInFlightBaseline) { expected, snapshot ->
            f.stored = f.stored.copy(messageNodes = f.stored.messageNodes + UIMessage.user("concurrent input").toMessageNode())
            f.commit(expected, snapshot)
        }
        assertNull(result)
        assertEquals(0, f.writes)
        assertEquals("concurrent input", f.stored.currentMessages.last().toText())
    }

    @Test fun `settled acknowledgement rejects forged tool success or changed reply text`() {
        val f = Fixture(); val bytes = f.store.read(f.disk.id)
        val altered = f.continued.copy(messageNodes = f.continued.messageNodes.map { n ->
            if (n.id != f.pending.id) n else n.copy(messages = n.messages.map { m -> m.copy(parts =
                m.parts.map { p -> if (p is UIMessagePart.Tool) p.copy(output = listOf(UIMessagePart.Text("success"))) else p }) })
        })
        assertFalse(f.journal.clearSettledInDurableContinuation(altered, true))
        assertArrayEquals(bytes, f.store.read(f.disk.id))
    }
}
