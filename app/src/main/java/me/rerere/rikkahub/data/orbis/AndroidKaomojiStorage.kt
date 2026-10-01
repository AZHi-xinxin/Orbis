package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream

private class AndroidKaomojiStorage(context: Context) : OrbisKaomojiStorage {
    private val files = context.filesDir.canonicalFile
    private val directory = File(files, "orbis-kaomoji")
    private val manifest = AtomicFile(File(directory, "library-v1.json"))
    private fun validatePaths() {
        require(directory.canonicalFile == directory.absoluteFile) { "kaomoji_storage_invalid" }
        for (suffix in listOf("", ".bak", ".new")) {
            val file = File(manifest.baseFile.path + suffix)
            require(file.canonicalFile == file.absoluteFile) { "kaomoji_storage_invalid" }
        }
    }
    override fun read(): String? {
        validatePaths()
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists()) return null
        return manifest.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= OrbisKaomojiRepository.MAX_STORAGE_CHARS * 4) { "kaomoji_storage_too_large" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
    override fun write(value: String) {
        validatePaths()
        require(directory.isDirectory || directory.mkdirs()) { "kaomoji_storage_unavailable" }
        val stream = manifest.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            manifest.finishWrite(stream)
        } catch (error: Throwable) {
            manifest.failWrite(stream)
            throw error
        }
    }
}

object OrbisKaomojis {
    private val instances = mutableMapOf<String, OrbisKaomojiRepository>()
    /** Call from Dispatchers.IO. One shared lock/state for AI and human entry points. */
    @Synchronized fun open(context: Context): OrbisKaomojiRepository {
        val app = context.applicationContext
        return instances.getOrPut(app.filesDir.canonicalPath) { OrbisKaomojiRepository(AndroidKaomojiStorage(app)) }
    }
}
