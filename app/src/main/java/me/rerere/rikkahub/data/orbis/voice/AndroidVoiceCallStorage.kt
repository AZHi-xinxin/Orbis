package me.rerere.rikkahub.data.orbis.voice

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** One atomic file per call, confined to its dedicated application-private directory. */
internal class AndroidVoiceCallStorage(context: Context) : OrbisVoiceCallStorage {
    private val parent = context.filesDir.canonicalFile
    private val root = File(parent, "orbis-voice-calls")
    override val lockKey: String = root.absolutePath

    private fun checkRoot() {
        require(root.canonicalFile.parentFile == parent && root.canonicalFile.name == root.name) {
            "voice_call_storage_outside_archive"
        }
    }

    private fun file(id: String): AtomicFile {
        validateVoiceCallId(id)
        checkRoot()
        val base = File(root, "$id.json")
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach {
            require(it.canonicalFile.parentFile == root.canonicalFile && it.canonicalFile.name == it.name) {
                "voice_call_file_outside_archive"
            }
        }
        return AtomicFile(base)
    }

    override fun ids(): List<String> {
        checkRoot()
        if (!root.exists()) return emptyList()
        check(root.isDirectory) { "voice_call_archive_not_directory" }
        return (root.listFiles() ?: throw IOException("voice_call_archive_list_failed"))
            .mapNotNull { item ->
                when {
                    item.name.endsWith(".json.bak") -> item.name.removeSuffix(".json.bak")
                    item.name.endsWith(".json") -> item.name.removeSuffix(".json")
                    else -> null // AtomicFile's uncommitted .new file is not a record.
                }
            }.distinct().onEach(::validateVoiceCallId)
    }

    override fun read(id: String): String? {
        val atomic = file(id)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_BYTES) { "voice_call_archive_too_large" }
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
    }

    override fun write(id: String, value: String) {
        val atomic = file(id)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "voice_call_archive_too_large" }
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            try { atomic.failWrite(stream) } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
        if (read(id) != value) throw IOException("voice_call_archive_verify_failed")
    }

    private companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}
