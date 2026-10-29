package me.rerere.rikkahub.data.orbis.diagnostics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.Json

/** Five bounded code-only receipts per assistant, private to this installation. */
internal object DiagnosticJournal {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(context: Context, owner: String): AtomicFile {
        require(owner.matches(Regex("[a-fA-F0-9-]{36}")))
        return AtomicFile(File(context.noBackupFilesDir, "orbis-self-check/$owner.json"))
    }
    @Synchronized fun read(context: Context, owner: String): List<DiagnosticEvent> {
        val f = file(context, owner)
        if (!f.baseFile.exists()) return emptyList()
        check(f.baseFile.length() <= 8192)
        return json.decodeFromString<List<DiagnosticEvent>>(f.openRead().bufferedReader().use { it.readText() })
            .filter { it.code in diagnosticCodes && it.source in setOf("model", "tool", "theme", "stickers") }.takeLast(5)
    }
    @Synchronized fun record(context: Context, owner: String, source: String, code: String) {
        // Diagnostics must never change success/failure of the original operation.
        runCatching {
            require(source in setOf("model", "tool", "theme", "stickers") && code in diagnosticCodes)
            val items = (read(context, owner) + DiagnosticEvent(System.currentTimeMillis(), source, code)).takeLast(5)
            val f = file(context, owner)
            f.baseFile.parentFile!!.mkdirs()
            val out = f.startWrite()
            try { out.write(json.encodeToString(items).toByteArray()); f.finishWrite(out) }
            catch (e: Exception) { f.failWrite(out); throw e }
        }
    }
}
