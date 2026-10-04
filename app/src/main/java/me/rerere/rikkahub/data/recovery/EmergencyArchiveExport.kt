package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The local archive is never removed or changed. A receipt is returned only after the selected
 * external destination has been closed, reopened and fully compared with the verified source.
 * Failure/cancellation may leave a partial external document, but can never authorize uninstall.
 * The Android caller must independently restrict the destination to a user-picked local document.
 */
internal object EmergencyArchiveExport {
    data class Receipt(val sizeBytes: Long, val sha256: String)

    fun saveVerified(
        archive: File,
        openDestination: () -> OutputStream,
        reopenDestination: () -> InputStream,
        checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("emergency_export_interrupted")
        },
        onProgress: (EmergencyArchiveProgress) -> Unit = {},
    ): Receipt {
        checkCancelled()
        // Bind a byte baseline BEFORE semantic validation, then prove it unchanged afterwards.
        // Otherwise a replaced source between verify() and baseline hashing could be copied
        // consistently despite no longer being the package that was actually validated.
        val sourceLength = archive.length()
        onProgress(EmergencyArchiveProgress("source_hash", 0, sourceLength))
        val expected = archive.inputStream().use { input ->
            transfer(input, null, checkCancelled) { count ->
                onProgress(EmergencyArchiveProgress("source_hash", count, sourceLength))
            }
        }
        checkCancelled()
        EmergencyArchive.verify(archive) { progress ->
            checkCancelled()
            onProgress(progress.copy(phase = "source_verify"))
        }
        onProgress(EmergencyArchiveProgress("source_recheck", 0, expected.sizeBytes))
        val rechecked = archive.inputStream().use { input ->
            transfer(input, null, checkCancelled) { count ->
                onProgress(EmergencyArchiveProgress("source_recheck", count, expected.sizeBytes))
            }
        }
        check(rechecked == expected) { "emergency_export_source_changed" }
        checkCancelled()
        onProgress(EmergencyArchiveProgress("save", 0, expected.sizeBytes))
        val written = openDestination().use { target ->
            archive.inputStream().use { input ->
                transfer(input, target, checkCancelled) { count ->
                    onProgress(EmergencyArchiveProgress("save", count, expected.sizeBytes))
                }
            }
        }
        check(written == expected) { "emergency_export_source_changed" }
        checkCancelled()
        onProgress(EmergencyArchiveProgress("readback", 0, expected.sizeBytes))
        val readBack = reopenDestination().use { input ->
            transfer(input, null, checkCancelled) { count ->
                onProgress(EmergencyArchiveProgress("readback", count, expected.sizeBytes))
            }
        }
        check(readBack == expected) { "emergency_export_checksum_mismatch" }
        checkCancelled()
        return readBack
    }

    private fun transfer(
        input: InputStream,
        output: OutputStream?,
        checkCancelled: () -> Unit,
        onBytes: (Long) -> Unit,
    ): Receipt {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) throw IOException("emergency_export_read_stalled")
            output?.write(buffer, 0, read)
            hash.update(buffer, 0, read)
            count = Math.addExact(count, read.toLong())
            onBytes(count)
        }
        return Receipt(count, hash.digest().joinToString("") { "%02x".format(it) })
    }
}
