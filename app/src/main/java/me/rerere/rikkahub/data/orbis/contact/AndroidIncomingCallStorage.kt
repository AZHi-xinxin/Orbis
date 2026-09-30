package me.rerere.rikkahub.data.orbis.contact

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream

internal class AndroidIncomingCallStorage(context: Context) : IncomingCallStorage {
    private val parent = context.filesDir.canonicalFile
    private val root = File(parent, "orbis-incoming-call-log")
    override val lockKey: String = root.absolutePath
    private fun file(id: String): AtomicFile {
        validateIncomingId(id)
        check(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val base = File(root, "$id.json")
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach {
            check(it.canonicalFile == it.absoluteFile)
        }
        return AtomicFile(base)
    }
    override fun ids(): List<String> {
        check(root.canonicalFile == root.absoluteFile)
        if (!root.exists()) return emptyList()
        return checkNotNull(root.listFiles()).mapNotNull {
            when {
                it.name.endsWith(".json.bak") -> it.name.removeSuffix(".json.bak")
                it.name.endsWith(".json") -> it.name.removeSuffix(".json")
                else -> null
            }
        }.distinct().onEach(::validateIncomingId)
    }
    override fun read(id: String): String? {
        val atomic = file(id)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 32 * 1024)
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
    }
    override fun write(id: String, value: String) {
        val atomic = file(id)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024)
        val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
