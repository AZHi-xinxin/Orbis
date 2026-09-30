package me.rerere.rikkahub.data.orbis

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class OrbisPhoneObservationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `trusted app directory aliases are normalized before missing child is checked`() {
        val appFiles = temporary.newFolder("app-files")
        val alias = File(appFiles, "../app-files")
        assertNotEquals(alias.absoluteFile, alias.canonicalFile)
        val paths = OrbisObservationPaths(alias)
        paths.checkRoot()
        assertEquals(File(appFiles.canonicalFile, "orbis-phone-observation"), paths.root)
        assertFalse(paths.root.exists()) // Opening a new archive never creates or writes it.
        paths.checkRoot(create = true)
        assertTrue(paths.root.isDirectory)
        assertFalse(paths.baseFile.exists())
    }

    @Test fun `OS canonical alias is accepted without canonicalizing the private child`() {
        val actualFiles = temporary.newFolder("canonical-files").canonicalFile
        val systemAlias = object : File(temporary.root, "system-provided-alias") {
            override fun getCanonicalFile(): File = actualFiles
        }
        val paths = OrbisObservationPaths(systemAlias)
        paths.checkRoot()
        assertEquals(File(actualFiles, "orbis-phone-observation"), paths.root)
        assertFalse(paths.root.exists())
    }

    @Test fun `private observation root cannot be a regular file`() {
        val paths = OrbisObservationPaths(temporary.root)
        assertTrue(paths.root.createNewFile())
        assertThrows(IllegalStateException::class.java) { paths.checkRoot() }
        assertTrue(paths.root.isFile)
    }

    @Test fun `archive and atomic sidecars cannot be directories`() {
        for (suffix in listOf("", ".bak", ".new")) {
            val paths = OrbisObservationPaths(temporary.newFolder("case-${suffix.removePrefix(".").ifEmpty { "base" }}"))
            paths.checkRoot(create = true)
            val invalid = File(paths.root, "summaries.json$suffix")
            assertTrue(invalid.mkdir())
            assertThrows(IllegalStateException::class.java) { paths.checkRoot() }
            assertTrue(invalid.isDirectory)
        }
    }

    private class MemoryStorage : OrbisObservationStorage {
        var text: String? = null
        var writes = 0
        var failAt = -1
        var failAfterWrite = false
        var failRead = false
        override fun readArchive(): String? {
            if (failRead) error("private-path sentinel")
            return text
        }
        override fun writeArchive(text: String) {
            writes++
            if (writes == failAt && !failAfterWrite) error("private-write sentinel")
            this.text = text
            if (writes == failAt) error("private-post-write sentinel")
        }
    }
    private class Clock : OrbisObservationClock {
        var elapsed = 100L
        var wall = 1_000_000L
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
    }
    private class Fixture {
        val storage = MemoryStorage()
        val clock = Clock()
        var samples = 0
        var device = OrbisDeviceSnapshot("test-brand", "test-model", "10", 29, 50, false, "not_granted")
        var callback: (() -> Unit)? = null
        val core = OrbisPhoneObservation(storage, clock, { samples++; callback?.invoke(); device })
        fun start() { core.foreground.enter("page"); core.start("page") }
        fun advance(ms: Long) { clock.elapsed += ms; core.poll() }
    }

    @Test fun `opening and querying do not sample write or start a run`() {
        val f = Fixture()
        repeat(5) { assertNull(f.core.readState().active) }
        assertEquals(0, f.samples); assertEquals(0, f.storage.writes)
        assertTrue(f.core.readState().history.isEmpty())
    }
    @Test fun `start requires the actual foreground owner`() {
        val f = Fixture()
        assertEquals("phone_observation_foreground_required", assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }.code)
        f.core.foreground.enter("other")
        assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }
        assertEquals(0, f.samples); assertEquals(0, f.storage.writes)
    }
    @Test fun `one run at a time and repeated foreground events do not create runs`() {
        val f = Fixture(); f.start()
        repeat(3) { f.core.foreground.enter("page") }
        assertEquals("phone_observation_running", assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }.code)
        assertEquals(1, f.samples); assertEquals(1L, f.core.readState().active!!.number)
    }
    @Test fun `unchanged ticks only sample each thirty seconds and record no changes`() {
        val f = Fixture(); f.start()
        repeat(29) { f.advance(1_000) }
        assertEquals(1, f.samples)
        f.advance(1_000)
        assertEquals(2, f.samples); assertEquals(0, f.core.readState().active!!.changes)
    }
    @Test fun `deadline is exactly ten minutes and no sample occurs at deadline`() {
        val f = Fixture(); f.start()
        repeat(19) { f.advance(30_000) }
        assertEquals(20, f.samples)
        f.advance(30_000)
        val state = f.core.readState()
        assertNull(state.active); assertEquals("completed", state.history.first().status)
        assertEquals(600_000L, state.history.first().elapsedMs)
        assertEquals(20, f.samples); assertEquals(0, state.history.first().changes)
    }
    @Test fun `late scheduler skips catchup and expired polls cannot run extra work`() {
        val f = Fixture(); f.start(); f.advance(590_000)
        assertEquals(2, f.samples)
        f.advance(10_000); repeat(10) { f.advance(30_000) }
        assertEquals(2, f.samples); assertNull(f.core.readState().active)
    }
    @Test fun `wall clock changes do not extend or prematurely end duration`() {
        val f = Fixture(); f.start()
        f.clock.wall = 0; f.advance(30_000)
        assertNotNull(f.core.readState().active)
        f.clock.wall = Long.MAX_VALUE; f.advance(570_000)
        assertEquals("completed", f.core.readState().history.first().status)
        assertEquals(1_000_000L, f.core.readState().history.first().startedAtMs)
    }
    @Test fun `monotonic clock rollback fails closed`() {
        val f = Fixture(); f.start(); f.clock.elapsed = 1; f.core.poll()
        assertEquals("clock_error", f.core.readState().history.first().status)
        assertEquals(1, f.samples)
    }
    @Test fun `change count ignores first sample and counts only changed facts`() {
        val f = Fixture(); f.start(); f.device = f.device.copy(batteryPercent = 51)
        f.advance(30_000); f.advance(30_000)
        assertEquals(3, f.samples); assertEquals(1, f.core.readState().active!!.changes)
    }
    @Test fun `leaving foreground stops without another sample and resume does not restart`() {
        val f = Fixture(); f.start(); f.core.foreground.leave("page"); f.advance(30_000)
        assertEquals("background_paused", f.core.readState().history.first().status)
        f.core.foreground.enter("page"); f.advance(30_000)
        assertNull(f.core.readState().active); assertEquals(1, f.samples)
        f.core.start("page"); assertEquals(2L, f.core.readState().active!!.number)
    }
    @Test fun `leave then immediate resume invalidates old run lease`() {
        val f = Fixture(); f.start()
        f.core.foreground.leave("page"); f.core.foreground.enter("page")
        assertNull(f.core.readState().active)
        f.core.pauseIfNotForeground()
        assertNull(f.core.readState().active); assertEquals("background_paused", f.core.readState().history.first().status)
    }
    @Test fun `a stale page cannot revoke a new foreground owner`() {
        val f = Fixture(); f.core.foreground.enter("old"); f.core.foreground.enter("page")
        f.core.foreground.leave("old"); f.core.start("page")
        assertNotNull(f.core.readState().active)
    }
    @Test fun `revocation during a read discards the observation`() {
        val f = Fixture(); f.callback = { f.core.foreground.leave("page") }; f.start()
        val state = f.core.readState()
        assertNull(state.active); assertNull(state.latestSample)
        assertEquals("background_paused", state.history.first().status)
        assertEquals(0, state.history.first().samples)
    }
    @Test fun `stop is terminal idempotent and never resumes`() {
        val f = Fixture(); f.start(); f.core.stop()
        val writes = f.storage.writes
        repeat(5) { f.core.stop(); f.advance(30_000) }
        assertEquals(writes, f.storage.writes); assertEquals(1, f.samples)
        assertEquals("stopped", f.core.readState().history.single().status)
    }
    @Test fun `stop fence discards a late sample and permits explicit restart on the same page`() {
        val f = Fixture(); f.start()
        f.callback = { f.core.requestStop() }
        f.advance(30_000)
        val stopped = f.core.readState()
        assertNull(stopped.active); assertEquals("stopped", stopped.history.first().status)
        assertEquals(1, stopped.history.first().samples)
        f.callback = null; f.core.start("page")
        assertEquals(2L, f.core.readState().active!!.number)
    }
    @Test fun `stop fence does not wait for a sampler holding the core monitor`() {
        val f = Fixture(); f.start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>(null)
        f.callback = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        f.clock.elapsed += 30_000
        val worker = Thread { try { f.core.poll() } catch (error: Throwable) { failed.set(error) } }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            f.core.requestStop()
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive); assertNull(failed.get())
        assertEquals("stopped", f.core.readState().history.first().status)
        assertEquals(1, f.core.readState().history.first().samples)
    }
    @Test fun `an old ticker cannot sample a new run`() {
        val f = Fixture(); f.start(); f.core.stop(); f.core.start("page")
        f.clock.elapsed += 30_000
        val count = f.samples
        f.core.poll(expectedNumber = 1)
        assertEquals(count, f.samples); assertEquals(2L, f.core.readState().active!!.number)
        f.core.poll(expectedNumber = 2)
        assertEquals(count + 1, f.samples)
    }
    @Test fun `cancelled sampler propagates cancellation and terminates run`() {
        val f = Fixture(); f.core.foreground.enter("page")
        f.callback = { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { f.core.start("page") }
        assertNull(f.core.readState().active)
        assertEquals("stopped", f.core.readState().history.first().status)
    }
    @Test fun `sampler failure has fixed safe status without exception contents`() {
        val f = Fixture(); f.callback = { error("private-credential sentinel") }; f.start()
        assertEquals("sample_error", f.core.readState().history.first().status)
        assertFalse(f.storage.text.orEmpty().contains("sentinel"))
    }
    @Test fun `restart restores an interruption summary but never samples or writes`() {
        val f = Fixture(); f.start(); f.advance(30_000)
        val beforeWrites = f.storage.writes
        val restarted = OrbisPhoneObservation(f.storage, f.clock, { error("must never sample") })
        assertNull(restarted.readState().active)
        assertEquals("interrupted", restarted.readState().history.first().status)
        assertEquals(2, restarted.readState().history.first().samples)
        assertEquals(beforeWrites, f.storage.writes)
        restarted.poll(); assertEquals(beforeWrites, f.storage.writes)
    }
    @Test fun `history is bounded summaries only without device facts`() {
        val f = Fixture(); f.core.foreground.enter("page")
        repeat(12) { f.core.start("page"); f.core.stop() }
        assertEquals(10, f.core.readState().history.size)
        assertEquals(12L, f.core.readState().history.first().number)
        assertFalse(f.storage.text.orEmpty().contains("test-brand"))
        assertFalse(f.storage.text.orEmpty().contains("batteryPercent"))
    }
    @Test fun `corrupt unknown-version or excessive archives never overwrite or run`() {
        for (text in listOf("not-json private-sentinel", """{"version":2}""", "x".repeat(33_000),
            Json.encodeToString(OrbisObservationArchive(nextNumber = 2,
                history = listOf(OrbisObservationSummary(1, 1, "running")))))) {
            val f = Fixture(); f.storage.text = text; f.core.reload(); f.core.foreground.enter("page")
            assertTrue(f.core.readState().storageBlocked)
            assertEquals("phone_observation_storage_unavailable", assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }.code)
            assertEquals(text, f.storage.text); assertEquals(0, f.samples); assertEquals(0, f.storage.writes)
        }
    }
    @Test fun `write then throw does not pretend success and reload preserves interruption`() {
        val f = Fixture(); f.storage.failAt = 1; f.storage.failAfterWrite = true
        f.core.foreground.enter("page")
        assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }
        assertTrue(f.core.readState().storageBlocked); assertNull(f.core.readState().active); assertEquals(0, f.samples)
        val written = f.storage.text
        assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }
        assertEquals(written, f.storage.text)
        f.storage.failAt = -1; f.core.reload()
        assertEquals("interrupted", f.core.readState().history.first().status)
        f.core.start("page"); assertEquals(2L, f.core.readState().active!!.number)
    }
    @Test fun `failure during a later checkpoint blocks further samples`() {
        val f = Fixture(); f.start(); f.storage.failAt = f.storage.writes + 1
        f.clock.elapsed += 30_000
        assertThrows(OrbisPhoneException::class.java) { f.core.poll() }
        assertTrue(f.core.readState().storageBlocked)
        repeat(5) { f.advance(30_000) }
        assertEquals(2, f.samples)
    }
    @Test fun `invalid device data stops instead of saving fabricated measurements`() {
        val f = Fixture(); f.device = f.device.copy(batteryPercent = 200); f.start()
        assertEquals("sample_error", f.core.readState().history.first().status)
        assertNull(f.core.readState().latestSample)
    }
    @Test fun `an externally changed archive is not overwritten from a stale in memory state`() {
        val f = Fixture(); f.start()
        f.storage.text = "externally-replaced sentinel"
        val writes = f.storage.writes
        assertThrows(OrbisPhoneException::class.java) { f.core.stop() }
        assertEquals("externally-replaced sentinel", f.storage.text)
        assertEquals(writes, f.storage.writes)
        assertTrue(f.core.readState().storageBlocked)
    }
    @Test fun `archive read failure blocks writes and exposes only a fixed error`() {
        val f = Fixture(); f.storage.failRead = true; f.core.reload()
        assertEquals("phone_observation_storage_unavailable", f.core.readState().error)
        assertTrue(f.core.readState().storageBlocked)
        f.core.foreground.enter("page")
        assertThrows(OrbisPhoneException::class.java) { f.core.start("page") }
        assertEquals(0, f.storage.writes)
    }
}
