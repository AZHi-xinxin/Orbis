package me.rerere.rikkahub.data.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ZipAttachmentException(val code: String) : IllegalArgumentException(code)

/** No extraction, execution, shell, network, or recursive archive handling. */
object ZipAttachmentArchive {
    const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
    const val MAX_ENTRIES = 1024
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_PAGE_ENTRIES = 40
    const val MAX_TEXT_CHARS = 8000
    private val zipMimes = setOf("application/zip", "application/x-zip", "application/x-zip-compressed")
    private val documentContainers = setOf("docx", "pptx", "xlsx", "epub", "odt", "ods", "odp")
    private val textExtensions = setOf("txt", "md", "markdown", "mdx", "csv", "tsv", "json", "jsonl", "xml",
        "html", "htm", "css", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "py", "rb", "lua",
        "sql", "java", "kt", "kts", "dart", "php", "swift", "go", "c", "h", "cpp", "cc", "hpp", "rs", "cs",
        "sh", "bash", "ps1", "bat", "cmd", "yml", "yaml", "toml", "ini", "env", "properties", "gradle",
        "log", "tex", "rst", "proto", "graphql")

    data class Entry(val path: String, val directory: Boolean, val bytes: Long, val compressedBytes: Long,
                     val crc: Long, val textSupported: Boolean)
    data class DirectoryPage(val entries: List<Entry>, val totalEntries: Int, val nextOffset: Int?)
    data class TextPage(val text: String, val totalChars: Int, val nextOffset: Int?, val bytes: Long)

    fun isZip(fileName: String, mime: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in documentContainers) return false
        return extension == "zip" || mime.substringBefore(';').trim().lowercase(Locale.ROOT) in zipMimes
    }

    /** A mislabelled ZIP must not reach the generic document text decoder. No entry is opened. */
    fun hasZipSignature(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(4)
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read < 0) break
            count += read
        }
        count == 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
            ((header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                (header[2] == 5.toByte() && header[3] == 6.toByte()) ||
                (header[2] == 7.toByte() && header[3] == 8.toByte()))
    }

    fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_ARCHIVE_BYTES - copied + 1).toInt())
            if (count < 0) break
            copied += count
            checked(copied <= MAX_ARCHIVE_BYTES, "zip_archive_too_large")
            output.write(buffer, 0, count)
        }
        checked(copied >= 22, "zip_invalid_archive")
    }

    fun directory(file: File, offset: Int = 0, limit: Int = MAX_PAGE_ENTRIES): DirectoryPage {
        checked(offset >= 0 && limit in 1..MAX_PAGE_ENTRIES, "zip_invalid_page")
        val entries = inspect(file)
        checked(offset <= entries.size, "zip_invalid_page")
        val end = minOf(entries.size, offset + limit)
        return DirectoryPage(entries.subList(offset, end), entries.size, end.takeIf { it < entries.size })
    }

    fun readText(file: File, path: String, offset: Int = 0, limit: Int = 4000): TextPage {
        checked(offset >= 0 && limit in 1..MAX_TEXT_CHARS, "zip_invalid_page")
        val selected = inspect(file).singleOrNull { it.path == path }
            ?: throw ZipAttachmentException("zip_entry_not_found")
        checked(!selected.directory && selected.textSupported, "zip_entry_not_utf8_text_type")
        val bytes = ZipFile(file, Charsets.UTF_8).use { archive ->
            val entry = archive.getEntry(path) ?: throw ZipAttachmentException("zip_entry_not_found")
            checked(entry.size == selected.bytes && entry.crc == selected.crc, "zip_archive_changed")
            archive.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                val crc = CRC32()
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_ENTRY_BYTES - out.size() + 1).toInt())
                    if (count < 0) break
                    checked(out.size().toLong() + count <= MAX_ENTRY_BYTES && out.size().toLong() + count <= selected.bytes,
                        "zip_expansion_limit")
                    out.write(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
                checked(out.size().toLong() == selected.bytes && crc.value == selected.crc, "zip_crc_or_size_mismatch")
                out.toByteArray()
            }
        }
        val text = decodeUtf8(bytes, "zip_text_encoding_unsupported").removePrefix("\uFEFF")
        checked(text.none { it.code < 32 && it !in "\n\r\t" }, "zip_entry_binary_not_supported")
        checked(offset <= text.length, "zip_invalid_page")
        checked(offset == 0 || offset == text.length || !text[offset].isLowSurrogate(), "zip_invalid_page")
        var end = minOf(text.length, offset + limit)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
        checked(end > offset || offset == text.length, "zip_page_splits_unicode")
        return TextPage(text.substring(offset, end), text.length, end.takeIf { it < text.length }, selected.bytes)
    }

    /** Validate the complete bounded central directory, including Unix link attributes.
     * Names must be UTF-8 (ASCII is a subset); legacy unknown encodings fail explicitly.
     * Java ZipFile alone does not expose link attributes or enforce CRC during reads.
     */
    fun inspect(file: File): List<Entry> {
        checked(file.isFile && file.length() in 22..MAX_ARCHIVE_BYTES, "zip_archive_size_or_type")
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val tailSize = minOf(length, 65_557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(length - tailSize); input.readFully(tail)
            val end = (tailSize - 22 downTo 0).firstOrNull {
                u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tailSize
            } ?: throw ZipAttachmentException("zip_invalid_end_record")
            checked(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
                u16(tail, end + 8) == u16(tail, end + 10), "zip_multidisk_not_supported")
            val count = u16(tail, end + 10)
            checked(count <= MAX_ENTRIES, "zip_too_many_entries")
            val centralBytes = u32(tail, end + 12)
            val centralOffset = u32(tail, end + 16)
            val endOffset = length - tailSize + end
            checked(centralOffset + centralBytes == endOffset && centralOffset <= length, "zip_invalid_central_directory")
            input.seek(centralOffset)
            val names = HashSet<String>()
            val ranges = ArrayList<LongRange>()
            var totalBytes = 0L
            val entries = ArrayList<Entry>()
            repeat(count) {
                val header = ByteArray(46)
                checked(input.filePointer + 46 <= endOffset, "zip_invalid_central_directory")
                input.readFully(header)
                checked(u32(header, 0) == 0x02014b50L, "zip_invalid_central_directory")
                val flags = u16(header, 8)
                val method = u16(header, 10)
                checked(flags and 1 == 0 && flags and 64 == 0, "zip_encrypted_not_supported")
                checked(method == 0 || method == 8, "zip_compression_not_supported")
                val size = u32(header, 24)
                val compressed = u32(header, 20)
                val nameSize = u16(header, 28)
                val extraSize = u16(header, 30)
                val commentSize = u16(header, 32)
                checked(nameSize in 1..2048 && input.filePointer + nameSize + extraSize + commentSize <= endOffset,
                    "zip_invalid_entry_header")
                val nameBytes = ByteArray(nameSize); input.readFully(nameBytes)
                val name = decodeUtf8(nameBytes, "zip_filename_encoding_unsupported")
                checked(safePath(name), "zip_unsafe_entry_path")
                checked(names.add(Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)), "zip_duplicate_entry")
                val directory = name.endsWith('/')
                val unixType = ((u32(header, 38) ushr 16).toInt() and 0xf000)
                checked(unixType in setOf(0, 0x8000, 0x4000) && (unixType != 0x4000 || directory), "zip_link_or_special_entry")
                checked(u16(header, 34) == 0, "zip_multidisk_not_supported")
                checked(size <= MAX_ENTRY_BYTES && compressed <= MAX_ARCHIVE_BYTES, "zip_expansion_limit")
                checked(size == 0L || compressed > 0, "zip_invalid_entry_size")
                checked(size <= 1024 * 1024 || size <= compressed * 1000L, "zip_compression_ratio_limit")
                if (directory) checked(size == 0L, "zip_invalid_directory")
                totalBytes += size
                checked(totalBytes <= MAX_TOTAL_BYTES, "zip_total_expansion_limit")
                val returnPosition = input.filePointer + extraSize + commentSize
                val localOffset = u32(header, 42)
                checked(localOffset + 30 <= centralOffset, "zip_invalid_local_header")
                input.seek(localOffset)
                val local = ByteArray(30); input.readFully(local)
                checked(u32(local, 0) == 0x04034b50L && u16(local, 6) == flags && u16(local, 8) == method,
                    "zip_inconsistent_local_header")
                checked(u16(local, 26) == nameSize, "zip_inconsistent_local_header")
                val localName = ByteArray(nameSize); input.readFully(localName)
                checked(localName.contentEquals(nameBytes), "zip_inconsistent_local_header")
                val dataEnd = localOffset + 30 + nameSize + u16(local, 28) + compressed
                checked(dataEnd <= centralOffset, "zip_invalid_entry_size")
                if (flags and 8 == 0) checked(u32(local, 14) == u32(header, 16) &&
                    u32(local, 18) == compressed && u32(local, 22) == size, "zip_inconsistent_local_header")
                val range = localOffset until dataEnd
                checked(ranges.none { it.first <= range.last && range.first <= it.last }, "zip_overlapping_entries")
                ranges.add(range)
                input.seek(returnPosition)
                entries.add(Entry(name, directory, size, compressed, u32(header, 16), !directory && isTextPath(name)))
            }
            checked(input.filePointer == endOffset, "zip_invalid_central_directory")
            // Force the platform ZIP reader to validate its own directory view without decompressing entries.
            ZipFile(file, Charsets.UTF_8).use { checked(it.size() == count, "zip_inconsistent_directory") }
            entries
        }
    }

    fun safePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 512 || path.startsWith('/') || '\\' in path || ':' in path ||
            path.any { it.code < 32 || it.code == 127 }) return false
        return path.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." &&
            !it.endsWith('.') && !it.endsWith(' ') }
    }

    fun isTextPath(path: String): Boolean = path.substringAfterLast('.').lowercase(Locale.ROOT) in textExtensions ||
        path.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("readme", "license", "licence", "makefile", "dockerfile")

    private fun checked(value: Boolean, code: String) { if (!value) throw ZipAttachmentException(code) }
    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    private fun decodeUtf8(bytes: ByteArray, code: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw ZipAttachmentException(code) }
}
