package me.rerere.rikkahub.data.orbis.soup

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Only this fixed app-private file is accessed. No chat database, shared storage, VPS, or WebView. */
private class SoupAndroidStorage(context: Context) : SoupStorage {
    private val file = AtomicFile(File(context.filesDir, "orbis-games/local-soup-v1.json"))
    override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer); if (size < 0) break
                require(out.size() + size <= 4 * 1024 * 1024) { "soup_storage_large" }; out.write(buffer, 0, size)
            }
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        }
    }
    override fun write(value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync(); file.finishWrite(stream)
        } catch (error: Throwable) { runCatching { file.failWrite(stream) }; throw error }
        if (read() != value) throw IOException("soup_storage_verify_failed")
    }
}

internal object LocalSoup {
    @Volatile private var repository: SoupRepository? = null
    @Synchronized fun open(context: Context): SoupRepository = repository ?: SoupRepository(
        SoupAndroidStorage(context.applicationContext),
    ).also { it.recoverInterrupted(); repository = it }
}
