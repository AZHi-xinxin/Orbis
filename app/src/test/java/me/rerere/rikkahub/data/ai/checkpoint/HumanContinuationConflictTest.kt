package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HumanContinuationConflictTest {
    private class Store : GenerationCheckpointStore {
        var bytes: ByteArray? = null
        val archive = mutableMapOf<String, ByteArray>()
        var failArchive = false
        var failDelete = false
        var deletes = 0
        override fun read(conversationId: Uuid) = bytes?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun preserveConflict(conversationId: Uuid, bytes: ByteArray) {
            check(!failArchive)
            archive[ContinuationConflictCopies.name(conversationId, bytes)] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) { check(!failDelete); deletes++; bytes = null }
    }
    private fun base() = Conversation(assistantId = Uuid.random(), compactionEpoch = 21,
        messageNodes = listOf(UIMessage.user("old input").toMessageNode(), UIMessage.assistant("old reply").toMessageNode()))
    private fun Conversation.append(value: String) = copy(messageNodes = messageNodes + UIMessage.user(value).toMessageNode())

    @Test fun `stale epoch is preserved byte for byte and no longer blocks fresh checkpoint or commit proof`() {
        val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
        journal.checkpoint(Uuid.random(), old, old.append("only in old journal"))
        val original = store.bytes!!.copyOf()
        val current = old.copy(compactionEpoch = 23)
        assertTrue(runCatching { journal.recover(current) }.isFailure)
        val result = journal.recoverForSession(current)
        assertTrue(result.conflictPreserved); assertNull(result.recovery)
        assertArrayEquals(original, store.archive.values.single()); assertNull(store.bytes)
        val run = Uuid.random(); val next = current.append("new message")
        journal.checkpoint(run, current, next)
        journal.prepareRoomCommit(run, current, current, next)
        assertEquals(next, HumanContinuationJournal(store).recover(current)!!.snapshot)
        assertArrayEquals(original, store.archive.values.single())
    }

    @Test fun `archive or delete failure never destroys the only copy and retry is idempotent`() {
        for (archiveFailure in listOf(true, false)) {
            val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
            journal.checkpoint(Uuid.random(), old, old.append("retained")); val original = store.bytes!!.copyOf()
            store.failArchive = archiveFailure; store.failDelete = !archiveFailure
            assertTrue(runCatching { journal.recoverForSession(old.copy(compactionEpoch = 22)) }.isFailure)
            assertArrayEquals(original, store.bytes); assertEquals(0, store.deletes)
            store.failArchive = false; store.failDelete = false
            assertTrue(journal.recoverForSession(old.copy(compactionEpoch = 22)).conflictPreserved)
            assertEquals(1, store.archive.size); assertArrayEquals(original, store.archive.values.single())
        }
    }

    @Test fun `malformed foreign and active-lane records are not retired`() {
        val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
        journal.checkpoint(Uuid.random(), old, old.append("retained")); val original = store.bytes!!.copyOf()
        assertTrue(runCatching { journal.recoverForSession(old.copy(assistantId = Uuid.random())) }.isFailure)
        assertTrue(runCatching { journal.recoverForSession(old.copy(compactionEpoch = 22), mayPreserveConflict = { false }) }.isFailure)
        assertArrayEquals(original, store.bytes); assertTrue(store.archive.isEmpty())
        store.bytes = "bad file".toByteArray()
        val failure = runCatching { journal.recoverForSession(old) }.exceptionOrNull()!!
        assertFalse(failure.message.orEmpty().contains("bad file"))
        assertEquals("bad file", store.bytes!!.decodeToString()); assertEquals(0, store.deletes)
    }

    @Test fun `normal recovery still returns uncommitted history without archiving or tool replay`() {
        val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
        val next = old.append("recover me")
        journal.checkpoint(Uuid.random(), old, next)
        val result = journal.recoverForSession(old)
        assertFalse(result.conflictPreserved); assertEquals(next, result.recovery!!.snapshot)
        assertTrue(store.archive.isEmpty()); assertEquals(0, store.deletes)
    }

    @Test fun `failed safety hold preserves active copy and never permits automatic replay`() {
        val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
        journal.checkpoint(Uuid.random(), old, old.append("retain")); val bytes = store.bytes!!.copyOf()
        assertTrue(runCatching {
            journal.recoverForSession(old.copy(compactionEpoch = 22), beforeRetireConflict = { error("hold failed") })
        }.isFailure)
        assertArrayEquals(bytes, store.bytes); assertArrayEquals(bytes, store.archive.values.single())
        assertEquals(0, store.deletes)
    }

    @Test fun `a changed active copy after preservation cannot be erased`() {
        val store = Store(); val journal = HumanContinuationJournal(store); val old = base()
        journal.checkpoint(Uuid.random(), old, old.append("retain")); val bytes = store.bytes!!.copyOf()
        val newer = "synthetic concurrent replacement".toByteArray()
        assertTrue(runCatching {
            journal.recoverForSession(old.copy(compactionEpoch = 22), beforeRetireConflict = { store.bytes = newer })
        }.isFailure)
        assertArrayEquals(newer, store.bytes); assertArrayEquals(bytes, store.archive.values.single())
        assertEquals(0, store.deletes)
    }
}
