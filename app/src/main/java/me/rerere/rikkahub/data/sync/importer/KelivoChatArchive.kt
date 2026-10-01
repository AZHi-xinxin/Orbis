package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Only the portable SQLite snapshot is staged. Never extract settings, skills, media or ZIP paths. */
internal object KelivoChatArchive {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_DATABASE_BYTES = 256L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    private const val DATABASE_ENTRY = "database/kelivo.db"
    data class Snapshot(val file: File, val conversationCount: Int, val messageCount: Int)

    fun fingerprint(file: File, checkCancelled: () -> Unit = {}): String {
        require(file.isFile && file.length() in 1..MAX_ARCHIVE_BYTES) { "kelivo_archive_size" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            var total = 0L
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= MAX_ARCHIVE_BYTES) { "kelivo_archive_size" }
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }

    fun extract(file: File, destination: File, checkCancelled: () -> Unit = {},
        usableSpace: (File) -> Long = { it.usableSpace }): Snapshot {
        require(file.isFile && file.length() in 1..MAX_ARCHIVE_BYTES) { "kelivo_archive_size" }
        require(destination.isDirectory && destination.listFiles()?.isEmpty() == true) { "kelivo_private_staging" }
        return ZipFile(file).use { zip ->
            val paths = linkedMapOf<String, ZipEntry>()
            var expanded = 0L
            for (entry in zip.entries()) {
                checkCancelled()
                require(paths.size < 20000) { "kelivo_entry_count" }
                val canonical = safePath(entry.name, entry.isDirectory)
                require(paths.put(canonical, entry) == null) { "kelivo_path_collision" }
                require(entry.size in 0..MAX_DATABASE_BYTES && entry.compressedSize >= 0) { "kelivo_entry_size" }
                expanded += entry.size
                require(expanded <= MAX_EXPANDED_BYTES) { "kelivo_expanded_size" }
                require(entry.size <= maxOf(1024L * 1024, entry.compressedSize * 1000)) { "kelivo_compression_ratio" }
            }
            paths.keys.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(paths[parent]?.isDirectory != false) { "kelivo_path_collision" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            // Standalone export only: no WAL replay, normalization or source schema execution.
            require(paths.keys.none { it.startsWith("database/") && it != DATABASE_ENTRY &&
                (it.endsWith("-wal") || it.endsWith("-journal") || it.endsWith("-shm")) }) { "kelivo_database_sidecar" }
            val manifestEntry = paths["manifest.json"]?.takeIf { it.name == "manifest.json" && !it.isDirectory }
                ?: error("kelivo_manifest_missing")
            require(manifestEntry.size in 1..MAX_MANIFEST_BYTES) { "kelivo_manifest_size" }
            val bytes = java.io.ByteArrayOutputStream()
            copyEntry(zip, manifestEntry, bytes, MAX_MANIFEST_BYTES, checkCancelled)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val manifest = DeepSeekStrictJson.parse(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString(),
                checkCancelled) as? JsonObject ?: error("kelivo_manifest_object")
            require(manifest.text("format") == "kelivo-backup" && manifest.number("formatVersion") == 2L &&
                manifest.number("minimumReadableFormatVersion") in 1L..2L && manifest.text("payloadKind") == "sqlite" &&
                (manifest["includeChats"] as? JsonPrimitive)?.booleanOrNull == true) { "kelivo_format" }
            val database = manifest["database"] as? JsonObject ?: error("kelivo_database_manifest")
            require(database.text("entry") == DATABASE_ENTRY && database.number("schemaVersion") == 3L &&
                database.number("minimumReadableSchemaVersion") in 1L..3L) { "kelivo_schema_version" }
            val conversations = database.number("conversationCount")
            val messages = database.number("messageCount")
            require(conversations in 1L..10000L && messages in 0L..100000L) { "kelivo_row_count" }
            val checksum = (manifest["entries"] as? JsonObject)?.get(DATABASE_ENTRY) as? JsonObject
                ?: error("kelivo_database_checksum")
            val entry = paths[DATABASE_ENTRY]?.takeIf { it.name == DATABASE_ENTRY && !it.isDirectory }
                ?: error("kelivo_database_missing")
            require(entry.size in 16L..MAX_DATABASE_BYTES && checksum.number("bytes") == entry.size) { "kelivo_database_size" }
            val expected = checksum.text("sha256")
            require(expected.matches(Regex("[0-9a-fA-F]{64}"))) { "kelivo_database_checksum" }
            val target = File(destination, "kelivo-chat-snapshot.db")
            require(target.createNewFile()) { "kelivo_staging_collision" }
            try {
                val digest = target.outputStream().use { out ->
                    copyEntry(zip, entry, out, MAX_DATABASE_BYTES, checkCancelled) { count ->
                        RikkaChatArchive.requireExtractionSpace(usableSpace(destination), count)
                    }
                }
                require(digest.equals(expected, ignoreCase = true)) { "kelivo_database_checksum" }
                require(target.inputStream().use { input ->
                    val header = ByteArray(16)
                    input.read(header) == 16 && header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
                }) { "kelivo_database_header" }
                Snapshot(target, conversations.toInt(), messages.toInt())
            } catch (failure: Throwable) { target.delete(); throw failure }
        }
    }

    private fun safePath(name: String, directory: Boolean): String {
        require(name.isNotEmpty() && name.length <= 1024 && !name.startsWith('/') && '\\' !in name && ':' !in name &&
            name.none { it.code < 32 || it.code == 127 }) { "kelivo_unsafe_path" }
        val path = if (directory) name.removeSuffix("/") else name
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." &&
            !it.endsWith('.') && !it.endsWith(' ') }) { "kelivo_unsafe_path" }
        return Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, output: OutputStream, limit: Long,
        checkCancelled: () -> Unit, beforeWrite: (Int) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var total = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit && total <= entry.size) { "kelivo_inflated_size" }
                beforeWrite(count)
                crc.update(buffer, 0, count); digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        require(total == entry.size && crc.value == entry.crc) { "kelivo_zip_checksum" }
        return digest.digest().hex()
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("kelivo_manifest_string")
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        ?: error("kelivo_manifest_number")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
