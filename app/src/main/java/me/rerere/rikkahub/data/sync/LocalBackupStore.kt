package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** Not cache. App-private checkpoints survive a crash/update, NOT an uninstall or storage wipe. */
internal class LocalBackupStore(private val directory: File) : BackupObjectStore {
    init { require(directory.isDirectory || directory.mkdirs()) }
    private fun file(name: String): File {
        require(name.matches(Regex("orbis_v1_[a-f0-9-]{36}\\.(payload|complete\\.json)")))
        return File(directory, name).also { require(it.canonicalFile.parentFile == directory.canonicalFile) }
    }
    override suspend fun listNames() = directory.list().orEmpty().filter(BackupRestorePoint::isMarker)
    override suspend fun upload(name: String, file: File) {
        val target = file(name)
        require(!target.exists()) { "backup_version_exists" }
        val temporary = File.createTempFile("writing-", ".tmp", directory)
        try {
            copy(file, temporary, BackupRestorePoint.MAX_BYTES)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    override suspend fun download(name: String, file: File, maxBytes: Long) { copy(file(name), file, maxBytes) }
    override suspend fun delete(name: String) { val target = file(name); check(!target.exists() || target.delete()) }
    private suspend fun copy(source: File, target: File, limit: Long) {
        require(source.length() in 1..limit)
        ArchiveCapacity.requireSpace(target.parentFile!!.usableSpace, source.length())
        val coroutine = currentCoroutineContext()
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            var size = 0L; val buffer = ByteArray(64 * 1024)
            while(true) {
                coroutine.ensureActive(); val count = input.read(buffer); if(count < 0) break
                size += count; require(size <= limit)
                ArchiveCapacity.requireSpace(target.parentFile!!.usableSpace, count.toLong())
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
    }
}
