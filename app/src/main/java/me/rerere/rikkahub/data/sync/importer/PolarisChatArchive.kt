package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads only the manifest and chat store. Other stores, assets and ZIP paths are never extracted. */
object PolarisChatArchive {
    const val MAX_ARCHIVE_BYTES = ArchiveCapacity.MAX_ZIP_BYTES
    private const val MAX_ENTRY_BYTES = ArchiveCapacity.MAX_DISK_ENTRY_BYTES
    private const val MAX_EXPANDED_BYTES = ArchiveCapacity.MAX_EXPANDED_BYTES
    private const val MAX_MANIFEST_BYTES = 1024L * 1024
    private const val MAX_CHAT_BYTES = ArchiveCapacity.MAX_STREAM_JSON_BYTES
    private const val CHAT_ENTRY = "stores/chat.json"

    fun fingerprint(file: File, checkCancelled: () -> Unit = {}): String {
        require(file.isFile) { "polaris_missing_file" }
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                checkCancelled()
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ARCHIVE_BYTES) { "polaris_archive_size" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun open(file: File, checkCancelled: () -> Unit = {}): ArchiveReader {
        require(file.isFile) { "polaris_missing_file" }
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        val zip = ZipFile(file)
        try {
            val paths = linkedMapOf<String, ZipEntry>()
            var expanded = 0L
            for (entry in zip.entries()) {
                checkCancelled()
                require(paths.size < ArchiveCapacity.MAX_ENTRIES) { "polaris_entry_count" }
                val path = safePath(entry.name, entry.isDirectory)
                if (paths.put(path, entry) != null) throw ArchiveReadException(ArchiveFailure.UNSAFE_PATH)
                ArchiveCapacity.requireSize(entry.size, MAX_ENTRY_BYTES, allowEmpty = true)
                require(entry.compressedSize >= 0) { "polaris_entry_size" }
                expanded += entry.size
                ArchiveCapacity.requireSize(expanded, MAX_EXPANDED_BYTES, allowEmpty = true)
                if (path == "manifest.json" || path == CHAT_ENTRY) {
                    require(entry.size <= maxOf(1024L * 1024, entry.compressedSize * 1000)) {
                        "polaris_compression_ratio"
                    }
                }
            }
            paths.keys.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(paths[parent]?.isDirectory != false) { "polaris_path_collision" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            val manifestEntry = paths["manifest.json"]?.takeIf { it.name == "manifest.json" && !it.isDirectory }
                ?: error("polaris_manifest_missing")
            val manifestBytes = readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES, checkCancelled)
            val strictUtf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val manifest = DeepSeekStrictJson.parse(strictUtf8.decode(ByteBuffer.wrap(manifestBytes)).toString(),
                checkCancelled) as? JsonObject ?: error("polaris_manifest_object")
            require(manifest.string("format") == "polaris-export" &&
                manifest.number("version") == 1) { "polaris_format" }
            val stores = manifest["stores"] as? JsonObject ?: error("polaris_stores")
            require(stores.string("chat") == CHAT_ENTRY) { "polaris_chat_store" }
            val chat = paths[CHAT_ENTRY]?.takeIf { it.name == CHAT_ENTRY && !it.isDirectory }
                ?: error("polaris_chat_missing")
            ArchiveCapacity.requireSize(chat.size, MAX_CHAT_BYTES)
            val stream = CountingCrcStream(zip.getInputStream(chat), chat.size, checkCancelled)
            val reader = InputStreamReader(stream, Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
            return ArchiveReader(zip, reader, stream, chat, checkCancelled)
        } catch (failure: Throwable) {
            zip.close()
            throw failure
        }
    }

    internal class ArchiveReader(
        private val zip: ZipFile, private val reader: Reader, private val stream: CountingCrcStream,
        private val chat: ZipEntry, private val checkCancelled: () -> Unit,
    ) : Closeable {
        private var consumed = false
        var skippedNonDirect = 0
            private set
        fun conversations(): Sequence<PolarisConversation> = sequence {
            check(!consumed); consumed = true
            val parser = StoreReader(reader, checkCancelled)
            var total = 0
            val seen = hashSetOf<String>()
            for (raw in parser.conversations()) {
                checkCancelled()
                require(++total <= PolarisChatLimits.MAX_CONVERSATIONS) { "polaris_conversation_count" }
                val kind = (raw["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("polaris_conversation_kind")
                if (kind != "direct") {
                    skippedNonDirect++
                    continue
                }
                val conversation = parsePolarisConversation(raw)
                require(seen.add(conversation.id)) { "polaris_duplicate_conversation" }
                yield(conversation)
            }
            stream.verify(chat)
        }
        override fun close() {
            try { reader.close() } finally { zip.close() }
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long, checkCancelled: () -> Unit): ByteArray {
        require(entry.size in 1..limit) { "polaris_entry_size" }
        val stream = CountingCrcStream(zip.getInputStream(entry), entry.size, checkCancelled)
        return stream.use {
            val output = ByteArrayOutputStream(entry.size.toInt())
            val buffer = ByteArray(32768)
            while (true) {
                checkCancelled()
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            stream.verify(entry)
            output.toByteArray()
        }
    }

    private fun safePath(name: String, directory: Boolean): String {
        ArchiveCapacity.requireSafePath(name, directory)
        require(name.isNotEmpty() && name.length <= 1024 && !name.startsWith('/') && '\\' !in name &&
            ':' !in name && name.none { it.code < 32 || it.code == 127 }) { "polaris_unsafe_path" }
        val path = if (directory) name.removeSuffix("/") else name
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." &&
            !it.endsWith('.') && !it.endsWith(' ') }) { "polaris_unsafe_path" }
        return Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("polaris_manifest_string")
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: error("polaris_manifest_number")

    internal class CountingCrcStream(input: java.io.InputStream, private val limit: Long,
        private val checkCancelled: () -> Unit) : FilterInputStream(input) {
        private val checksum = CRC32()
        private var total = 0L
        override fun read(): Int {
            checkCancelled()
            return super.read().also { if (it >= 0) account(byteArrayOf(it.toByte()), 0, 1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return `in`.read(buffer, offset, length).also { if (it > 0) account(buffer, offset, it) }
        }
        private fun account(buffer: ByteArray, offset: Int, count: Int) {
            total += count
            if (total > limit) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
            checksum.update(buffer, offset, count)
        }
        fun verify(entry: ZipEntry) {
            if (total != entry.size || checksum.value != entry.crc) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
        }
    }
}

/** Streams one conversation at a time; no 21 MB chat-store DOM or other store is retained. */
private class StoreReader(private val reader: Reader, private val checkCancelled: () -> Unit) {
    private var pending = -2
    private var offset = 0L
    private fun peek(): Int { if (pending == -2) pending = reader.read(); return pending }
    private fun next(): Char {
        if (offset and 4095L == 0L) checkCancelled()
        require(peek() >= 0) { "polaris_json_truncated" }
        ArchiveCapacity.requireSize(++offset, ArchiveCapacity.MAX_STREAM_JSON_BYTES)
        return pending.toChar().also { pending = -2 }
    }
    private fun ws() { while (peek() >= 0 && peek().toChar() in " \r\n\t") next() }
    private fun expect(char: Char) { ws(); require(next() == char) { "polaris_json_structure" } }
    private fun value(limit: Int): String {
        ws()
        val raw = StringBuilder()
        fun add(c: Char) { if (raw.length >= limit) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT); raw.append(c) }
        when (peek().toChar()) {
            '{', '[' -> {
                val brackets = ArrayDeque<Char>()
                var quoted = false; var escaped = false
                do {
                    val c = next(); add(c)
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') quoted = false
                    } else when (c) {
                        '"' -> quoted = true
                        '{', '[' -> brackets.addLast(c)
                        '}' -> require(brackets.removeLastOrNull() == '{') { "polaris_json_structure" }
                        ']' -> require(brackets.removeLastOrNull() == '[') { "polaris_json_structure" }
                    }
                } while (brackets.isNotEmpty())
            }
            '"' -> {
                add(next())
                var escaped = false
                while (true) {
                    val c = next(); add(c)
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') break
                }
            }
            else -> {
                while (peek() >= 0 && peek().toChar() !in ",}] \r\n\t") add(next())
            }
        }
        return raw.toString()
    }
    private fun key(): String {
        val raw = value(1024)
        val parsed = DeepSeekStrictJson.parse(raw, checkCancelled) as? JsonPrimitive
        require(parsed?.isString == true) { "polaris_json_key" }
        return parsed.content
    }
    fun conversations(): Sequence<JsonObject> = sequence {
        expect('{')
        val seen = hashSetOf<String>()
        while (true) {
            ws(); if (peek() == '}'.code) { next(); break }
            val name = key()
            require(seen.add(name)) { "polaris_duplicate_root_key" }
            expect(':')
            if (name == "conversations") {
                expect('[')
                while (true) {
                    ws(); if (peek() == ']'.code) { next(); break }
                    val item = DeepSeekStrictJson.parse(value(ArchiveCapacity.MAX_WINDOW_CHARS), checkCancelled)
                        as? JsonObject ?: error("polaris_conversation_object")
                    yield(item)
                    ws(); val separator = next()
                    if (separator == ']') break
                    require(separator == ',') { "polaris_json_separator" }
                }
            } else {
                // All other root fields are validated but discarded, including deleted IDs and group-room state.
                DeepSeekStrictJson.parse(value(4 * 1024 * 1024), checkCancelled)
            }
            ws(); val separator = next()
            if (separator == '}') break
            require(separator == ',') { "polaris_json_separator" }
        }
        require("conversations" in seen) { "polaris_conversations_missing" }
        ws(); require(peek() == -1) { "polaris_json_trailing" }
    }
}
