package me.rerere.rikkahub.data.ai.checkpoint

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid

/** Supply Context.noBackupFilesDir. Never place these private chat tails on shared/external storage. */
class AndroidGenerationCheckpointStore private constructor(private val directory: File) : GenerationCheckpointStore {
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

    companion object {
        fun create(noBackupFilesDir: File): AndroidGenerationCheckpointStore =
            AndroidGenerationCheckpointStore(File(noBackupFilesDir, "orbis-generation-journal-v1"))
    }
}
