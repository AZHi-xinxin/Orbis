package me.rerere.rikkahub.data.orbis.voice

import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveredVideoFrameAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = UUID.randomUUID().toString()
    private val conversation = UUID.randomUUID().toString()
    private val call = UUID.randomUUID().toString()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    @Test fun everyReadEntryWaitsForColdRecoveryAndCannotReadAnOldActiveManifest() = runTest {
        val store = OrbisVideoFrameStore(temporary.newFolder())
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        val recovery = CompletableDeferred<Unit>()
        val access = RecoveredVideoFrameAccess(store, recovery) { 200 + VIDEO_FRAME_TTL_MS }
        var imported = false
        val listing = async { access.list(owner, call) }
        val reading = async { runCatching { access.read(owner, call, frame.id) } }
        val retaining = async { runCatching { access.retain(owner, call, frame.id) { imported = true; "photo" } } }
        runCurrent()
        assertFalse(listing.isCompleted); assertFalse(reading.isCompleted); assertFalse(retaining.isCompleted)
        assertFalse(imported)
        // Awaiting readers never hold the store lock: recovery itself must still make progress.
        assertEquals(1, store.cleanup(200 + VIDEO_FRAME_TTL_MS, recoverInterrupted = true))
        recovery.complete(Unit)
        assertTrue(listing.await().isEmpty())
        assertTrue(reading.await().isFailure); assertTrue(retaining.await().isFailure)
        assertFalse(imported)
    }

    @Test fun failedRecoveryFailsClosedAndFirstLiveCaptureWorksAfterSuccessfulRecovery() = runTest {
        val store = OrbisVideoFrameStore(temporary.newFolder())
        val failure = CompletableDeferred<Unit>().apply { completeExceptionally(IOException("recovery failed")) }
        assertTrue(runCatching { RecoveredVideoFrameAccess(store, failure).list(owner) }.isFailure)
        val done = CompletableDeferred<Unit>().apply { complete(Unit) }
        val access = RecoveredVideoFrameAccess(store, done) { 300 }
        store.begin(owner, conversation, call, 100)
        val frame = store.add(owner, call, jpeg, 200)
        assertEquals(1, access.list(owner, call).single().frames.size)
        assertArrayEquals(jpeg, access.read(owner, call, frame.id))
        assertEquals("synthetic-photo", access.retain(owner, call, frame.id) { "synthetic-photo" })
    }
}
