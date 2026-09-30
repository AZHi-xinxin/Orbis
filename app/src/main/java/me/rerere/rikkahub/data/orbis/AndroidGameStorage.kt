package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** App-private, atomic local game storage; never reads or changes Rikka chat databases. */
private class AndroidGameStorage(context: Context, filename: String = "gomoku-v1.json", private val maxBytes: Int = 8 * 1024 * 1024) : OrbisGameStorage {
    private val file = AtomicFile(File(context.filesDir, "orbis-games/$filename"))
    override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "game_storage_too_large" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
    override fun write(value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            try { file.failWrite(stream) } catch (rollbackError: Throwable) { error.addSuppressed(rollbackError) }
            throw error
        }
        // AtomicFile may log some sync/rename failures instead of throwing. Do not report success
        // if a subsequent read cannot observe the complete new payload.
        // A failure here is indeterminate, not proof of rollback. The repository blocks writes
        // until the user explicitly reloads and validates the real disk state.
        if (read() != value) throw IOException("game_storage_verify_failed")
    }
}

/** One lock and StateFlow for all local entry points. Initialization happens on Dispatchers.IO. */
object OrbisGames {
    @Volatile private var repository: OrbisGameRepository? = null
    @Synchronized fun open(context: Context): OrbisGameRepository = repository
        ?: OrbisGameRepository(AndroidGameStorage(context.applicationContext)).also { repository = it }
}

object OrbisMiniGames {
    @Volatile private var repository: OrbisMiniGameRepository? = null
    @Synchronized fun open(context: Context): OrbisMiniGameRepository = repository
        ?: OrbisMiniGameRepository(AndroidGameStorage(context.applicationContext, "library-v1.json", 128 * 1024 * 1024))
            .also { repository = it }
}
