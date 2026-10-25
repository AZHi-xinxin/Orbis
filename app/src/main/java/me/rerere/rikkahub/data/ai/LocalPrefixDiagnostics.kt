package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.providers.openai.PrefixObservation
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Debug-candidate evidence: no texts, keys, URLs, identifiers, or content hashes. No network. */
internal object LocalPrefixDiagnostics {
    @Synchronized fun record(context:Context,value:PrefixObservation) {
        val folder=File(context.noBackupFilesDir,"prefix-diagnostics");folder.mkdirs()
        val file=File(folder,"counts.jsonl");val tmp=File(folder,"counts.tmp")
        val old=if(file.isFile && file.length()<256*1024) file.readLines().takeLast(119) else emptyList()
        tmp.writeText((old+Json.encodeToString(value)).joinToString("\n",postfix="\n"))
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
}
