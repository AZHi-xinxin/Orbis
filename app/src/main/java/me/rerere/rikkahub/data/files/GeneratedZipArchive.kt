package me.rerere.rikkahub.data.files

import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates text artifacts only. Paths are ZIP entry names, never Android or workspace paths. */
object GeneratedZipArchive {
    const val MAX_ENTRIES = 128
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    data class TextFile(val path: String, val text: String)
    data class Result(val name: String, val bytes: ByteArray, val entryCount: Int)

    fun create(name: String, files: List<TextFile>, checkActive: () -> Unit = {}): Result {
        require(name.length in 5..128 && name.endsWith(".zip", ignoreCase = true) &&
            ZipAttachmentArchive.safePath(name) && '/' !in name && !name.endsWith(" ")) { "zip_invalid_name" }
        require(files.size in 1..MAX_ENTRIES) { "zip_entry_count_limit" }
        val names = HashSet<String>()
        var total = 0L
        val encoded = files.map { file ->
            checkActive()
            require(ZipAttachmentArchive.safePath(file.path) && !file.path.endsWith('/') &&
                ZipAttachmentArchive.isTextPath(file.path)) { "zip_invalid_text_path" }
            val normalized = Normalizer.normalize(file.path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            require(names.add(normalized)) { "zip_duplicate_path" }
            require(file.text.length <= MAX_ENTRY_BYTES && file.text.none { it.code < 32 && it !in "\n\r\t" }) {
                "zip_text_invalid_or_too_large"
            }
            // Reject unpaired surrogates instead of silently replacing the user's text on export.
            val bytes = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(file.text)).let {
                ByteArray(it.remaining()).also(it::get)
            }
            require(bytes.size <= MAX_ENTRY_BYTES) { "zip_entry_size_limit" }
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "zip_total_size_limit" }
            file.path to bytes
        }
        require(names.none { path -> path.split('/').dropLast(1).indices.any { index ->
            path.split('/').take(index + 1).joinToString("/") in names
        } }) { "zip_file_directory_conflict" }
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) { require(size() < MAX_ARCHIVE_BYTES); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(length <= MAX_ARCHIVE_BYTES - size()) { "zip_output_size_limit" }
                super.write(bytes, offset, length)
            }
        }
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            encoded.forEach { (path, bytes) ->
                checkActive()
                // STORED makes our own artifacts readable by the strict input ZIP ratio guard,
                // including repetitive source/text. No compression bomb or hidden extra payload.
                zip.putNextEntry(ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                    time = 0L
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        checkActive()
        return Result(name, output.toByteArray(), files.size)
    }
}
