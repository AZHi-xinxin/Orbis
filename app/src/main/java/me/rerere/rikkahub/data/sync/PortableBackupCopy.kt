package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** Same database/files snapshot; only the portable settings copy loses connection credentials. */
internal suspend fun portableBackupCopy(source: File, directory: File): File {
    val target = File.createTempFile("portable-backup-", ".zip", directory)
    val coroutine = currentCoroutineContext()
    try {
        ZipFile(source).use { input ->
            NativeBackupBudget.inspect(source.length(), input.entries().asSequence())
            ZipOutputStream(NativeBackupBudget.Output(target.outputStream()) { directory.usableSpace }).use { output ->
                for(entry in input.entries()) {
                    coroutine.ensureActive()
                    if(entry.isDirectory) continue
                    output.putNextEntry(ZipEntry(entry.name))
                    if(entry.name == "settings.json") {
                        val bytes = ByteArrayOutputStream()
                        ArchiveCapacity.copyZipEntry(input, entry, bytes, NativeBackupBudget.MAX_SETTINGS_BYTES, { coroutine.ensureActive() })
                        val value = BackupSettingsPrivacy.sanitize(bytes.toString("UTF-8")).toByteArray(Charsets.UTF_8)
                        require(value.size <= NativeBackupBudget.MAX_SETTINGS_BYTES)
                        output.write(value)
                    } else ArchiveCapacity.copyZipEntry(input, entry, output, NativeBackupBudget.entryLimit(entry.name), { coroutine.ensureActive() })
                    output.closeEntry()
                }
            }
        }
        ZipFile(target).use { NativeBackupBudget.inspect(target.length(), it.entries().asSequence()) }
        return target
    } catch(error: Throwable) { target.delete(); throw error }
}
