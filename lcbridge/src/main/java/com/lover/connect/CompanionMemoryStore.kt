package com.lover.connect

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** One existing on-device memory file, shared by native calls, MCP, the observer and UI. */
class CompanionMemoryStore(private val directory: File) {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private val lock = Any()
    }
    private val file get() = File(directory, "lc_memory.json")

    fun save(key: String, value: String): String = synchronized(lock) {
        require(key.isNotBlank() && value.isNotBlank()) { "invalid_memory_entry" }
        require(key.length <= 256 && value.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "memory_entry_too_large" }
        // A malformed/oversized existing file is not replaced with an empty one.
        val data = load()
        data.put(key.trim(), value.trim())
        replace(data.toString(2))
        "已记住：${key.trim()} = ${value.trim()}"
    }

    fun read(key: String = ""): String = synchronized(lock) {
        val data = load()
        val actualKey = key.trim()
        if (actualKey.isNotEmpty()) return@synchronized if (data.has(actualKey))
            "$actualKey = ${data.get(actualKey)}" else "没有找到：$actualKey"
        if (data.length() == 0) return@synchronized "记忆库为空"
        buildString {
            append("记忆库内容：\n")
            data.keys().forEach { append("- ").append(it).append("：").append(data.get(it)).append('\n') }
        }
    }

    fun exportJson(): ByteArray? = synchronized(lock) {
        if (!file.exists()) null else load().toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Explicit UI import retains its replace semantics; it is never automatic. */
    fun importJson(input: InputStream) {
        val bytes = input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                require(out.size() + size <= MAX_BYTES) { "memory_file_too_large" }
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }
        val data = JSONObject(bytes.toString(Charsets.UTF_8))
        synchronized(lock) { replace(data.toString(2)) }
    }

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        require(file.length() <= MAX_BYTES) { "memory_file_too_large" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun replace(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "memory_file_too_large" }
        check(directory.isDirectory || directory.mkdirs()) { "memory_directory_unavailable" }
        val temporary = Files.createTempFile(directory.toPath(), ".lc_memory-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            // Same-directory rename, no destructive fallback if atomic replacement is unsupported.
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
