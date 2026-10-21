package me.rerere.rikkahub.data.sync

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import me.rerere.rikkahub.data.datastore.BackupReminderConfig

/** Legacy global timestamps cannot prove which destination received an export. */
internal fun BackupReminderConfig.completedAt(key: String): Long = destinationTimes[key] ?: 0L

internal fun BackupReminderConfig.completed(key: String, at: Long): BackupReminderConfig {
    require(at > 0 && key.isNotBlank())
    val next = destinationTimes + (key to maxOf(destinationTimes[key] ?: 0L, at))
    return copy(lastBackupTime = maxOf(lastBackupTime, at),
        destinationTimes = next.entries.sortedByDescending { it.value }.take(100).associate { it.toPair() })
}

/** Success means a closed destination can be read back with the exact bytes, not merely prepared. */
internal object VerifiedBackupExport {
    fun copy(source: () -> InputStream, destination: () -> OutputStream, readBack: () -> InputStream,
             checkCancelled: () -> Unit = {}) {
        val expected = MessageDigest.getInstance("SHA-256")
        var written = 0L
        source().use { input ->
            destination().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count); expected.update(buffer, 0, count); written += count
                }
                output.flush()
            }
        }
        require(written > 0) { "backup_export_empty" }
        val actual = MessageDigest.getInstance("SHA-256")
        var checked = 0L
        readBack().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                checked += count
                require(checked <= written) { "backup_export_mismatch" }
                actual.update(buffer, 0, count)
            }
        }
        require(checked == written && expected.digest().contentEquals(actual.digest())) { "backup_export_mismatch" }
    }
}
