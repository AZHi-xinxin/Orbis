package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Official v3 export: index + one JSON per chat + optional local attachments. No profile is read. */
internal class DeepSeekV3Archive(private val zip: ZipFile, private val checkCancelled: () -> Unit) : DeepSeekArchive.ArchiveReader {
    private val entries = zip.entries().asSequence().toList().also { list ->
        require(list.isNotEmpty())
        require(list.map { it.name }.toSet().size == list.size)
        list.forEach {
            checkCancelled(); ArchiveCapacity.requireSafePath(it.name, it.isDirectory)
            ArchiveCapacity.requireSize(it.size, Long.MAX_VALUE, allowEmpty = true)
            if (it.isDirectory) require(it.size == 0L)
        }
    }.associateBy { it.name }
    private val manifest = entries.values.single { !it.isDirectory && !it.name.startsWith("__MACOSX/") &&
        it.name.substringAfterLast('/') == "manifest.json" && entries.keys.any { path ->
            path == it.name.removeSuffix("manifest.json") + "chat_session_index.json" ||
                path.startsWith(it.name.removeSuffix("manifest.json") + "chat_sessions/") && path.endsWith(".json")
        } }
    private val prefix = manifest.name.removeSuffix("manifest.json").also {
        require(it.isEmpty() || !it.startsWith("__MACOSX/"))
    }
    private val index: List<JsonObject>
    private val sessionIds: Set<String>
    private var consumed = false
    private val indexed = prefix + "chat_session_index.json" in entries
    override val warnings: List<String> get() = if (indexed) emptyList() else
        listOf("原包缺少会话索引，已从同一导出目录的会话文件恢复列表；请核对窗口和消息数量。")

    init {
        val version = readJson(manifest.name).jsonObject["export_format_version"]
        require((version as? JsonPrimitive)?.intOrNull?.let { it >= 3 } == true) { "deepseek_export_version" }
        index = if (indexed) readJson(prefix + "chat_session_index.json").jsonArray.map { it.jsonObject }
        else entries.keys.filter { it.startsWith(prefix + "chat_sessions/") &&
            it.removePrefix(prefix + "chat_sessions/").let { name -> '/' !in name && name.endsWith(".json") } }
            .sorted().map { buildJsonObject { put("id", it.substringAfterLast('/').removeSuffix(".json")) } }
        require(index.isNotEmpty())
        sessionIds = index.map { it.getValue("id").jsonPrimitive.content.also(::safeId) }.toSet()
        require(sessionIds.size == index.size)
        val jsonNames = sessionIds.map { prefix + "chat_sessions/$it.json" }.toSet()
        require(jsonNames.all { it in entries })
        // New optional metadata does not change the indexed conversation layout.
        // All names were validated above; only explicitly indexed chats are read.
    }

    private fun readJson(name: String): JsonElement {
        val entry = requireNotNull(entries[name])
        val crc = java.util.zip.CRC32()
        var count = 0L
        val input = object : java.io.FilterInputStream(zip.getInputStream(entry)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                return `in`.read(buffer, offset, length).also { n ->
                    if (n > 0) { count += n; crc.update(buffer, offset, n) }
                    if (count > entry.size) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
                }
            }
            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 255
            }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return java.io.InputStreamReader(input, decoder).buffered(32768).use { reader ->
            DeepSeekStrictJson.read(reader, checkCancelled, StreamingJsonBudget.deepSeek()).also {
                if (count != entry.size || crc.value != entry.crc) throw ArchiveReadException(ArchiveFailure.CHECKSUM)
            }
        }
    }

    override fun conversations(): Sequence<DeepSeekConversation> = sequence {
        check(!consumed); consumed = true
        for (header in index) {
            checkCancelled()
            val id = header.getValue("id").jsonPrimitive.content
            val raw = readJson(prefix + "chat_sessions/$id.json").jsonObject
            require(raw["id"] == header["id"])
            val messages = raw.getValue("messages").jsonArray
            if (indexed) require(header["message_count"]?.jsonPrimitive?.intOrNull == messages.size)
            // An exported path is data, never a destination or URL. Only this chat's local folder is eligible.
            messages.forEach { message -> message.jsonObject["fragments"]?.jsonArray?.forEach { fragment ->
                fragment.jsonObject["files"]?.jsonArray?.forEach { file ->
                    val path = file.jsonObject["export_path"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    if (!path.isNullOrEmpty()) {
                        ArchiveCapacity.requireSafePath(path)
                        require(path.startsWith("chat_sessions/${id}_files/") && path.split('/').size == 3)
                        entries[prefix + path]?.let { entry ->
                            file.jsonObject["file_size"]?.takeUnless { it == JsonNull }?.let {
                                require((it as? JsonPrimitive)?.longOrNull == entry.size)
                            }
                        }
                    }
                }
            } }
            yield(DeepSeekParser.parse(JsonArray(listOf(normalize(raw))), checkCancelled).conversations.single())
        }
    }

    override fun copyAttachment(path: String, output: OutputStream): Boolean {
        ArchiveCapacity.requireSafePath(path)
        val parts = path.split('/')
        require(parts.size == 3 && parts[0] == "chat_sessions" && parts[1].endsWith("_files") && parts[1].removeSuffix("_files") in sessionIds)
        val entry = entries[prefix + path] ?: return false
        require(!entry.isDirectory)
        ArchiveCapacity.copyZipEntry(zip, entry, output, Long.MAX_VALUE, checkCancelled)
        return true
    }
    override fun close() = zip.close()

    private fun normalize(raw: JsonObject): JsonObject {
        val messages = raw.getValue("messages").jsonArray.map { it.jsonObject }
        val root = "__deepseek_v3_root__"
        fun messageId(value: JsonElement): String = value.jsonPrimitive.let {
            require(!it.isString && it.longOrNull != null && it.long >= 0); it.content
        }
        val ids = messages.map { messageId(it.getValue("id")) }
        require(ids.size == ids.toSet().size)
        val parents = messages.map { it.getValue("parent_id").let { p -> if (p == JsonNull) root else messageId(p) } }
        val children = ids.indices.groupBy({ parents[it] }, { ids[it] })
        val mapping = linkedMapOf<String, JsonElement>()
        fun node(id: String, parent: String?, message: JsonElement) = buildJsonObject {
            put("id", id); put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("children", JsonArray(children[id].orEmpty().map(::JsonPrimitive))); put("message", message)
        }
        mapping[root] = node(root, null, JsonNull)
        messages.forEachIndexed { position, m ->
            checkCancelled()
            val role = m.getValue("role").jsonPrimitive.content
            require(role in setOf("USER", "ASSISTANT"))
            val fragments = m.getValue("fragments").jsonArray.map { fragment ->
                val f = fragment.jsonObject
                if (f["type"] == JsonPrimitive("FILE")) JsonObject(f + ("files" to JsonArray(f.getValue("files").jsonArray.map {
                    val file = it.jsonObject
                    JsonObject(file + ("file_id" to (file["id"]?.takeUnless { it == JsonNull }
                        ?: JsonPrimitive(deepSeekImportId("file", raw.getValue("id").jsonPrimitive.content,
                            file.toString()).toString()))))
                }))) else f
            }
            mapping[ids[position]] = node(ids[position], parents[position], JsonObject(m + mapOf(
                "model" to (m["model"] ?: JsonPrimitive("")), "fragments" to JsonArray(fragments),
                "export_v3" to JsonPrimitive(true))))
        }
        val current = raw["current_message_id"]?.takeUnless { it == JsonNull }?.let(::messageId)
        return JsonObject(raw + mapOf("mapping" to JsonObject(mapping),
            "current_node" to (current?.let(::JsonPrimitive) ?: JsonNull)))
    }

    private fun safeId(id: String) { require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) }
}
