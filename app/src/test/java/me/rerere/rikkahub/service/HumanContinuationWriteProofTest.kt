package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic coordinator tests: no device, network, tools, or user records. */
class HumanContinuationWriteProofTest {
    private class Store : GenerationCheckpointStore {
        private val values = mutableMapOf<Uuid, ByteArray>()
        var failWrites = false
        override fun read(conversationId: Uuid) = values[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            check(!failWrites) { "synthetic journal failure" }
            values[conversationId] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) { values.remove(conversationId) }
    }

    private class Fixture {
        val baseline = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("old question").toMessageNode(), UIMessage.assistant("old answer").toMessageNode()))
        val early = baseline.copy(messageNodes = baseline.messageNodes +
            UIMessage.user("new question").toMessageNode() + UIMessage.assistant("A").toMessageNode())
        val newer = early.replyText("AB")
        val store = Store()
        val journal = HumanContinuationJournal(store)
        val runId = Uuid.random()
        var disk = baseline
        var latest = early
        var commits = 0
        var failBeforeCommit = false
        var failAfterCommit = false
        var storedCallbacks = 0
        val context = HumanContinuationContextStore(baseline, baseline, runId, Mutex(), journal,
            read = { disk }, proveBaseline = { _, _ -> false },
            commit = { expected, candidate ->
                commits++
                check(!failBeforeCommit) { "synthetic DB failure" }
                if (!sameDurableGenerationSnapshot(expected, disk)) null else {
                    disk = disk.copy(messageNodes = candidate.messageNodes)
                    check(!failAfterCommit) { "synthetic missing transaction completion receipt" }
                    disk
                }
            },
            canWrite = { true }, project = { it }, latest = { latest },
            onStored = { storedCallbacks++ }, onRebased = { _, _ -> }, retireOldJournal = {})
    }

    @Test fun `write proof failure prevents Room mutation and retains previous complete checkpoint`() = runBlocking {
        val f = Fixture()
        assertTrue(f.context.checkpoint(f.newer))
        val original = f.store.read(f.baseline.id)
        f.store.failWrites = true

        assertTrue(runCatching { f.context.save(f.early) }.isFailure)

        assertEquals(0, f.commits)
        assertEquals(0, f.storedCallbacks)
        assertEquals(f.baseline.messageNodes, f.disk.messageNodes)
        assertArrayEquals(original, f.store.read(f.baseline.id))
        assertEquals(f.newer.messageNodes, HumanContinuationJournal(f.store).recover(f.disk)!!.snapshot.messageNodes)
    }

    @Test fun `failed DB write keeps newer journal tail and later save can proceed`() = runBlocking {
        val f = Fixture()
        assertTrue(f.context.checkpoint(f.newer))
        f.failBeforeCommit = true

        assertTrue(runCatching { f.context.save(f.early) }.isFailure)

        assertEquals(f.baseline.messageNodes, f.disk.messageNodes)
        assertEquals(0, f.storedCallbacks)
        assertEquals(f.newer.messageNodes, HumanContinuationJournal(f.store).recover(f.disk)!!.snapshot.messageNodes)
        f.failBeforeCommit = false
        assertTrue(f.context.save(f.newer))
        assertEquals(f.newer.messageNodes, f.disk.messageNodes)
        assertEquals(1, f.storedCallbacks)
    }

    @Test fun `commit without completion receipt advances safely on next save`() = runBlocking {
        val f = Fixture()
        f.failAfterCommit = true
        assertTrue(runCatching { f.context.save(f.early) }.isFailure)
        assertEquals(f.early.messageNodes, f.disk.messageNodes)
        assertEquals(0, f.storedCallbacks)
        // The next streamed checkpoint must not erase the write-ahead candidate receipt.
        assertTrue(f.context.checkpoint(f.newer))
        assertEquals(f.newer.messageNodes, HumanContinuationJournal(f.store).recover(f.disk)!!.snapshot.messageNodes)

        f.failAfterCommit = false
        assertTrue(f.context.save(f.newer))

        assertEquals(f.newer.messageNodes, f.disk.messageNodes)
        assertEquals(1, f.storedCallbacks)
        assertEquals(2, f.commits)
    }

    @Test fun `subsequent write attempt keeps current Room proof across crash before next CAS`() = runBlocking {
        val f = Fixture()
        assertTrue(f.context.checkpoint(f.early))
        assertTrue(f.context.save(f.early))
        assertTrue(f.context.checkpoint(f.newer))
        f.failBeforeCommit = true
        assertTrue(runCatching { f.context.save(f.newer) }.isFailure)

        val reopened = HumanContinuationJournal(f.store).recover(f.disk)!!

        assertEquals(f.newer.messageNodes, reopened.snapshot.messageNodes)
        assertEquals(f.early.messageNodes, reopened.durableBaseline!!.messageNodes)
        // A newly constructed context starts from the actual last durable page, not the old anchor.
        val restored = HumanContinuationContextStore(reopened.baseline, reopened.durableBaseline!!,
            reopened.runId, Mutex(), HumanContinuationJournal(f.store), read = { f.disk },
            proveBaseline = { _, _ -> false }, commit = { expected, candidate ->
                if (!sameDurableGenerationSnapshot(expected, f.disk)) null else candidate.also { f.disk = it }
            }, canWrite = { true }, project = { it }, latest = { reopened.snapshot },
            onStored = {}, onRebased = { _, _ -> }, retireOldJournal = {})
        assertTrue(restored.save(reopened.snapshot))
        assertEquals(f.newer.messageNodes, f.disk.messageNodes)
    }

    @Test fun `missing completion receipt never authorizes a concurrent human edit`() = runBlocking {
        val f = Fixture()
        f.failAfterCommit = true
        assertTrue(runCatching { f.context.save(f.early) }.isFailure)
        assertTrue(f.context.checkpoint(f.newer))
        f.disk = f.disk.replyText("human replacement")
        val concurrent = f.disk
        val journalBytes = f.store.read(f.baseline.id)
        f.failAfterCommit = false

        assertFalse(f.context.save(f.newer))

        assertEquals(concurrent, f.disk)
        assertEquals(1, f.commits)
        assertEquals(0, f.storedCallbacks)
        assertArrayEquals(journalBytes, f.store.read(f.baseline.id))
        assertThrows(IllegalStateException::class.java) { HumanContinuationJournal(f.store).recover(f.disk) }
        assertArrayEquals(journalBytes, f.store.read(f.baseline.id))
    }

    companion object {
        private fun Conversation.replyText(text: String) = copy(messageNodes = messageNodes.dropLast(1) +
            messageNodes.last().let { node -> node.copy(messages = listOf(
                node.currentMessage.copy(parts = listOf(UIMessagePart.Text(text))))) })
    }
}
