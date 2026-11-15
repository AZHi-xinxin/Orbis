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
        require(list.size in 1..ArchiveCapacity.MAX_ENTRIES)
        require(list.map { it.name }.toSet().size == list.size)
        var expanded = 0L
        list.forEach {
            checkCancelled(); ArchiveCapacity.requireSafePath(it.name, it.isDirectory)
            ArchiveCapacity.requireSize(it.size, ArchiveCapacity.MAX_DISK_ENTRY_BYTES, allowEmpty = true)
            expanded += it.size
            ArchiveCapacity.requireSize(expanded, ArchiveCapacity.MAX_EXPANDED_BYTES, allowEmpty = true)
            if (it.isDirectory) require(it.size == 0L)
        }
    }.associateBy { it.name }
    private val manifest = entries.values.single { it.name.substringAfterLast('/') == "manifest.json" }
    private val prefix = manifest.name.removeSuffix("manifest.json").also {
        require(it.isEmpty() || it.removeSuffix("/").split('/').size == 1 && it != "__MACOSX/")
    }
    private val index: List<JsonObject>
    private val sessionIds: Set<String>
    private var consumed = false

    init {
        val version = readJson(manifest.name, 1024 * 1024).jsonObject["export_format_version"]
        require(version == JsonPrimitive(3)) { "deepseek_export_version" }
        index = readJson(prefix + "chat_session_index.json", 16 * 1024 * 1024).jsonArray.map { it.jsonObject }
        require(index.size in 1..10_000)
        sessionIds = index.map { it.getValue("id").jsonPrimitive.content.also(::safeId) }.toSet()
        require(sessionIds.size == index.size)
        val jsonNames = sessionIds.map { prefix + "chat_sessions/$it.json" }.toSet()
        require(jsonNames.all { it in entries })
        val allowedRoots = setOf(prefix + "manifest.json", prefix + "chat_session_index.json", prefix + "user.json")
        val attachmentPrefixes = sessionIds.map { prefix + "chat_sessions/${it}_files/" }.toSet()
        entries.values.forEach { entry ->
            val name = entry.name
            when {
                name in allowedRoots -> if (name.endsWith("user.json")) require(entry.size <= 1024 * 1024)
                name in jsonNames -> ArchiveCapacity.requireSize(entry.size, ArchiveCapacity.MAX_WINDOW_CHARS.toLong())
                entry.isDirectory -> require(name == prefix || name == prefix + "chat_sessions/" || name in attachmentPrefixes)
                attachmentPrefixes.any { name.startsWith(it) && '/' !in name.removePrefix(it) } -> Unit
                else -> error("deepseek_v3_unexpected_path")
            }
        }
    }

    private fun readJson(name: String, limit: Int): JsonElement {
        val entry = requireNotNull(entries[name])
        ArchiveCapacity.requireSize(entry.size, limit.toLong())
        val output = ByteArrayOutputStream()
        ArchiveCapacity.copyZipEntry(zip, entry, output, limit.toLong(), checkCancelled)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        return DeepSeekStrictJson.parse(text, checkCancelled)
    }

    override fun conversations(): Sequence<DeepSeekConversation> = sequence {
        check(!consumed); consumed = true
        var count = 0
        for (header in index) {
            checkCancelled()
            val id = header.getValue("id").jsonPrimitive.content
            val raw = readJson(prefix + "chat_sessions/$id.json", ArchiveCapacity.MAX_WINDOW_CHARS).jsonObject
            require(raw["id"] == header["id"])
            val messages = raw.getValue("messages").jsonArray
            require(header["message_count"]?.jsonPrimitive?.intOrNull == messages.size)
            count += messages.size; require(count <= 1_000_000)
            // An exported path is data, never a destination or URL. Only this chat's local folder is eligible.
            messages.forEach { message -> message.jsonObject["fragments"]?.jsonArray?.forEach { fragment ->
                fragment.jsonObject["files"]?.jsonArray?.forEach { file ->
                    val path = file.jsonObject["export_path"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    if (!path.isNullOrEmpty()) {
                        ArchiveCapacity.requireSafePath(path)
                        require(path.startsWith("chat_sessions/${id}_files/") && path.split('/').size == 3)
                        entries[prefix + path]?.let { entry ->
                            require(file.jsonObject["file_size"]?.jsonPrimitive?.longOrNull == entry.size)
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
        ArchiveCapacity.copyZipEntry(zip, entry, output, ArchiveCapacity.MAX_DISK_ENTRY_BYTES, checkCancelled)
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
                    JsonObject(file + ("file_id" to file.getValue("id")))
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

    private fun safeId(id: String) { require(id.length in 1..256 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) }
}
