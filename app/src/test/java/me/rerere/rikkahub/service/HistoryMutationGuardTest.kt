package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointException
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.applyEventPresentation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic state and production guard/journal helpers only; no Room, phone, or provider. */
class HistoryMutationGuardTest {
    private class MemoryStore : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun exists(conversationId: Uuid) = bytes.containsKey(conversationId)
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            this.bytes[conversationId] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) {
            bytes.remove(conversationId)
        }
    }

    private suspend fun reject(action: suspend () -> Unit): IllegalStateException {
        try {
            action()
            fail("history mutation must be rejected")
        } catch (error: IllegalStateException) {
            return error
        }
        error("unreachable")
    }

    @Test fun `idle settled history is writable after checking journal presence`() = runBlocking {
        var checks = 0
        var writes = 0
        requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
            checks++
            false
        }
        writes++
        assertEquals(1, checks)
        assertEquals(1, writes)
    }

    @Test fun `busy includes a registered job before journal creation and denies the write`() = runBlocking {
        var checks = 0
        var writes = 0
        val failure = reject {
            requireHistoryMutationReady(busy = true, recoveryBlocked = false) {
                checks++
                false
            }
            writes++
        }
        assertTrue(failure.message!!.contains("正在回复"))
        assertEquals(0, checks)
        assertEquals(0, writes)
    }

    @Test fun `recovery blocked denies mutation even if journal would appear absent`() = runBlocking {
        var checks = 0
        var writes = 0
        val failure = reject {
            requireHistoryMutationReady(busy = false, recoveryBlocked = true) {
                checks++
                false
            }
            writes++
        }
        assertTrue(failure.message!!.contains("回复恢复记录待核对"))
        assertEquals(0, checks)
        assertEquals(0, writes)
    }

    @Test fun `leftover journal blocks mutation after generation job has ended`() = runBlocking {
        var checks = 0
        var writes = 0
        val failure = reject {
            requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
                checks++
                true
            }
            writes++
        }
        assertTrue(failure.message!!.contains("回复恢复记录待核对"))
        assertEquals(1, checks)
        assertEquals(0, writes)
    }

    @Test fun `storage failure is not treated as a missing journal`() = runBlocking {
        val storageFailure = IllegalStateException("synthetic inaccessible checkpoint storage")
        var writes = 0
        val failure = reject {
            requireHistoryMutationReady(busy = false, recoveryBlocked = false) { throw storageFailure }
            writes++
        }
        assertSame(storageFailure, failure)
        assertEquals(0, writes)
    }

    @Test fun `cancellation during journal check cannot continue into history mutation`() = runBlocking {
        val cancellation = CancellationException("synthetic cancelled presence check")
        var writes = 0
        try {
            requireHistoryMutationReady(busy = false, recoveryBlocked = false) { throw cancellation }
            writes++
            fail("cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(0, writes)
    }

    @Test fun `production journal sanitizes inaccessible storage while guard keeps write blocked`() = runBlocking {
        val store = MemoryStore()
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("synthetic current question").toMessageNode(),
        ))
        GenerationCheckpointJournal(store).begin(original)
        val before = store.bytes.getValue(original.id).copyOf()
        val inaccessible = object : GenerationCheckpointStore by store {
            override fun exists(conversationId: Uuid): Boolean {
                error("synthetic-private-path-and-content")
            }
        }
        val journal = GenerationCheckpointJournal(inaccessible)
        var writes = 0
        val failure = reject {
            requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
                journal.hasCheckpoint(original.id)
            }
            writes++
        }
        assertTrue(failure is GenerationCheckpointException)
        assertEquals("checkpoint_storage_or_decode_failed", (failure as GenerationCheckpointException).code)
        assertFalse(failure.message!!.contains("synthetic-private-path-and-content"))
        assertNull(failure.cause)
        assertEquals(0, writes)
        assertArrayEquals(before, store.bytes.getValue(original.id))
    }

    @Test fun `denied event flags preserve exact journal and allow strict recovery after restart`() = runBlocking {
        // Requests can contain either flag value; committed read state is intentionally monotonic.
        listOf(Triple(false, true, true), Triple(true, true, true),
            Triple(false, true, false), Triple(false, false, false)).forEach { (read, collapsed, changeRead) ->
            val nextRead = if (changeRead) !read else read
            val nextCollapsed = if (changeRead) collapsed else !collapsed
            listOf(false, true).forEach { replyAlreadyCommitted ->
                val event = UIMessage.user("synthetic immutable event body").copy(
                    orbisEvent = OrbisEventMetadata("synthetic-receipt", "synthetic-source",
                        "synthetic-event", 1000L, read = read, collapsed = collapsed),
                )
                val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
                    event.toMessageNode(), UIMessage.user("synthetic current question").toMessageNode(),
                ))
                val store = MemoryStore()
                val journal = GenerationCheckpointJournal(store)
                val handle = journal.begin(original)
                val reply = UIMessage.assistant("synthetic complete durable response").toMessageNode()
                val tail = original.messageNodes.takeLast(1) + reply
                journal.checkpoint(handle, original.assistantId, original.compactionEpoch, tail)
                val completed = original.copy(messageNodes = original.messageNodes + reply)
                val state = MutableStateFlow(completed)
                var stored = if (replyAlreadyCommitted) completed else original
                val storedBefore = stored
                val journalBefore = store.bytes.getValue(original.id).copyOf()
                val edit = OrbisEventPresentationEdit(original.messageNodes.first().id, event.id,
                    checkNotNull(event.orbisEvent), event.toText(), nextRead, nextCollapsed)
                val mutex = Mutex()
                var writes = 0
                reject {
                    mutex.withLock {
                        requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
                            journal.hasCheckpoint(original.id)
                        }
                        persistOrbisEventPresentation(state, original.assistantId, edit) {
                            writes++
                            stored = stored.applyEventPresentation(original.assistantId, edit)
                        }
                    }
                }
                assertEquals(0, writes)
                assertEquals(storedBefore, stored)
                assertEquals(completed, state.value)
                assertArrayEquals(journalBefore, store.bytes.getValue(original.id))

                // A fresh journal instance stands in for process restart; nothing is rehashed,
                // erased, normalized, or replayed to make this strict recovery succeed.
                val restartedJournal = GenerationCheckpointJournal(store)
                val recovered = checkNotNull(restartedJournal.recover(stored))
                assertEquals(!replyAlreadyCommitted, recovered.changed)
                assertTrue(recovered.unknownToolIds.isEmpty())
                assertEquals(completed, recovered.conversation)
                assertArrayEquals(journalBefore, store.bytes.getValue(original.id))
                stored = recovered.conversation
                state.value = stored
                restartedJournal.clearAfterDurableCommit(recovered.handle, stored)
                assertFalse(restartedJournal.hasCheckpoint(original.id))

                // Once settled, an explicit new card action works normally without altering text.
                mutex.withLock {
                    requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
                        restartedJournal.hasCheckpoint(original.id)
                    }
                    persistOrbisEventPresentation(state, original.assistantId, edit) {
                        writes++
                        stored = stored.applyEventPresentation(original.assistantId, edit)
                    }
                }
                assertEquals(1, writes)
                assertEquals(stored, state.value)
                assertEquals(read || nextRead, stored.currentMessages.first().orbisEvent!!.read)
                assertEquals(nextCollapsed, stored.currentMessages.first().orbisEvent!!.collapsed)
                assertEquals(event.parts, stored.currentMessages.first().parts)
                assertEquals(reply, stored.messageNodes.last())
            }
        }
    }

    @Test fun `shared mutex keeps event mutation behind baseline publication and rejects it`() = runBlocking {
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("synthetic history").toMessageNode(),
            UIMessage.user("synthetic question").toMessageNode(),
        ))
        val store = MemoryStore()
        val journal = GenerationCheckpointJournal(store)
        val mutex = Mutex()
        val baselineLockHeld = CompletableDeferred<Unit>()
        val releaseBaseline = CompletableDeferred<Unit>()
        var checks = 0
        var writes = 0
        val baseline = launch {
            mutex.withLock {
                baselineLockHeld.complete(Unit)
                releaseBaseline.await()
                journal.begin(original)
            }
        }
        baselineLockHeld.await()
        val mutation = launch {
            reject {
                mutex.withLock {
                    requireHistoryMutationReady(busy = false, recoveryBlocked = false) {
                        checks++
                        journal.hasCheckpoint(original.id)
                    }
                    writes++
                }
            }
        }
        assertEquals(0, checks)
        releaseBaseline.complete(Unit)
        baseline.join()
        mutation.join()
        assertEquals(1, checks)
        assertEquals(0, writes)
        assertFalse(checkNotNull(journal.recover(original)).changed)
        assertTrue(journal.hasCheckpoint(original.id))
    }
}
