package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** Production publication ordering and continuation CAS with synthetic, memory-only storage. */
class HumanContinuationPublicationTest {
    private class MemoryStore : GenerationCheckpointStore {
        private val records = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = records[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            records[conversationId] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) {
            records.remove(conversationId)
        }
    }

    private class Fixture {
        val baseline = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("synthetic previous question").toMessageNode(),
                UIMessage.assistant("synthetic previous answer").toMessageNode(),
            ),
        )
        var stored = baseline
        var published = baseline
        var writes = 0
        var proofAttempts = 0
        val generationJournal = GenerationCheckpointJournal(MemoryStore())

        fun prove(stored: Conversation, expected: Conversation): Boolean {
            proofAttempts++
            return generationJournal.provesInFlightBaseline(stored, expected)
        }

        suspend fun compareAndSet(expected: Conversation, next: Conversation): Conversation? {
            if (!sameDurableGenerationSnapshot(expected, stored)) return null
            writes++
            stored = stored.copy(messageNodes = next.messageNodes)
            return stored
        }
    }

    @Test fun `failed ordinary commit preserves RAM baseline and next continuation saves without generation journal`() = runBlocking {
        val f = Fixture()
        val attempted = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.user("synthetic input whose ordinary save fails").toMessageNode())
        val failure = IOException("synthetic transaction failure")

        val result = runCatching {
            commitConversationBeforePublish(
                commit = { throw failure },
                publish = { f.published = attempted },
            )
        }

        assertSame(failure, result.exceptionOrNull())
        assertSame(f.baseline, f.published)
        assertSame(f.baseline, f.stored)
        assertFalse(f.generationJournal.hasCheckpoint(f.baseline.id))

        // The independent displayed lane may retain the failed input and continue. Its durable
        // baseline must still be the successfully stored page, not the failed ordinary snapshot.
        val continuation = attempted.copy(messageNodes = attempted.messageNodes + listOf(
            UIMessage.assistant("synthetic continued answer").toMessageNode(),
            UIMessage.user("synthetic next input").toMessageNode(),
            UIMessage.assistant("synthetic next answer").toMessageNode(),
        ))
        val saved = commitHumanContinuationSnapshot(
            expected = f.published,
            snapshot = continuation,
            read = { f.stored },
            proveInFlightBaseline = f::prove,
            commit = f::compareAndSet,
        )

        assertNotNull(saved)
        assertEquals(continuation.messageNodes, f.stored.messageNodes)
        assertEquals(1, f.writes)
        assertEquals(0, f.proofAttempts)
        assertFalse(f.generationJournal.hasCheckpoint(f.baseline.id))
        assertEquals(1, f.stored.currentMessages.count { it.id == attempted.currentMessages.last().id })
    }

    @Test fun `successful ordinary commit publishes only after durable write returns`() = runBlocking {
        val f = Fixture()
        val next = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.user("synthetic new input").toMessageNode())
        val releaseCommit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) {
            commitConversationBeforePublish(
                commit = {
                    events += "commit started"
                    releaseCommit.await()
                    f.stored = next
                    events += "commit returned"
                },
                publish = {
                    assertSame(next, f.stored)
                    events += "published"
                    f.published = next
                },
            )
        }

        assertEquals(listOf("commit started"), events)
        assertSame(f.baseline, f.published)
        assertSame(f.baseline, f.stored)
        releaseCommit.complete(Unit)
        saving.join()
        assertEquals(listOf("commit started", "commit returned", "published"), events)
        assertSame(next, f.published)
        assertSame(next, f.stored)
    }

    @Test fun `unseen persisted history still refuses continuation without journal proof`() = runBlocking {
        val f = Fixture()
        val unseen = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.user("synthetic concurrent edit").toMessageNode())
        f.stored = unseen
        val next = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.assistant("synthetic stale continuation").toMessageNode())

        val saved = commitHumanContinuationSnapshot(
            expected = f.published,
            snapshot = next,
            read = { f.stored },
            proveInFlightBaseline = f::prove,
            commit = f::compareAndSet,
        )

        assertNull(saved)
        assertSame(unseen, f.stored)
        assertSame(f.baseline, f.published)
        assertEquals(0, f.writes)
        assertEquals(1, f.proofAttempts)
        assertFalse(f.generationJournal.hasCheckpoint(f.baseline.id))
    }

    @Test fun `concurrent edit between read and commit still fails CAS without overwriting Room`() = runBlocking {
        val f = Fixture()
        val unseen = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.user("synthetic transaction race").toMessageNode())
        val next = f.baseline.copy(messageNodes = f.baseline.messageNodes +
            UIMessage.assistant("synthetic stale continuation").toMessageNode())

        val saved = commitHumanContinuationSnapshot(
            expected = f.published,
            snapshot = next,
            read = { f.stored },
            proveInFlightBaseline = f::prove,
            commit = { expected, snapshot ->
                f.stored = unseen
                f.compareAndSet(expected, snapshot)
            },
        )

        assertNull(saved)
        assertSame(unseen, f.stored)
        assertSame(f.baseline, f.published)
        assertEquals(0, f.writes)
        assertEquals(0, f.proofAttempts)
        assertFalse(f.generationJournal.hasCheckpoint(f.baseline.id))
    }
}
