package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class TemporaryFileCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_900_000_000_000L
    private val old = now - TimeUnit.DAYS.toMillis(2)
    private val kinds = TemporaryFileKind.entries.toSet()
    private fun cache() = temporary.newFolder("cache")
    private fun file(root: File, name: String, text: String = "synthetic backup", time: Long = old) =
        File(root, name).apply { requireNotNull(parentFile).mkdirs(); writeText(text); check(setLastModified(time)) }

    @Test fun `scan is read only and only selects old known regular files directly under cache`() {
        val root = cache()
        val eligible = file(root, "backup_20261101_120000_123.zip")
        val protected = listOf(file(root, "user-backup.zip"), file(root, "backup_20261101_120000_456.zip", time = now),
            file(root, "portable-backup-33.zip", text = ""), file(root, "nested/portable-backup-2.zip"),
            file(root, "no_backup/automatic-versions/orbis_v1_keep.payload"), file(root, "files/upload/photo.png"))
        File(root, "orbis-overwrite-4.tmp").mkdir()
        val scan = TemporaryFileCleanup(root, { now }).scan()
        assertEquals(listOf(eligible.name), scan.candidates.map { it.stamp.name })
        assertTrue(eligible.exists()); assertTrue(protected.all { it.exists() })
    }

    @Test fun `confirmed transfer cleanup returns accurate count and preserves unrelated storage`() {
        val root = cache()
        val names = listOf("backup_20261101_120000_12.zip", "portable-backup-13.zip", "orbis-overwrite-14.tmp",
            "temp_restore_15.zip", "orbis-rikka-import-16.zip", "version-restore-17.zip", "orbis-version-18.tmp")
        val files = names.map { file(root, it) }; val bytes = files.sumOf { it.length() }
        val keep = file(root, "chat.db")
        val cleaner = TemporaryFileCleanup(root, { now })
        val result = cleaner.clean(cleaner.scan(), kinds)
        assertEquals(names.size, result.deleted); assertEquals(bytes, result.bytes)
        assertFalse(result.backupBusy); assertEquals(0, result.skipped)
        assertTrue(files.none { it.exists() }); assertTrue(keep.exists())
    }

    @Test fun `duplicate repair archives keep one exact copy and preserve unique versions`() {
        val root = cache()
        val copies = (1..4).map { file(root, "orbis-stage22-backup-copy-$it.tar") }
        val unique = file(root, "orbis-stage22-backup-other.tar", "different backup")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan()
        assertEquals(3, scan.count(kinds))
        val result = cleaner.clean(scan, setOf(TemporaryFileKind.DUPLICATE_REPAIR))
        assertEquals(3, result.deleted); assertEquals(1, copies.count { it.exists() })
        assertTrue(unique.exists()); assertTrue(cleaner.scan().candidates.isEmpty())
    }

    @Test fun `category deselection and newly arrived files do not expand the approved scope`() {
        val root = cache(); val transfer = file(root, "portable-backup-1.zip")
        file(root, "orbis-stage22-backup-a.tar"); file(root, "orbis-stage22-backup-b.tar")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan()
        val later = file(root, "portable-backup-2.zip")
        val receipt = cleaner.clean(scan, setOf(TemporaryFileKind.DUPLICATE_REPAIR))
        assertEquals(1, receipt.deleted); assertTrue(transfer.exists()); assertTrue(later.exists())
    }

    @Test fun `updated or replaced files are skipped even if a replacement reuses size and timestamp`() {
        val root = cache(); val changed = file(root, "portable-backup-1.zip"); val replaced = file(root, "portable-backup-2.zip")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan()
        changed.writeText("new active content")
        Files.move(replaced.toPath(), root.resolve("keep-renamed").toPath())
        file(root, replaced.name)
        val result = cleaner.clean(scan, kinds)
        assertEquals(0, result.deleted); assertEquals(2, result.skipped)
    }

    @Test fun `missing or changed keeper never allows deleting the last valid backup`() {
        val root = cache(); file(root, "orbis-stage22-backup-a.tar"); file(root, "orbis-stage22-backup-b.tar")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan(); val entry = scan.candidates.single()
        val keep = File(root, entry.keeper!!.name)
        keep.writeText("X".repeat(entry.keeper.size.toInt())); keep.setLastModified(entry.keeper.modified)
        assertEquals(0, cleaner.clean(scan, kinds).deleted)
        keep.delete()
        assertEquals(0, cleaner.clean(scan, kinds).deleted)
        assertTrue(File(root, entry.stamp.name).exists())
    }

    @Test fun `symbolic links during scan or swapped in after confirmation never delete their targets`() {
        val root = cache(); val outside = file(temporary.root, "outside.txt")
        val link = root.resolve("portable-backup-1.zip")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val real = file(root, "portable-backup-2.zip")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan(); assertEquals(1, scan.candidates.size)
        real.delete(); Files.createSymbolicLink(real.toPath(), outside.toPath())
        assertEquals(0, cleaner.clean(scan, kinds).deleted); assertTrue(outside.exists())
    }

    @Test fun `cache root replacement and symbolic root fail closed`() {
        val root = cache(); file(root, "portable-backup-1.zip")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan()
        val moved = File(temporary.root, "old-cache"); Files.move(root.toPath(), moved.toPath()); root.mkdir()
        val new = file(root, "portable-backup-1.zip")
        assertTrue(runCatching { cleaner.clean(scan, kinds) }.isFailure); assertTrue(new.exists())
        val alias = File(temporary.root, "alias"); Files.createSymbolicLink(alias.toPath(), moved.toPath())
        assertTrue(runCatching { TemporaryFileCleanup(alias, { now }).scan() }.isFailure)
    }

    @Test fun `active backup blocks deletion and becoming active stops remaining files`() {
        val root = cache(); file(root, "portable-backup-1.zip"); file(root, "portable-backup-2.zip")
        var allowed = false
        val cleaner = TemporaryFileCleanup(root, { now }, { allowed }); val scan = cleaner.scan()
        assertTrue(cleaner.clean(scan, kinds).backupBusy); assertEquals(2, root.listFiles()!!.size)
        allowed = true
        val partial = TemporaryFileCleanup(root, { now }, { allowed && File(root, "portable-backup-1.zip").exists() })
        val result = partial.clean(scan, kinds)
        assertEquals(1, result.deleted); assertTrue(result.backupBusy); assertTrue(File(root, "portable-backup-2.zip").exists())
    }

    @Test fun `recent future and unknown timestamp files are kept`() {
        val root = cache()
        for ((index, time) in listOf(now, now + 10000, 0L, now - TimeUnit.HOURS.toMillis(23)).withIndex())
            file(root, "portable-backup-$index.zip", time = time)
        assertTrue(TemporaryFileCleanup(root, { now }).scan().candidates.isEmpty())
    }

    @Test fun `cancellation never turns into a broad retry or unrelated cleanup`() {
        val root = cache(); val file = file(root, "portable-backup-1.zip")
        val cleaner = TemporaryFileCleanup(root, { now }); val scan = cleaner.scan()
        assertTrue(runCatching { cleaner.clean(scan, kinds) { throw CancellationException() } }.exceptionOrNull() is CancellationException)
        assertTrue(file.exists())
    }
}
