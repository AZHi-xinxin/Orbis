package me.rerere.rikkahub.data.ai.checkpoint

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Android AtomicFile + real directory fsync; only owned synthetic cache directories. */
@RunWith(AndroidJUnit4::class)
class ContinuationConflictDeviceTest {
    private fun isolated(block: (File, AndroidGenerationCheckpointStore) -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        val cache = inst.targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(cache.toPath(), "continuation-conflict-test-").toFile()
        try { block(root, AndroidGenerationCheckpointStore.createHumanContinuation(root)) }
        finally {
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("continuation-conflict-test-"))
            val owned = root.walkBottomUp().toList()
            owned.forEach { check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(root.toPath())) }
            owned.forEach { check(it.delete()) }
        }
    }
    private fun old() = Conversation(assistantId = Uuid.random(), compactionEpoch = 21,
        messageNodes = listOf(UIMessage.user("synthetic old").toMessageNode()))
    private fun Conversation.append(text: String) = copy(messageNodes = messageNodes + UIMessage.user(text).toMessageNode())

    @Test fun generationConflictUsesSeparateDurableArchiveAndFreshSlotSurvivesReopen() = isolated { root, _ ->
        val store = AndroidGenerationCheckpointStore.create(root)
        val journal = GenerationCheckpointJournal(store)
        val base = old().append("synthetic tail")
        journal.begin(base)
        val bytes = checkNotNull(store.read(base.id))
        val current = base.copy(messageNodes = listOf(UIMessage.user("changed prefix").toMessageNode()) + base.messageNodes.drop(1))
        var held = false
        assertTrue(journal.preserveIncompatible(current, { true }, { held = true }))
        assertTrue(held)
        assertNull(store.read(base.id))
        val archive = File(root, ContinuationConflictCopies.GENERATION_DIRECTORY)
            .resolve(ContinuationConflictCopies.name(base.id, bytes))
        assertArrayEquals(bytes, archive.readBytes())
        assertFalse(File(root, ContinuationConflictCopies.DIRECTORY).exists())
        journal.begin(current)
        val reopened = GenerationCheckpointJournal(AndroidGenerationCheckpointStore.create(root))
        assertEquals(current.messageNodes, reopened.recover(current)!!.conversation.messageNodes)
        assertArrayEquals(bytes, archive.readBytes())
    }

    @Test fun realDirectorySyncPreservesConflictAndFreshJournalSurvivesReopen() = isolated { root, store ->
        val old = old()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(Uuid.random(), old, old.append("synthetic only in old copy"))
        val bytes = checkNotNull(store.read(old.id))
        val current = old.copy(compactionEpoch = 23)
        var holds = 0
        assertTrue(journal.recoverForSession(current, beforeRetireConflict = { holds++ }).conflictPreserved)
        assertEquals(1, holds)
        assertNull(store.read(old.id))
        val archive = File(root, ContinuationConflictCopies.DIRECTORY).resolve(ContinuationConflictCopies.name(old.id, bytes))
        assertArrayEquals(bytes, archive.readBytes())
        val next = current.append("synthetic new input")
        val run = Uuid.random()
        journal.checkpoint(run, current, next)
        journal.prepareRoomCommit(run, current, current, next)
        val reopened = HumanContinuationJournal(AndroidGenerationCheckpointStore.createHumanContinuation(root))
        assertEquals(next.messageNodes, reopened.recover(current)!!.snapshot.messageNodes)
        assertArrayEquals(bytes, archive.readBytes())
    }

    @Test fun archivePathFailureLeavesActiveCopyAndRetryPreservesExactBytes() = isolated { root, store ->
        val old = old()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(Uuid.random(), old, old.append("retained"))
        val bytes = checkNotNull(store.read(old.id))
        val obstruction = File(root, ContinuationConflictCopies.DIRECTORY).apply { writeText("synthetic obstruction") }
        val current = old.copy(compactionEpoch = 22)
        assertTrue(runCatching { journal.recoverForSession(current) }.isFailure)
        assertArrayEquals(bytes, store.read(old.id))
        check(obstruction.delete()) // Only this test-created file, never a journal.
        assertTrue(journal.recoverForSession(current).conflictPreserved)
        assertArrayEquals(bytes, obstruction.listFiles()!!.single().readBytes())
    }

    @Test fun failedHoldKeepsActiveAndDurableCopyAndRetryIsIdempotent() = isolated { root, store ->
        val old = old()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(Uuid.random(), old, old.append("retained"))
        val bytes = checkNotNull(store.read(old.id))
        val current = old.copy(compactionEpoch = 22)
        assertTrue(runCatching { journal.recoverForSession(current, beforeRetireConflict = { error("synthetic hold failure") }) }.isFailure)
        assertArrayEquals(bytes, store.read(old.id))
        val folder = File(root, ContinuationConflictCopies.DIRECTORY)
        assertArrayEquals(bytes, folder.listFiles()!!.single().readBytes())
        assertTrue(journal.recoverForSession(current).conflictPreserved)
        assertNull(store.read(old.id))
        assertEquals(1, folder.listFiles()!!.size)
        assertArrayEquals(bytes, folder.listFiles()!!.single().readBytes())
    }
}
