package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

class ChatImportStagingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_790_000_000_000L
    private fun orphan(cache: File, age: Long = ChatImportStaging.MAX_IDLE_MILLIS + 1000): File {
        val dir = File(File(cache, ChatImportStaging.ROOT), UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, "lease").writeText("orbis-chat-import-v1")
        val payload = File(dir, "payload").apply { mkdir() }
        File(payload, "archive.bin").writeText("synthetic source")
        assertTrue(dir.setLastModified(now - age))
        return dir
    }

    @Test fun closesOnlyItsOwnExactFilesAndIsIdempotent() {
        val cache = temporary.newFolder()
        val unrelated = File(cache, "unrelated.db").apply { writeText("preserve") }
        val first = ChatImportStaging.create(cache)
        val other = ChatImportStaging.create(cache)
        File(first.payload, "archive.bin").writeText("synthetic")
        first.close(); first.close()
        assertFalse(first.directory.exists()); assertTrue(other.directory.isDirectory)
        assertEquals("preserve", unrelated.readText())
        other.close()
    }

    @Test fun prunesOnlyExpiredUnlockedOwnedStaging() {
        val cache = temporary.newFolder()
        val expired = orphan(cache)
        val recent = orphan(cache, age = 1000)
        ChatImportStaging.prune(cache, now)
        assertFalse(expired.exists()); assertTrue(recent.exists())
    }

    @Test fun activeLeaseSurvivesPruningEvenWhenOlderThanTtl() {
        val cache = temporary.newFolder()
        ChatImportStaging.create(cache).use { active ->
            File(active.payload, "kelivo-chat-snapshot.db").writeText("synthetic")
            assertTrue(active.directory.setLastModified(now - ChatImportStaging.MAX_IDLE_MILLIS - 1000))
            ChatImportStaging.prune(cache, now)
            assertTrue(File(active.payload, "kelivo-chat-snapshot.db").exists())
        }
    }

    @Test fun abandonedOperitSnapshotsAreRemovedOnlyInsideOwnedPayload() {
        val cache = temporary.newFolder()
        val expired = orphan(cache)
        File(expired, "payload/orbis-operit-12345.json").writeText("synthetic")
        val unknown = orphan(cache)
        File(unknown, "payload/orbis-operit-user.json").writeText("preserve")
        ChatImportStaging.prune(cache, now)
        assertFalse(expired.exists())
        assertTrue(unknown.exists())
    }

    @Test fun unknownMarkersNamesAndPayloadsAreNotDeleted() {
        val cache = temporary.newFolder()
        val unknown = orphan(cache)
        File(unknown, "payload/user-file.txt").writeText("preserve")
        val differentMarker = orphan(cache)
        File(differentMarker, "lease").writeText("another-owner")
        val unrelated = File(cache, "kelivo-chats-unleased").apply { mkdir() }
        File(unrelated, "kelivo-chat-snapshot.db").writeText("not owned by this helper")
        ChatImportStaging.prune(cache, now)
        assertTrue(unknown.exists()); assertTrue(differentMarker.exists()); assertTrue(unrelated.exists())
    }

    @Test fun reservesSpaceBeforeFirstWriteAndEveryChunk() {
        val folder = temporary.newFolder()
        val target = File(folder, "archive.bin")
        assertThrows(IllegalArgumentException::class.java) {
            ChatImportStaging.copyArchive(ByteArrayInputStream(byteArrayOf(1)), target, 100, {},
                { RikkaChatArchive.MIN_FREE_BYTES - 1 })
        }
        assertFalse(target.exists())
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            ChatImportStaging.copyArchive(ByteArrayInputStream(ByteArray(150000)), target, 200000, {}, {
                if (++checks <= 2) Long.MAX_VALUE else RikkaChatArchive.MIN_FREE_BYTES
            })
        }
        assertEquals(3, checks); assertFalse(target.exists())
    }

    @Test fun limitAndCancellationRemovePartialArchiveWithoutTouchingSource() {
        val source = temporary.newFile().apply { writeBytes(ByteArray(100)) }
        val before = source.readBytes()
        val target = File(temporary.newFolder(), "archive.bin")
        assertThrows(IllegalArgumentException::class.java) {
            source.inputStream().use { ChatImportStaging.copyArchive(it, target, 10, {}, { Long.MAX_VALUE }) }
        }
        assertFalse(target.exists())
        assertThrows(CancellationException::class.java) {
            source.inputStream().use { ChatImportStaging.copyArchive(it, target, 100,
                { throw CancellationException("synthetic cancellation") }, { Long.MAX_VALUE }) }
        }
        assertFalse(target.exists()); assertArrayEquals(before, source.readBytes())
    }

    @Test fun cancellationBeforeCoroutineStartsStillClosesRetainedArchive() = runTest {
        val lease = ChatImportStaging.create(temporary.newFolder())
        File(lease.payload, "archive.bin").writeText("synthetic source with private settings")
        var entered = false
        val work = launch(start = CoroutineStart.LAZY) { entered = true }
        lease.releaseOnCompletion(work)
        work.cancel(); work.join()
        assertFalse(entered); assertFalse(lease.directory.exists())
    }
}
