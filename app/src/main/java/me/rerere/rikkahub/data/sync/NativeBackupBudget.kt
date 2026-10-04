package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import me.rerere.rikkahub.data.sync.importer.ArchiveFailure
import me.rerere.rikkahub.data.sync.importer.ArchiveReadException
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry

/** The producer and consumer use the same limits: a successful export is never too big to restore. */
internal object NativeBackupBudget {
    const val MAX_SETTINGS_BYTES = 8L * ArchiveCapacity.MIB
    data class Inspection(val expandedBytes: Long, val databaseBytes: Long, val entries: Int)

    fun entryLimit(name: String): Long = OrbisLocalToolBackup.maxBytes(name)?.toLong()
        ?: if (name == "settings.json") MAX_SETTINGS_BYTES else ArchiveCapacity.MAX_DISK_ENTRY_BYTES

    fun inspect(archiveBytes: Long, entries: Sequence<ZipEntry>): Inspection {
        ArchiveCapacity.requireSize(archiveBytes, ArchiveCapacity.MAX_ZIP_BYTES)
        val seen = hashSetOf<String>()
        var total = 0L
        var database = 0L
        var count = 0
        entries.forEach { entry ->
            if (++count > ArchiveCapacity.MAX_ENTRIES) throw ArchiveReadException(ArchiveFailure.SIZE_LIMIT)
            ArchiveCapacity.requireSafePath(entry.name, entry.isDirectory)
            if (!seen.add(entry.name)) throw ArchiveReadException(ArchiveFailure.UNSAFE_PATH)
            ArchiveCapacity.requireSize(entry.size, entryLimit(entry.name), allowEmpty = true)
            total += entry.size
            ArchiveCapacity.requireSize(total, ArchiveCapacity.MAX_EXPANDED_BYTES, allowEmpty = true)
            if (entry.name == DatabaseBackup.ARCHIVE_DATABASE || entry.name == DatabaseBackup.WAL) database += entry.size
        }
        return Inspection(total, database, count)
    }

    /** Includes compressed bytes and ZIP directory/footer, and checks real free space before every write. */
    class Output(output: OutputStream, private val freeBytes: () -> Long) : FilterOutputStream(output) {
        private var total = 0L
        override fun write(value: Int) { account(1); out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            account(length); out.write(bytes, offset, length)
        }
        private fun account(count: Int) {
            total += count
            ArchiveCapacity.requireSize(total, ArchiveCapacity.MAX_ZIP_BYTES, allowEmpty = true)
            ArchiveCapacity.requireSpace(freeBytes(), count.toLong())
        }
    }
}
