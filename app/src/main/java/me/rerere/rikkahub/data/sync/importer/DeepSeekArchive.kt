package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class DeepSeekBranchPreview(val leafId: String, val messageCount: Int, val updatedAt: Instant, val isDefault: Boolean)
data class DeepSeekConversationPreview(
    val sourceId: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val totalNodes: Int, val messageCount: Int, val branchPointCount: Int,
    val branches: List<DeepSeekBranchPreview>, val defaultLeafId: String, val defaultSelectionReason: String,
    val omittedSummaryCount: Int = 0,
)
data class DeepSeekArchivePreview(
    val conversations: List<DeepSeekConversationPreview>,
    val warnings: List<String> = emptyList(),
)

/** Local-only, bounded streaming reader. Never extracts paths or reads user.json contents. */
object DeepSeekArchive {
    const val MAX_JSON_BYTES = Long.MAX_VALUE
    const val MAX_ARCHIVE_BYTES = Long.MAX_VALUE
    internal const val MAX_STRING_CHARS = 4 * 1024 * 1024

    fun inspect(file: File, checkCancelled: () -> Unit = {}): DeepSeekArchivePreview = safeRead {
        open(file, checkCancelled).use { archive ->
            DeepSeekArchivePreview(archive.conversations().map { conversation ->
                checkCancelled()
                // BFS parser order makes this O(nodes), not O(leaves * history).
                val depths = mutableMapOf<String, Int>()
                conversation.nodes.values.forEach { node ->
                    depths[node.sourceId] = (depths[node.parentId] ?: 0) + if (node.message == null) 0 else 1
                }
                DeepSeekConversationPreview(conversation.sourceId, conversation.title, conversation.createdAt,
                    conversation.updatedAt, conversation.nodes.size, depths.size - 1,
                    conversation.nodes.values.count { it.children.size > 1 },
                    conversation.nodes.values.filter { it.children.isEmpty() || it.sourceId == conversation.selectedLeafId }
                        .map { DeepSeekBranchPreview(it.sourceId, depths.getValue(it.sourceId),
                            it.message?.createdAt ?: conversation.createdAt, it.sourceId == conversation.selectedLeafId) },
                    conversation.selectedLeafId,
                    if (conversation.original["current_node"] != null && conversation.original["current_node"] != JsonNull)
                        "source_current_node" else "newest_leaf")
            }.toList())
        }
    }

    internal fun open(file: File, checkCancelled: () -> Unit): ArchiveReader {
        checkCancelled()
        require(file.isFile) { "deepseek_missing_file" }
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        return safeRead {
            val zip = ZipFile(file)
            try {
                if (zip.entries().asSequence().any { it.name.substringAfterLast('/') == "chat_session_index.json" })
                    DeepSeekV3Archive(zip, checkCancelled) else LegacyArchiveReader(zip, checkCancelled)
            } catch (failure: Throwable) { zip.close(); throw failure }
        }
    }

    internal interface ArchiveReader : Closeable {
        fun conversations(): Sequence<DeepSeekConversation>
        fun copyAttachment(path: String, output: java.io.OutputStream): Boolean = false
    }

    private class LegacyArchiveReader(private val zip: ZipFile, private val checkCancelled: () -> Unit) : ArchiveReader {
        private val reader: Reader
        private val crc = CRC32()
        private var bytes = 0L
        private val expectedSize: Long
        private val expectedCrc: Long
        private var consumed = false
        init {
            try {
                val entries = mutableListOf<java.util.zip.ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    checkCancelled()
                    entries += enumeration.nextElement()
                }
                val entry = selectConversationEntry(entries)
                ArchiveCapacity.requireSize(entry.size, MAX_JSON_BYTES)
                expectedSize = entry.size
                expectedCrc = entry.crc
                val stream = object : FilterInputStream(zip.getInputStream(entry)) {
                    private fun account(buffer: ByteArray, offset: Int, count: Int) {
                        if (count <= 0) return
                        bytes += count
                        ArchiveCapacity.requireSize(bytes, MAX_JSON_BYTES)
                        if (bytes > expectedSize) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
                        crc.update(buffer, offset, count)
                    }
                    override fun read(): Int {
                        checkCancelled()
                        val result = `in`.read()
                        if (result >= 0) account(byteArrayOf(result.toByte()), 0, 1)
                        return result
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        checkCancelled()
                        return `in`.read(buffer, offset, length).also { account(buffer, offset, it) }
                    }
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                reader = InputStreamReader(stream, decoder).buffered(32 * 1024)
            } catch (failure: Throwable) { zip.close(); throw failure }
        }
        override fun conversations(): Sequence<DeepSeekConversation> = sequence {
            check(!consumed) { "deepseek_already_read" }; consumed = true
            val identities = hashSetOf<String>()
            for (raw in DeepSeekStrictJson.arrayItems(reader, checkCancelled, StreamingJsonBudget.deepSeek())) {
                val conversation = DeepSeekParser.parse(JsonArray(listOf(raw)), checkCancelled).conversations.single()
                require(identities.add(conversation.sourceId)) { "deepseek_duplicate_or_many_conversations" }
                yield(conversation)
            }
            require(identities.isNotEmpty()) { "deepseek_empty" }
            if (bytes != expectedSize || crc.value != expectedCrc) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
        }
        override fun close() { try { reader.close() } finally { zip.close() } }
    }

    /** A re-zipped export may have one enclosing folder and Finder metadata. Nothing is
     * extracted; select exactly one data root, never merge multiple exports or read a profile. */
    private fun selectConversationEntry(entries: List<java.util.zip.ZipEntry>): java.util.zip.ZipEntry {
        require(entries.isNotEmpty() && entries.map { it.name }.toSet().size == entries.size) { "deepseek_zip_paths" }
        entries.forEach { entry ->
            ArchiveCapacity.requireSafePath(entry.name, entry.isDirectory)
            if (entry.isDirectory) require(entry.size == 0L) { "deepseek_zip_directory" }
        }
        val entry = entries.single { !it.isDirectory && it.name.substringAfterLast('/') == "conversations.json" &&
            !it.name.startsWith("__MACOSX/") }
        // Other safe entries may be newer export metadata or local attachments. Never execute
        // or extract them, and never parse account/settings files as conversations.
        return entry
    }

    internal inline fun <T> safeRead(block: () -> T): T = try { block() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: ArchiveReadException) { throw failure }
    catch (failure: DeepSeekUnsupportedFragmentException) { throw failure }
    catch (_: java.nio.charset.CharacterCodingException) { throw ArchiveReadException(ArchiveFailure.INVALID_UTF8) }
    catch (_: java.util.zip.ZipException) { throw ArchiveReadException(ArchiveFailure.CHECKSUM) }
    catch (_: java.io.IOException) { throw ArchiveReadException(ArchiveFailure.READ_WRITE) }
    catch (_: Exception) { throw IllegalArgumentException("DeepSeek 导出包格式不兼容或已损坏；未覆盖现有聊天") }
}

/** Duplicate-key rejecting JSON reader, materializing only one conversation from the outer array. */
internal data class StreamingJsonBudget(
    val totalChars: Long = ArchiveCapacity.MAX_STREAM_JSON_BYTES,
    val itemChars: Int = ArchiveCapacity.MAX_WINDOW_CHARS,
    val itemValues: Int = 2_000_000,
    val stringChars: Int = DeepSeekArchive.MAX_STRING_CHARS,
) {
    init {
        require(totalChars > 0)
        require(itemChars > 0 && itemValues > 0 && stringChars > 0)
    }
    companion object {
        // No product-level count/size quotas for official chat imports. Each conversation
        // is consumed separately; filesystem capacity and valid JSON still apply.
        fun deepSeek() = StreamingJsonBudget(Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
    }
}

internal object DeepSeekStrictJson {
    fun parse(text: String, checkCancelled: () -> Unit = {}): JsonElement {
        ArchiveCapacity.requireSize(text.length.toLong(), ArchiveCapacity.MAX_WINDOW_CHARS.toLong(), allowEmpty = true)
        return JsonReader(StringReader(text), checkCancelled).read()
    }
    fun read(reader: Reader, checkCancelled: () -> Unit, budget: StreamingJsonBudget): JsonElement =
        JsonReader(reader, checkCancelled, budget).read()
    fun arrayItems(reader: Reader, checkCancelled: () -> Unit,
        budget: StreamingJsonBudget = StreamingJsonBudget()): Sequence<JsonElement> = JsonReader(reader, checkCancelled, budget).arrayItems()
    /** Streams a named array inside a small, explicitly allowed object envelope. Header order is not significant. */
    fun objectArrayItems(reader: Reader, arrayKey: String, headerKeys: Set<String>,
        checkCancelled: () -> Unit, allowExtraMetadata: Boolean = false,
        validateHeader: (JsonObject) -> Unit): Sequence<JsonElement> =
        JsonReader(reader, checkCancelled).objectArrayItems(arrayKey, headerKeys, allowExtraMetadata, validateHeader)

    private class JsonReader(private val source: Reader, private val checkCancelled: () -> Unit,
        private val budget: StreamingJsonBudget = StreamingJsonBudget()) {
        private var pending = -2
        private var offset = 0L
        private var values = 0
        private var itemStart: Long? = 0L
        private fun peek(): Int { if (pending == -2) pending = source.read(); return pending }
        private fun next(): Char {
            if (offset and 4095L == 0L) checkCancelled()
            require(peek() >= 0) { "deepseek_json_truncated" }
            if (++offset > budget.totalChars)
                throw ArchiveReadException(ArchiveFailure.SIZE_LIMIT, ArchiveCapacity.MAX_STREAM_JSON_BYTES)
            if (itemStart?.let { offset - it > budget.itemChars } == true)
                throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
            return pending.toChar().also { pending = -2 }
        }
        private fun whitespace() { while (peek() >= 0 && peek().toChar() in " \r\n\t") next() }
        fun read(): JsonElement { val result = value(0); whitespace(); require(peek() == -1) { "deepseek_json_trailing" }; return result }
        fun arrayItems(): Sequence<JsonElement> = sequence {
            itemStart = null
            whitespace(); require(next() == '[') { "deepseek_root_array" }; whitespace()
            if (peek() == ']'.code) next() else while (true) {
                yield(itemValue(1)); whitespace()
                val separator = next()
                if (separator == ']') break
                require(separator == ',') { "deepseek_json_separator" }
            }
            whitespace(); require(peek() == -1) { "deepseek_json_trailing" }
        }
        fun objectArrayItems(arrayKey: String, headerKeys: Set<String>,
            allowExtraMetadata: Boolean,
            validateHeader: (JsonObject) -> Unit): Sequence<JsonElement> = sequence {
            itemStart = null
            whitespace(); require(next() == '{') { "archive_root_object" }; whitespace()
            val seen = hashSetOf<String>()
            val header = linkedMapOf<String, JsonElement>()
            if (peek() == '}'.code) next() else while (true) {
                whitespace(); val key = string()
                require(!allowExtraMetadata || (seen.size < 256 && key.length <= 256)) { "archive_header_limit" }
                require(seen.add(key) && (key == arrayKey || key in headerKeys || allowExtraMetadata)) { "archive_header_key" }
                whitespace(); require(next() == ':') { "archive_json_separator" }; whitespace()
                if (key == arrayKey) {
                    require(next() == '[') { "archive_items_array" }; whitespace()
                    if (peek() == ']'.code) next() else while (true) {
                        yield(itemValue(2)); whitespace()
                        val separator = next()
                        if (separator == ']') break
                        require(separator == ',') { "archive_json_separator" }
                    }
                } else if (key in headerKeys) {
                    // Envelope fields are primitives, never settings objects or another unbounded array.
                    require(peek().toChar() !in "{[") { "archive_header_primitive" }
                    header[key] = value(1)
                } else {
                    // Opt-in format metadata (e.g. Operit folders), never imported as settings.
                    // Validate the entire value with the same per-item/depth/string limits,
                    // but do not retain its arrays/objects in memory.
                    values = 0
                    itemStart = offset
                    value(1, discard = true)
                    itemStart = null
                }
                whitespace(); val separator = next()
                if (separator == '}') break
                require(separator == ',') { "archive_json_separator" }
            }
            require(arrayKey in seen) { "archive_missing_items" }
            whitespace(); require(peek() == -1) { "archive_json_trailing" }
            validateHeader(JsonObject(header))
        }
        private fun itemValue(depth: Int): JsonElement {
            values = 0
            itemStart = offset
            return value(depth).also { itemStart = null }
        }
        private fun value(depth: Int, discard: Boolean = false): JsonElement {
            if (depth > 48 || ++values > budget.itemValues) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
            whitespace()
            return when (peek().toChar()) {
                '{' -> {
                    next(); whitespace()
                    val entries = linkedMapOf<String, JsonElement>()
                    if (peek() == '}'.code) next() else while (true) {
                        whitespace(); val key = string()
                        require(key !in entries) { "deepseek_json_duplicate_key" }
                        whitespace(); require(next() == ':') { "deepseek_json_separator" }
                        entries[key] = value(depth + 1, discard); whitespace()
                        val end = next()
                        if (end == '}') break
                        require(end == ',') { "deepseek_json_separator" }
                    }
                    if (discard) JsonNull else JsonObject(entries)
                }
                '[' -> {
                    next(); whitespace()
                    val items = mutableListOf<JsonElement>()
                    if (peek() == ']'.code) next() else while (true) {
                        val item = value(depth + 1, discard)
                        if (!discard) items += item
                        whitespace()
                        val end = next()
                        if (end == ']') break
                        require(end == ',') { "deepseek_json_separator" }
                    }
                    JsonArray(items)
                }
                '"' -> JsonPrimitive(string())
                else -> {
                    val token = StringBuilder()
                    while (peek() >= 0 && peek().toChar() !in " \r\n\t,]}") {
                        require(token.length < 128) { "deepseek_json_literal_size" }; token.append(next())
                    }
                    val literal = token.toString()
                    require(literal in setOf("true", "false", "null") ||
                        Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(literal)) { "deepseek_json_literal" }
                    if (literal == "null") JsonNull else Json.parseToJsonElement(literal)
                }
            }
        }
        private fun string(): String {
            require(next() == '"') { "deepseek_json_string" }
            val output = StringBuilder()
            while (true) {
                val char = next()
                if (char == '"') {
                    var index = 0
                    while (index < output.length) {
                        val current = output[index++]
                        if (current.isHighSurrogate()) {
                            require(index < output.length && output[index++].isLowSurrogate()) { "deepseek_json_surrogate" }
                        } else require(!current.isLowSurrogate()) { "deepseek_json_surrogate" }
                    }
                    return output.toString()
                }
                require(char >= ' ') { "deepseek_json_control" }
                if (char != '\\') output.append(char) else when (val escaped = next()) {
                    '"', '\\', '/' -> output.append(escaped)
                    'b' -> output.append('\b'); 'f' -> output.append('\u000c'); 'n' -> output.append('\n')
                    'r' -> output.append('\r'); 't' -> output.append('\t')
                    'u' -> {
                        var code = 0
                        repeat(4) { code = code * 16 + (next().digitToIntOrNull(16) ?: error("deepseek_json_unicode")) }
                        output.append(code.toChar())
                    }
                    else -> error("deepseek_json_escape")
                }
                require(output.length <= budget.stringChars) { "deepseek_json_string_size" }
            }
        }
    }
}
