package me.rerere.rikkahub.data.orbis.voice

import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisVideoFrameStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val conversation = UUID.randomUUID().toString()
    private val call = UUID.randomUUID().toString()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    private fun root() = temp.newFolder()

    @Test fun corruptManifestDoesNotMakeOrphanJpegImmortal() = runBlocking {
        val root = root(); val store = OrbisVideoFrameStore(root)
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        val file = File(File(root, call), "${frame.id}.jpg")
        assertTrue(file.setLastModified(200))
        File(root, "$call.json").writeText("corrupt synthetic manifest")
        assertEquals(0, store.cleanup(200 + VIDEO_FRAME_TTL_MS - 1, recoverInterrupted = true))
        assertEquals(1, store.cleanup(200 + VIDEO_FRAME_TTL_MS, recoverInterrupted = true))
        assertFalse(file.exists())
    }

    @Test fun endedFramesExpireExactlyTenMinutesLaterAndNeverRemoveCopiedPhotos() = runBlocking {
        val root = root(); val store = OrbisVideoFrameStore(root)
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        val permanent = File(temp.newFolder(), "kept.jpg")
        store.retain(owner, call, frame.id, 250) { it.copyTo(permanent); "photo" }
        store.end(owner, call, 300)
        assertArrayEquals(jpeg, store.readJpeg(owner, call, frame.id, 300 + VIDEO_FRAME_TTL_MS - 1))
        assertEquals(0, store.cleanup(300 + VIDEO_FRAME_TTL_MS - 1))
        assertEquals(1, store.cleanup(300 + VIDEO_FRAME_TTL_MS))
        assertTrue(permanent.exists())
        assertTrue(store.list(owner, call, 300 + VIDEO_FRAME_TTL_MS).isEmpty())
        assertTrue(runCatching { store.readJpeg(owner, call, frame.id, 300 + VIDEO_FRAME_TTL_MS) }.isFailure)
    }

    @Test fun ownerAndCallIsolationRejectReadRetainAndTraversal() = runBlocking {
        val store = OrbisVideoFrameStore(root())
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        assertTrue(store.list(other, now = 300).isEmpty())
        assertTrue(runCatching { store.readJpeg(other, call, frame.id, 300) }.isFailure)
        var imported = false
        assertTrue(runCatching { store.retain(other, call, frame.id, 300) { imported = true; "photo" } }.isFailure)
        assertFalse(imported)
        assertTrue(runCatching { store.readJpeg(owner, "../outside", frame.id, 300) }.isFailure)
        val otherCall = UUID.randomUUID().toString()
        store.begin(owner, conversation, otherCall, 100)
        assertTrue(runCatching { store.readJpeg(owner, otherCall, frame.id, 300) }.isFailure)
    }

    @Test fun concurrentKeepsAllowOnlyTenDistinctFramesAndDuplicateDoesNotConsumeSlot() = runBlocking {
        val store = OrbisVideoFrameStore(root())
        store.begin(owner, conversation, call, 100)
        val frames = (1..12).map { store.add(owner, call, jpeg, 200 + it.toLong()) }
        var imports = 0
        val first = store.retain(owner, call, frames[0].id, 300) { imports++; "photo-${it.name}" }
        val duplicate = store.retain(owner, call, frames[0].id, 301) { imports++; "must-not-run" }
        assertEquals(first, duplicate); assertEquals(1, imports)
        val results = frames.drop(1).map { frame -> async {
            runCatching { store.retain(owner, call, frame.id, 302) { imports++; "photo-${it.name}" } }.isSuccess
        } }.awaitAll()
        assertEquals(9, results.count { it }); assertEquals(10, imports)
        assertEquals(10, store.list(owner, call, 303).single().frames.count { it.photoId != null })
    }

    @Test fun retainFailureDoesNotConsumeSlotAndReceiptSurvivesRestart() = runBlocking {
        val root = root(); val store = OrbisVideoFrameStore(root)
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        assertTrue(runCatching { store.retain(owner, call, frame.id, 300) { error("copy failed") } }.isFailure)
        assertEquals(0, store.list(owner, call, 301).single().frames.count { it.photoId != null })
        store.retain(owner, call, frame.id, 302) { "retained-once" }
        val restarted = OrbisVideoFrameStore(root)
        assertEquals("retained-once", restarted.retain(owner, call, frame.id, 303) { error("must not copy again") })
    }

    @Test fun processDeathRecoveryExpiresFromLastFrameInsteadOfGivingAnotherTenMinutes() = runBlocking {
        val root = root(); val first = OrbisVideoFrameStore(root)
        first.begin(owner, conversation, call, 100)
        val frame = first.add(owner, call, jpeg, 200)
        val restarted = OrbisVideoFrameStore(root)
        assertEquals(1, restarted.cleanup(200 + VIDEO_FRAME_TTL_MS, recoverInterrupted = true))
        assertTrue(runCatching { restarted.readJpeg(owner, call, frame.id, 200 + VIDEO_FRAME_TTL_MS) }.isFailure)
        assertTrue(runCatching { restarted.add(owner, call, jpeg, 200 + VIDEO_FRAME_TTL_MS) }.isFailure)
    }

    @Test fun activeSessionDoesNotExpireAndEndedSessionCannotCaptureMore() = runBlocking {
        val store = OrbisVideoFrameStore(root())
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        assertEquals(0, store.cleanup(VIDEO_FRAME_TTL_MS * 100))
        assertArrayEquals(jpeg, store.readJpeg(owner, call, frame.id, VIDEO_FRAME_TTL_MS * 100))
        store.end(owner, call, VIDEO_FRAME_TTL_MS * 100)
        assertTrue(runCatching { store.add(owner, call, jpeg, VIDEO_FRAME_TTL_MS * 100 + 1) }.isFailure)
    }

    @Test fun frameCapRejectsNewCaptureWithoutEvictingPreviousFrames() = runBlocking {
        val store = OrbisVideoFrameStore(root())
        store.begin(owner, conversation, call, 100)
        val frames = (1..VIDEO_CALL_MAX_FRAMES).map { store.add(owner, call, jpeg, 200 + it.toLong()) }
        assertTrue(runCatching { store.add(owner, call, jpeg, 1000) }.isFailure)
        assertArrayEquals(jpeg, store.readJpeg(owner, call, frames.first().id, 1000))
        assertEquals(VIDEO_CALL_MAX_FRAMES, store.list(owner, call, 1000).single().frames.size)
    }

    @Test fun rejectsOversizedAndInvalidImageBeforeAnyFileAppears() = runBlocking {
        val root = root(); val store = OrbisVideoFrameStore(root)
        store.begin(owner, conversation, call, 100)
        assertTrue(runCatching { store.add(owner, call, ByteArray(VIDEO_FRAME_MAX_BYTES + 1), 200) }.isFailure)
        assertTrue(runCatching { store.add(owner, call, "not an image".toByteArray(), 200) }.isFailure)
        assertTrue(store.list(owner, call, 300).single().frames.isEmpty())
        assertFalse(File(root, call).exists())
    }
}
