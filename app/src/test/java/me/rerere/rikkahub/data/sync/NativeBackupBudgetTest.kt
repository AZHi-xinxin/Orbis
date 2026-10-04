package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import me.rerere.rikkahub.data.sync.importer.ArchiveFailure
import me.rerere.rikkahub.data.sync.importer.ArchiveReadException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry

class NativeBackupBudgetTest {
    @Test fun samePolicyAcceptsDatabaseLargerThanOld256MiBWithoutAllocatingIt() {
        val size = 3L * ArchiveCapacity.GIB
        val result = NativeBackupBudget.inspect(2L * ArchiveCapacity.GIB,
            sequenceOf(entry("settings.json", 100), entry(DatabaseBackup.ARCHIVE_DATABASE, size)))
        assertEquals(size, result.databaseBytes)
        assertEquals(size + 100, result.expandedBytes)
    }

    @Test fun exporterAndRestorerBothRefuseUnrestorableSettingsOrEntry() {
        listOf(entry("settings.json", NativeBackupBudget.MAX_SETTINGS_BYTES + 1),
            entry(DatabaseBackup.ARCHIVE_DATABASE, ArchiveCapacity.MAX_DISK_ENTRY_BYTES + 1)).forEach {
            assertEquals(ArchiveFailure.SIZE_LIMIT, failure { NativeBackupBudget.inspect(1, sequenceOf(it)) }.reason)
        }
    }

    @Test fun expansionPathAndEntryCountRemainBounded() {
        assertEquals(ArchiveFailure.SIZE_LIMIT, failure {
            NativeBackupBudget.inspect(1, (1..3).asSequence().map { entry("upload/$it", 8L * ArchiveCapacity.GIB) })
        }.reason)
        assertEquals(ArchiveFailure.UNSAFE_PATH, failure { NativeBackupBudget.inspect(1, sequenceOf(entry("../db", 1))) }.reason)
        assertEquals(ArchiveFailure.UNSAFE_PATH, failure {
            NativeBackupBudget.inspect(1, sequenceOf(entry("settings.json", 1), entry("settings.json", 1)))
        }.reason)
    }

    @Test fun outputChecksSpaceBeforeWritingAndDoesNotTruncateSuccessfulWrites() {
        val bytes = ByteArrayOutputStream()
        val output = NativeBackupBudget.Output(bytes) { ArchiveCapacity.MIN_FREE_BYTES + 2 }
        assertEquals(ArchiveFailure.INSUFFICIENT_SPACE, failure { output.write(byteArrayOf(1, 2, 3)) }.reason)
        assertEquals(0, bytes.size())
        NativeBackupBudget.Output(bytes) { ArchiveCapacity.MIN_FREE_BYTES + 100 }.use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
    }

    private fun entry(name: String, bytes: Long) = ZipEntry(name).also { it.size = bytes }
    private fun failure(block: () -> Unit): ArchiveReadException = try {
        block(); fail("expected failure"); error("unreachable")
    } catch (failure: ArchiveReadException) { failure }
}
