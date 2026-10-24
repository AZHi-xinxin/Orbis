package me.rerere.rikkahub.data.orbis.body

import android.content.Context
import android.util.AtomicFile
import java.io.File
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import me.rerere.rikkahub.data.orbis.activities.bounded

/** noBackupFilesDir is excluded from Android backup. This namespace is absent from Orbis exports. */
object AndroidBodyDisplay {
    private val stores = mutableMapOf<String, BodyDisplayStore>()
    @Synchronized fun open(context: Context, owner: String): BodyDisplayStore {
        require(BodyRules.id(owner))
        val root = context.applicationContext.noBackupFilesDir.canonicalFile
        val target = File(root, "orbis-body-display-local/$owner.json")
        require(target.canonicalFile == target.absoluteFile)
        return stores.getOrPut(target.path) {
            val file = AtomicFile(target)
            BodyDisplayStore(object : ActivityStorage {
                override fun read(): String? = if (!file.baseFile.exists() && !File(target.path + ".bak").exists()) null
                    else file.openRead().use { it.bounded(4096).toString(Charsets.UTF_8) }
                override fun write(text: String) {
                    require(text.toByteArray().size <= 4096)
                    val output = file.startWrite()
                    try { output.write(text.toByteArray()); output.fd.sync(); file.finishWrite(output) }
                    catch (error: Exception) { file.failWrite(output); throw error }
                    check(read() == text) { "body_display_write_failed" }
                }
            })
        }
    }
}
