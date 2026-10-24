package me.rerere.rikkahub.data.ai.checkpoint

import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid

/** Supply Context.noBackupFilesDir. Never place these private chat tails on shared/external storage. */
class AndroidGenerationCheckpointStore private constructor(private val directory: File,
    private val conflicts: File? = null,
) : GenerationCheckpointStore {
    private fun atomic(id: Uuid): AtomicFile {
        check(directory.isDirectory || directory.mkdirs()) { "checkpoint_directory_unavailable" }
        val file = File(directory, "$id.json")
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "checkpoint_path_invalid" }
        return AtomicFile(file)
    }

    override fun read(conversationId: Uuid): ByteArray? {
        val file = atomic(conversationId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() <= GenerationCheckpointJournal.MAX_BYTES - count) { "checkpoint_too_large" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    override fun exists(conversationId: Uuid): Boolean {
        val file = atomic(conversationId)
        // AtomicFile's backup is recoverable committed state. Its .new file alone was never
        // acknowledged durable and cannot have authorized an external tool invocation.
        return file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    }

    override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
        check(bytes.size <= GenerationCheckpointJournal.MAX_BYTES) { "checkpoint_too_large" }
        val file = atomic(conversationId)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            // AtomicFile.finishWrite historically logs fsync failures instead of throwing them.
            // Explicit sync must succeed before reporting a durable execution boundary.
            stream.fd.sync()
            file.finishWrite(stream)
            // Detect a failed replacement instead of permitting a tool on an old STARTED record.
            check(read(conversationId)?.contentEquals(bytes) == true) { "checkpoint_readback_mismatch" }
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun delete(conversationId: Uuid) {
        val file = atomic(conversationId)
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
            !File(file.baseFile.path + ".new").exists()) { "checkpoint_delete_failed" }
    }

    override fun preserveConflict(conversationId: Uuid, bytes: ByteArray) {
        check(bytes.size in 1..GenerationCheckpointJournal.MAX_BYTES)
        val folder = checkNotNull(conflicts) { "checkpoint_conflict_preservation_unavailable" }
        check(!java.nio.file.Files.isSymbolicLink(folder.toPath()))
        check(folder.isDirectory || folder.mkdirs())
        check(folder.canonicalFile.parentFile == directory.canonicalFile.parentFile)
        val target = File(folder, ContinuationConflictCopies.name(conversationId, bytes))
        check(!java.nio.file.Files.isSymbolicLink(target.toPath()) && target.canonicalFile.parentFile == folder.canonicalFile)
        for (suffix in listOf(".bak", ".new")) check(!java.nio.file.Files.isSymbolicLink(File(target.path + suffix).toPath()))
        val file = AtomicFile(target)
        fun matches(): Boolean = file.openRead().use { input ->
            val actual = ByteArray(bytes.size + 1)
            var count = 0
            while (count < actual.size) {
                val read = input.read(actual, count, actual.size - count)
                if (read < 0) break
                count += read
            }
            count == bytes.size && actual.copyOf(count).contentEquals(bytes)
        }
        if (target.exists() || File(target.path + ".bak").exists()) {
            check(matches()) { "checkpoint_conflict_readback_mismatch" }
        } else {
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                file.finishWrite(stream)
                check(matches()) { "checkpoint_conflict_readback_mismatch" }
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        // A durable file is insufficient if its directory entry disappears on power loss.
        // Sync both the archive directory and its parent BEFORE the caller frees the slot.
        for (dir in listOf(folder, folder.parentFile)) {
            check(dir.isDirectory)
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }

    companion object {
        fun create(noBackupFilesDir: File): AndroidGenerationCheckpointStore =
            AndroidGenerationCheckpointStore(File(noBackupFilesDir, "orbis-generation-journal-v1"),
                File(noBackupFilesDir, ContinuationConflictCopies.GENERATION_DIRECTORY))

        fun createHumanContinuation(noBackupFilesDir: File): AndroidGenerationCheckpointStore =
            AndroidGenerationCheckpointStore(File(noBackupFilesDir, "orbis-human-continuation-v1"),
                File(noBackupFilesDir, ContinuationConflictCopies.DIRECTORY))
    }
}
