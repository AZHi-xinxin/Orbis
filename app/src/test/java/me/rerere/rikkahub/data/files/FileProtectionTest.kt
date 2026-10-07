package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FileProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `automatic locks survive wallpaper change and explicit unlock survives rediscovery`() {
        val file = File(temporary.root, FileProtection.PATH)
        val store = FileProtection(file)
        store.rememberAutomatic(setOf("upload/old.png"))
        store.rememberAutomatic(setOf("upload/new.png"))
        assertTrue(store.snapshot().isLocked("upload/old.png"))
        store.setLocked("upload/old.png", false)
        store.rememberAutomatic(setOf("upload/old.png"))
        assertFalse(FileProtection(file).snapshot().isLocked("upload/old.png"))
        store.setLocked("upload/old.png", true)
        assertTrue(store.snapshot().isLocked("upload/old.png"))
    }
    @Test fun `corruption and invalid paths are never overwritten`() {
        val file = temporary.newFile()
        file.writeText("broken")
        val store = FileProtection(file)
        assertThrows(Exception::class.java) { store.setLocked("upload/x.png", true) }
        assertEquals("broken", file.readText())
        listOf("../x", "upload/../x", "upload/..", "upload/x/y", "upload/x\\y", "upload/x:y").forEach {
            assertFalse(FileProtection.validPath(it))
        }
    }
    @Test fun `only flat owned upload paths can be classified as avatar files`() {
        val root = temporary.root.canonicalFile
        val image = File(root, "upload/avatar.png")
        assertEquals("upload/avatar.png", FileProtection.relativeUpload(root, image.toURI().toString()))
        assertNull(FileProtection.relativeUpload(root, "https://example.invalid/avatar.png"))
        assertNull(FileProtection.relativeUpload(root, File(root.parentFile, "outside.png").path))
        assertNull(FileProtection.relativeUpload(root, File(root, "upload/../keys.txt").path))
    }

    @Test(timeout = 15_000) fun `appearance protection first keeps its guard until waiting cleaner sees the lock`() {
        val root = temporary.root.canonicalFile
        val path = "upload/selected.png"
        val image = File(root, path).apply { parentFile!!.mkdirs(); writeText("synthetic artwork") }
        val setter = FileProtection(File(root, FileProtection.PATH))
        val cleaner = FileProtection(File(root, "./${FileProtection.PATH}").canonicalFile)
        val protected = CountDownLatch(1)
        val releaseSetter = CountDownLatch(1)
        val cleanerAttempted = CountDownLatch(1)
        val deleted = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val settingThread = worker("appearance-first-setter", failure) {
            setter.withAutomaticProtection(emptySet()) {
                // Execute the same validation + retention helper called by SettingsStore.
                setter.protectAppearanceChange(root, emptySet(), setOf(path))
                protected.countDown()
                await(releaseSetter)
            }
        }
        var cleanerThread: Thread? = null
        try {
            await(protected)
            cleanerThread = worker("appearance-first-cleaner", failure) {
                cleanerAttempted.countDown()
                cleaner.withAutomaticProtection(emptySet()) { state ->
                    if (!state.isLocked(path)) deleted.set(image.delete())
                }
            }
            await(cleanerAttempted)
            awaitMonitorBlocked(cleanerThread)
        } finally {
            releaseSetter.countDown()
            finish(settingThread, cleanerThread)
        }
        failure.get()?.let { throw AssertionError("Synthetic worker failed", it) }
        assertFalse(deleted.get())
        assertEquals("synthetic artwork", image.readText())
        assertTrue(FileProtection(File(root, FileProtection.PATH)).snapshot().isLocked(path))
    }

    @Test(timeout = 15_000) fun `cleaner first rejects missing new artwork without publishing or locking missing reference`() {
        val root = temporary.root.canonicalFile
        val oldPath = "upload/previous.png"
        val newPath = "upload/new.png"
        File(root, oldPath).apply { parentFile!!.mkdirs(); writeText("previous artwork") }
        val image = File(root, newPath).apply { writeText("new artwork") }
        val cleaner = FileProtection(File(root, FileProtection.PATH))
        val setter = FileProtection(File(root, "./${FileProtection.PATH}").canonicalFile)
        setter.protectAppearanceChange(root, emptySet(), setOf(oldPath))
        val originalProtection = File(root, FileProtection.PATH).readBytes()
        val checkedUnlocked = CountDownLatch(1)
        val releaseCleaner = CountDownLatch(1)
        val setterAttempted = CountDownLatch(1)
        val published = AtomicReference(oldPath)
        val rejected = AtomicReference<IllegalStateException?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val cleanerThread = worker("cleaner-first-cleaner", failure) {
            cleaner.withAutomaticProtection(emptySet()) { state ->
                assertFalse(state.isLocked(newPath))
                // Stop after the final unlocked check, before the physical deletion.
                checkedUnlocked.countDown()
                await(releaseCleaner)
                assertTrue(image.delete())
            }
        }
        var settingThread: Thread? = null
        try {
            await(checkedUnlocked)
            settingThread = worker("cleaner-first-setter", failure) {
                setterAttempted.countDown()
                try {
                    setter.protectAppearanceChange(root, setOf(oldPath), setOf(newPath))
                    published.set(newPath)
                } catch (error: IllegalStateException) {
                    rejected.set(error)
                }
            }
            await(setterAttempted)
            awaitMonitorBlocked(settingThread)
        } finally {
            releaseCleaner.countDown()
            finish(cleanerThread, settingThread)
        }
        failure.get()?.let { throw AssertionError("Synthetic worker failed", it) }
        assertNotNull("The real SettingsStore guard must reject deleted new artwork", rejected.get())
        assertTrue(rejected.get()!!.message!!.contains("原设置未改动"))
        assertEquals(oldPath, published.get())
        assertFalse(image.exists())
        assertArrayEquals(originalProtection, File(root, FileProtection.PATH).readBytes())
    }

    @Test fun `appearance helper validates only new references so missing old artwork can be replaced`() {
        val root = temporary.root.canonicalFile
        val oldPath = "upload/already-missing.png"
        val newPath = "upload/replacement.png"
        File(root, newPath).apply { parentFile!!.mkdirs(); writeText("replacement") }
        val protection = FileProtection(File(root, FileProtection.PATH))
        protection.protectAppearanceChange(root, setOf(oldPath), setOf(newPath))
        assertTrue(protection.snapshot().isLocked(newPath))
        val before = File(root, FileProtection.PATH).readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            protection.protectAppearanceChange(root, setOf(newPath), setOf("upload/../outside.png"))
        }
        assertArrayEquals(before, File(root, FileProtection.PATH).readBytes())
    }

    private fun worker(name: String, failure: AtomicReference<Throwable?>, action: () -> Unit): Thread =
        Thread({ try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) } }, name)
            .apply { isDaemon = true; start() }

    private fun await(latch: CountDownLatch) {
        assertTrue("Synthetic worker did not reach its checkpoint", latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitMonitorBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        assertEquals("Other canonical-path instance must wait for the entire protected action",
            Thread.State.BLOCKED, thread.state)
    }

    private fun finish(vararg threads: Thread?) {
        threads.filterNotNull().forEach { thread ->
            thread.join(5_000)
            assertFalse("Synthetic worker must finish before its fixture is removed", thread.isAlive)
        }
    }
}
