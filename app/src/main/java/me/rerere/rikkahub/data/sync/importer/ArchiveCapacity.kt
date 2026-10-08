package me.rerere.rikkahub.data.sync.importer

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class ArchiveFailure { SIZE_LIMIT, WINDOW_LIMIT, NODE_LIMIT, INSUFFICIENT_SPACE, INVALID_UTF8, CHECKSUM, UNSAFE_PATH, FORMAT, READ_WRITE }

/** Public diagnostics never contain filenames, source text, account fields or parser excerpts. */
class ArchiveReadException(val reason: ArchiveFailure, val limitBytes: Long? = null) :
    IllegalArgumentException(ArchiveCapacity.message(reason, limitBytes))

/** Byte budgets for disk streaming are intentionally separate from in-memory JSON/window budgets. */
object ArchiveCapacity {
    const val MIB = 1024L * 1024
    const val GIB = 1024L * MIB
    const val MAX_ZIP_BYTES = 8L * GIB
    const val MAX_EXPANDED_BYTES = 16L * GIB
    const val MAX_DISK_ENTRY_BYTES = 8L * GIB
    const val MAX_STREAM_JSON_BYTES = GIB
    const val MAX_WINDOW_CHARS = 64 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    const val MIN_FREE_BYTES = 64L * MIB

    fun requireSize(size: Long, limit: Long, allowEmpty: Boolean = false) {
        if (size < if (allowEmpty) 0L else 1L) throw ArchiveReadException(ArchiveFailure.FORMAT)
        if (size > limit) throw ArchiveReadException(ArchiveFailure.SIZE_LIMIT, limit)
    }

    fun requireSpace(available: Long, pendingBytes: Long = 0) {
        require(pendingBytes >= 0)
        if (available < MIN_FREE_BYTES || pendingBytes > available - MIN_FREE_BYTES)
            throw ArchiveReadException(ArchiveFailure.INSUFFICIENT_SPACE)
    }

    fun requireSafePath(name: String, directory: Boolean = false) {
        val path = if (directory) name.removeSuffix("/") else name
        if (path.isBlank() || path.length > 1024 || path.startsWith('/') || '\\' in path || ':' in path ||
            path.any { it.code < 32 || it.code == 127 } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." })
            throw ArchiveReadException(ArchiveFailure.UNSAFE_PATH)
    }

    fun copyZipEntry(zip: ZipFile, entry: ZipEntry, output: OutputStream, limit: Long,
        checkCancelled: () -> Unit = {}, beforeWrite: (Int) -> Unit = {}): Long {
        requireSize(entry.size, limit, allowEmpty = true)
        val crc = CRC32()
        var copied = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                requireSize(copied, limit, allowEmpty = true)
                if (copied > entry.size) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
                beforeWrite(count)
                crc.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        if (copied != entry.size || crc.value != entry.crc) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
        return copied
    }

    fun reasonOf(failure: Throwable): ArchiveFailure? = when (failure) {
        is ArchiveReadException -> failure.reason
        is DeepSeekUnsupportedFragmentException -> ArchiveFailure.FORMAT
        is CharacterCodingException -> ArchiveFailure.INVALID_UTF8
        is ZipException -> ArchiveFailure.CHECKSUM
        is IOException -> ArchiveFailure.READ_WRITE
        else -> null
    }

    fun publicError(failure: Throwable): String = when (failure) {
        is RikkaChatReadException -> failure.message!!
        is ArchiveReadException -> failure.message!!
        is DeepSeekUnsupportedFragmentException -> failure.message!!
        is CharacterCodingException -> message(ArchiveFailure.INVALID_UTF8)
        is ZipException -> message(ArchiveFailure.CHECKSUM)
        is IOException -> message(ArchiveFailure.READ_WRITE)
        else -> message(ArchiveFailure.FORMAT)
    }

    internal fun message(reason: ArchiveFailure, limit: Long? = null): String = when (reason) {
        ArchiveFailure.SIZE_LIMIT -> "超过安全处理上限" + (limit?.let { "（${it / MIB} MiB）" } ?: "") + "；请保留原文件，按会话或附件拆分后重试。没有截断原文。"
        ArchiveFailure.WINDOW_LIMIT -> "单个会话的文字或结构超过内存保护上限；已停止读取，没有截断原文，请按会话分段导出。"
        ArchiveFailure.NODE_LIMIT -> "单条消息超过安全读取大小；该会话未导入，没有截断原文，请保留原文件。"
        ArchiveFailure.INSUFFICIENT_SPACE -> "本机可用空间不足，已停止处理并预留 64 MiB；请释放空间后用原文件重试。"
        ArchiveFailure.INVALID_UTF8 -> "文件文字编码不是完整的 UTF-8；请保留原文件并从来源重新导出。"
        ArchiveFailure.CHECKSUM -> "压缩包完整性校验失败，可能下载不完整或文件损坏；请重新复制或导出原文件。"
        ArchiveFailure.UNSAFE_PATH -> "压缩包包含不安全或冲突的路径；已拒绝读取，请保留原文件。"
        ArchiveFailure.FORMAT -> "导出格式或聊天结构暂不兼容；请保留原文件以便适配。"
        ArchiveFailure.READ_WRITE -> "文件读写失败，可能已失去文件访问权限或存储不可用；请重新选择文件并检查存储。"
    }
}
