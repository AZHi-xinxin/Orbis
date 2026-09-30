package me.rerere.rikkahub.data.orbis

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Local storage limits, independent of the small per-call AI reading excerpt budget. */
object GardenBookImport {
    const val MAX_BOOK_BYTES = 32 * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 64 * 1024 * 1024

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= MAX_BOOK_BYTES) { "book_too_large" }
            bytes.write(buffer, 0, count)
        }
        return decode(bytes.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BOOK_BYTES) { "book_too_large" }
        require(bytes.isNotEmpty()) { "book_empty" }
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
            prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
        fun strict(charset: Charset, skip: Int = 0): String = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        val text = try {
            when {
                starts(0xff, 0xfe, 0, 0) || starts(0, 0, 0xfe, 0xff) -> error("book_encoding")
                starts(0xff, 0xfe) -> strict(Charsets.UTF_16LE, 2)
                starts(0xfe, 0xff) -> strict(Charsets.UTF_16BE, 2)
                starts(0xef, 0xbb, 0xbf) -> strict(Charsets.UTF_8, 3)
                else -> runCatching { strict(Charsets.UTF_8) }
                    .getOrElse { strict(Charset.forName("GB18030")) }
            }
        } catch (_: Exception) { throw IllegalArgumentException("book_encoding") }
        require(text.isNotBlank()) { "book_empty" }
        require(text.none { it.isISOControl() && it !in "\t\n\r\u000c" }) { "book_binary" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BOOK_BYTES) { "book_decoded_too_large" }
        return text
    }

    fun errorMessage(failure: Exception): String = when (failure.message) {
        "book_too_large", "book_decoded_too_large" -> "单本书最多 32 MiB（转换为 UTF-8 后也需在此范围内）。原书库未改动。"
        "book_empty" -> "这份文件没有可读正文，原书库未改动。"
        "book_encoding", "book_binary" -> "无法识别书籍文字。支持 UTF-8、GBK／GB18030，以及带 BOM 的 UTF-16 TXT／Markdown；请检查编码或文件格式。原书库未改动。"
        "book_extension" -> "请选择 TXT 或 Markdown 文字书籍。原书库未改动。"
        else -> "书籍未能读取，可能没有文件读取权限或文件已移动。请重新选择；原书库未改动。"
    }
}
