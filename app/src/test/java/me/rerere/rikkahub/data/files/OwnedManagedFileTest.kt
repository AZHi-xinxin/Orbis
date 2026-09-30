package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnedManagedFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `successful publication retains its file and record`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val target = File(root, "upload/synthetic.bin")
        var removed = false
        val result = persistOwnedManagedFile(root, target, { it.writeText("synthetic bytes") },
            persist = { "synthetic record" }, removeRecord = { removed = true })
        assertEquals("synthetic record", result)
        assertEquals("synthetic bytes", target.readText())
        assertFalse(removed)
    }

    @Test fun `partial write failure removes only this new target`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val sibling = File(root, "existing.bin").apply { writeText("keep") }
        val target = File(root, "upload/new.bin")
        var persisted = false
        try {
            persistOwnedManagedFile(root, target, { it.writeText("partial"); error("synthetic write failure") },
                persist = { persisted = true }, removeRecord = {})
            fail("must fail")
        } catch (_: IllegalStateException) { }
        assertFalse(target.exists())
        assertFalse(persisted)
        assertEquals("keep", sibling.readText())
    }

    @Test fun `indeterminate record failure reclaims new file and its own record`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val target = File(root, "upload/new.bin")
        val records = mutableSetOf("existing")
        try {
            persistOwnedManagedFile(root, target, { it.writeText("new") },
                persist = { records.add("new"); error("synthetic post-insert failure") },
                removeRecord = { records.remove("new") })
            fail("must fail")
        } catch (_: IllegalStateException) { }
        assertFalse(target.exists())
        assertEquals(setOf("existing"), records)
    }

    @Test(timeout = 15_000) fun `cancellation after record insertion cleans in noncancellable context`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val target = File(root, "upload/new.bin")
        val inserted = CompletableDeferred<Unit>()
        var recordExists = false
        val task = launch {
            persistOwnedManagedFile<Unit>(root, target, { it.writeText("new") },
                persist = { recordExists = true; inserted.complete(Unit); awaitCancellation() },
                removeRecord = { recordExists = false })
        }
        inserted.await()
        task.cancelAndJoin()
        assertFalse(target.exists())
        assertFalse(recordExists)
    }

    @Test fun `existing target is never overwritten or reclaimed`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val target = File(root, "existing.bin").apply { writeText("keep") }
        var wrote = false
        var removed = false
        try {
            persistOwnedManagedFile(root, target, { wrote = true }, persist = { "unused" },
                removeRecord = { removed = true })
            fail("must reject existing target")
        } catch (_: IllegalStateException) { }
        assertEquals("keep", target.readText())
        assertFalse(wrote)
        assertFalse(removed)
    }

    @Test fun `out of root target is rejected before writes or cleanup`() = runBlocking<Unit> {
        val root = temporary.newFolder("owned").canonicalFile
        val target = temporary.newFile("outside.bin").apply { writeText("keep") }
        var touched = false
        try {
            persistOwnedManagedFile(root, target, { touched = true }, persist = { "unused" },
                removeRecord = { touched = true })
            fail("must reject out-of-root target")
        } catch (_: IllegalArgumentException) { }
        assertFalse(touched)
        assertEquals("keep", target.readText())
    }
}
