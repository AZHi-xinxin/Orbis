package me.rerere.rikkahub.data.sync.importer

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.zip.ZipFile

/** Only chat database + upload files. Settings, credentials, skills and other data are never extracted. */
internal object RikkaChatArchive {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 20000
    internal const val MIN_FREE_BYTES = 64L * 1024 * 1024

    fun copyLimited(input: InputStream, output: OutputStream, limit: Long, checkCancelled: () -> Unit = {},
        beforeWrite: (Int) -> Unit = {}): Long {
        val buffer = ByteArray(65536)
        var copied = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) return copied
            copied += count
            require(copied <= limit) { "文件过大，已停止导入；原聊天不受影响" }
            beforeWrite(count)
            output.write(buffer, 0, count)
        }
    }

    fun extract(archive: File, destination: File, checkCancelled: () -> Unit = {},
        usableSpace: (File) -> Long = { it.usableSpace }): File {
        checkCancelled()
        require(archive.length() in 1..MAX_ARCHIVE_BYTES) { "备份为空或超过 512 MB" }
        require(destination.isDirectory) { "导入临时目录不存在" }
        val seen = mutableSetOf<String>()
        var total = 0L
        var count = 0
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                checkCancelled()
                require(++count <= MAX_ENTRIES) { "备份文件数量过多" }
                val name = entry.name
                require(!name.startsWith('/') && '\\' !in name && ':' !in name &&
                    name.split('/').none { it == ".." || it == "." }) { "备份包含不安全路径" }
                require(seen.add(name)) { "备份包含重复路径" }
                if (entry.isDirectory) continue
                val targetName = when {
                    name == "rikka_hub.db" -> "rikka_hub.db"
                    name == "rikka_hub-wal" || name == "rikka_hub.db-wal" -> "rikka_hub.db-wal"
                    name.startsWith("upload/") && name.count { it == '/' } == 1 -> name
                    else -> continue
                }
                val target = File(destination, targetName)
                requireExtractionSpace(usableSpace(destination), 0)
                require(!target.exists()) { "备份数据库边车文件冲突" }
                require(entry.size <= MAX_ENTRY_BYTES) { "备份单文件超过 256 MB" }
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        total += copyLimited(input, output, minOf(MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES - total),
                            checkCancelled, beforeWrite = { count -> requireExtractionSpace(usableSpace(destination), count) })
                    }
                }
            }
        }
        return File(destination, "rikka_hub.db").also {
            require(it.isFile && it.length() > 0) { "没有找到 RikkaHub 聊天数据库；请选择含聊天记录的 ZIP 备份" }
        }
    }

    internal fun requireExtractionSpace(available: Long, nextWrite: Int) {
        require(nextWrite >= 0 && available >= MIN_FREE_BYTES + nextWrite.toLong()) {
            "手机可用空间不足，导入已停止并预留 64 MB；原聊天未更改"
        }
    }

    /** Old app paths are untrusted: only a single filename under its upload directory can be remapped. */
    fun uploadName(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) return null
        val path = uri.path ?: return null
        val segments = path.split('/')
        if (segments.size < 2 || segments[segments.lastIndex - 1] != "upload") return null
        segments.last().takeIf { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it }
    }.getOrNull()
}
