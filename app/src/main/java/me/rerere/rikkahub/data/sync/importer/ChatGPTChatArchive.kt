package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.math.RoundingMode
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

internal data class ChatGPTHistoryConversation(
    val history: ClaudeHistoryConversation,
    val currentLeafId: String?,
    val excludedMessages: Int,
    val missingTimestamps: Int,
    val unfamiliarContents: Int,
)

/** Official conversations JSON, not a ZIP extractor or a browser/session importer.
 * Mapping parents are authoritative. Every visible branch is offered separately; children,
 * insertion order and timestamps are never used to stitch mutually exclusive answers together. */
object ChatGPTChatArchive {
    const val MAX_ARCHIVE_BYTES = ArchiveCapacity.MAX_STREAM_JSON_BYTES
    internal const val SELECTED_PATH = "chatgpt-path-v1"
    internal const val MAX_NODES = 100_000
    internal const val MAX_DEPTH = 10_000
    internal const val MAX_PATHS = 1024
    internal const val MAX_EXPANDED_MESSAGES = 1_000_000L

    internal fun selectionKey(conversationId: String, leafId: String): String =
        "${conversationId.length}:$conversationId/${leafId.length}:$leafId"

    fun inspect(file: File, checkCancelled: () -> Unit = {}): DeepSeekArchivePreview = ClaudeChatArchive.safe {
        open(file, checkCancelled).use { archive ->
            var sources = 0
            var excluded = 0
            var missingTimes = 0
            var references = 0
            var unfamiliar = 0
            var messages = 0
            var expanded = 0L
            val rows = buildList {
                for (record in archive.conversations()) {
                    val source = record.history
                    sources++
                    excluded += record.excludedMessages
                    missingTimes += record.missingTimestamps
                    unfamiliar += record.unfamiliarContents
                    messages += source.messages.size
                    references += source.messages.values.sumOf { it.attachmentReferences }
                    source.paths.forEachIndexed { index, path ->
                        checkCancelled()
                        expanded += path.messageCount
                        val current = record.currentLeafId == path.leafId
                        add(DeepSeekConversationPreview(
                            sourceId = selectionKey(source.sourceId, path.leafId),
                            title = source.title + if (source.paths.size > 1) " · 路径 ${index + 1}/${source.paths.size}" else "",
                            createdAt = source.createdAt, updatedAt = source.updatedAt,
                            totalNodes = source.messages.size, messageCount = source.messages.size,
                            branchPointCount = source.branchPoints,
                            branches = listOf(DeepSeekBranchPreview(SELECTED_PATH, path.messageCount,
                                source.messages[path.leafId]?.updatedAt ?: source.updatedAt, true)),
                            defaultLeafId = SELECTED_PATH,
                            defaultSelectionReason = if (current) "chatgpt_complete_path_current" else "chatgpt_complete_path",
                        ))
                    }
                }
            }
            DeepSeekArchivePreview(rows, buildList {
                add("发现 $sources 个 ChatGPT 源会话、$messages 条可见历史消息，共 ${rows.size} 条可选路径；全选保留全部可见分支，公共前文会重复，合计 $expanded 条导入消息。活动路径已标明，不把其他回答拼入同一条聊天。")
                if (excluded > 0) add("有 $excluded 条系统、开发者、自定义设置或源文件标记为隐藏的消息未导入；不会用它们改变应用系统提示。原始资料仍在原 JSON 中，请保管。")
                if (missingTimes > 0) add("有 $missingTimes 处时间缺失：消息沿用源会话时间，源会话时间也缺失时显示 1970-01-01；未猜测当前时间，原 JSON 未改动。")
                if (references > 0) add("有 $references 处图片、音频、视频或附件引用，仅保留未恢复说明；不导入文件、不访问本机路径或联网下载。")
                if (unfamiliar > 0) add("有 $unfamiliar 段尚未识别的内容类型，已用只读原始内容 JSON 保留，未当作附件恢复或执行；请保留原导出包。")
                add("历史工具只保存为只读文字，不执行；不导入账号、模型、密钥、设置或授权。不完整父链会停止读取，不静默丢弃正文。")
                add("只支持官方导出的 conversations.json 或 conversations-NNN.json 顶层数组，请先在本机解压并逐个选择；不读取 ZIP、HTML 或第三方会话抓取文件。相同内容版本跳过，变化后另建窗口，不覆盖旧记录。")
            })
        }
    }

    internal fun open(file: File, checkCancelled: () -> Unit = {}): ArchiveReader {
        checkCancelled()
        require(file.isFile)
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        return ArchiveReader(file, checkCancelled)
    }

    internal class ArchiveReader(file: File, private val checkCancelled: () -> Unit) : Closeable {
        private var consumed = false
        private var bytes = 0L
        private val stream = object : FilterInputStream(file.inputStream()) {
            override fun read(): Int {
                checkCancelled()
                return `in`.read().also { if (it >= 0) account(1) }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                return `in`.read(buffer, offset, minOf(length.toLong(), MAX_ARCHIVE_BYTES - bytes + 1).toInt())
                    .also { if (it > 0) account(it) }
            }
            private fun account(count: Int) { bytes += count; ArchiveCapacity.requireSize(bytes, MAX_ARCHIVE_BYTES) }
        }
        private val reader = PushbackReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(32 * 1024), 1)

        fun conversations(): Sequence<ChatGPTHistoryConversation> = sequence {
            check(!consumed); consumed = true
            val first = reader.read()
            if (first != -1 && first != '\uFEFF'.code) reader.unread(first)
            val ids = hashSetOf<String>()
            var nodes = 0L
            var paths = 0L
            var expanded = 0L
            for (item in DeepSeekStrictJson.arrayItems(reader, checkCancelled)) {
                checkCancelled()
                val raw = item as? JsonObject ?: error("chatgpt_conversation_object")
                val source = parseConversation(raw, checkCancelled)
                require(ids.add(source.history.sourceId)) { "chatgpt_duplicate_conversation" }
                nodes += (raw["mapping"] as JsonObject).size
                paths += source.history.paths.size
                expanded += source.history.paths.sumOf { it.messageCount.toLong() }
                if (ids.size > 10_000 || nodes > 1_000_000 || paths > 10_000 || expanded > MAX_EXPANDED_MESSAGES)
                    throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                yield(source)
            }
            require(ids.isNotEmpty()) { "chatgpt_empty_archive" }
        }
        override fun close() = reader.close()
    }

    internal fun parseConversation(raw: JsonObject, checkCancelled: () -> Unit = {}): ChatGPTHistoryConversation {
        val id = identity(raw.optionalString("id") ?: raw.string("conversation_id"))
        raw.optionalString("conversation_id")?.let { require(it == id) { "chatgpt_conversation_identity" } }
        val title = raw.optionalString("title").orEmpty().also { require(it.length <= 512) }
        var missingTimes = 0
        fun time(value: JsonElement?, fallback: Instant): Instant =
            if (value == null || value == JsonNull) { missingTimes++; fallback } else timestamp(value)
        val created = time(raw["create_time"], Instant.EPOCH)
        val updated = time(raw["update_time"], created)
        val mapping = raw["mapping"] as? JsonObject ?: error("chatgpt_mapping_object")
        if (mapping.size > MAX_NODES) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
        val parents = linkedMapOf<String, String?>()
        val children = hashMapOf<String, Int>()
        val visible = linkedMapOf<String, ClaudeHistoryMessage>()
        val sourceMessageIds = hashSetOf<String>()
        var excluded = 0
        var unfamiliar = 0
        // Canonical order makes ids/content versions independent of JSON object member order.
        for ((index, nodeId) in mapping.keys.sorted().withIndex()) {
            checkCancelled()
            identity(nodeId)
            val node = mapping.getValue(nodeId) as? JsonObject ?: error("chatgpt_node_object")
            node.optionalString("id")?.let { require(it == nodeId) { "chatgpt_node_identity" } }
            val parent = node.optionalString("parent")?.let(::identity)
            require(parent == null || parent in mapping) { "chatgpt_missing_parent" }
            parents[nodeId] = parent
            if (parent != null) children[parent] = (children[parent] ?: 0) + 1
            val rawMessage = node["message"]
            if (rawMessage == null || rawMessage == JsonNull) continue
            val message = rawMessage as? JsonObject ?: error("chatgpt_message_object")
            message.optionalString("id")?.let { require(sourceMessageIds.add(identity(it))) { "chatgpt_duplicate_message" } }
            val author = message["author"] as? JsonObject ?: error("chatgpt_author")
            val role = author.string("role")
            require(role in setOf("user", "assistant", "tool", "system", "developer")) { "chatgpt_role" }
            val metadata = message.optionalObject("metadata")
            val hidden = metadata?.get("is_visually_hidden_from_conversation")
            require(hidden == null || hidden == JsonNull || hidden == JsonPrimitive(true) || hidden == JsonPrimitive(false))
            if (role == "system" || role == "developer" || hidden == JsonPrimitive(true)) { excluded++; continue }
            val content = message["content"] as? JsonObject ?: error("chatgpt_content")
            if (content.optionalString("content_type") == "user_editable_context") { excluded++; continue }
            val parsed = contentParts(content)
            unfamiliar += parsed.unfamiliar
            val recipient = message.optionalString("recipient")
            val historicalTool = role == "tool" || (role == "assistant" && recipient != null && recipient != "all")
            val parts = if (historicalTool) listOf(ClaudeHistoryPart(
                "[ChatGPT 历史工具记录；只读文字，未执行]\n" + inertText(parsed.parts.joinToString("") { it.text })))
                else parsed.parts
            val attachments = metadata?.get("attachments")
            require(attachments == null || attachments == JsonNull || attachments is JsonArray)
            val references = (attachments as? JsonArray)?.size ?: 0
            require(references <= 10_000)
            val withAttachments = parts.toMutableList()
            if (references > 0) withAttachments += ClaudeHistoryPart("[ChatGPT 原记录另有 $references 处附件引用；文件未恢复，未读取或下载，请保留原导出包]")
            val started = time(message["create_time"], created)
            val finished = if (message["update_time"] == null || message["update_time"] == JsonNull) started else timestamp(message.getValue("update_time"))
            visible[nodeId] = ClaudeHistoryMessage(nodeId, parent, if (role == "user") "human" else "assistant",
                started, finished, index, withAttachments.ifEmpty { listOf(ClaudeHistoryPart("")) },
                parsed.references + references, false)
        }
        // Validate the entire graph, including hidden/null components. Iterative and memoized O(n).
        val depths = hashMapOf<String, Int>()
        val nearestVisible = hashMapOf<String, String?>()
        val visibleDepth = hashMapOf<String, Int>()
        for (start in parents.keys) {
            checkCancelled()
            if (start in depths) continue
            val walk = arrayListOf<String>()
            val visiting = hashSetOf<String>()
            var cursor: String? = start
            while (cursor != null && cursor !in depths) {
                checkCancelled()
                require(visiting.add(cursor)) { "chatgpt_cycle" }
                walk += cursor
                if (walk.size > MAX_DEPTH) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                cursor = parents.getValue(cursor)
            }
            var depth = cursor?.let { depths.getValue(it) } ?: 0
            var count = cursor?.let { visibleDepth.getValue(it) } ?: 0
            var ancestor = cursor?.let { nearestVisible[it] }
            for (nodeId in walk.asReversed()) {
                if (++depth > MAX_DEPTH) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                visible[nodeId]?.let { message ->
                    visible[nodeId] = message.copy(parentId = ancestor)
                    ancestor = nodeId
                    count++
                }
                depths[nodeId] = depth
                visibleDepth[nodeId] = count
                nearestVisible[nodeId] = ancestor
            }
        }
        val current = raw.optionalString("current_node")?.let(::identity)
        require(current == null || current in parents) { "chatgpt_current_node_missing" }
        val currentVisible = current?.let { nearestVisible[it] }
        val leaves = parents.keys.filter { it !in children }.mapNotNull { nearestVisible[it] }.toMutableSet()
        // current_node can be a non-leaf after editing. Offer its exact prefix as well as all leaves.
        currentVisible?.let(leaves::add)
        if (leaves.size > MAX_PATHS) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
        val orderedLeaves = leaves.sortedWith(compareBy<String> { it != currentVisible }.thenBy { it })
        val paths = orderedLeaves.map { ClaudeHistoryPath(it, visibleDepth.getValue(it), false) }
            .ifEmpty { listOf(ClaudeHistoryPath("", 0, false)) }
        if (paths.sumOf { it.messageCount.toLong() } > MAX_EXPANDED_MESSAGES) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            checkCancelled()
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
        }
        listOf(id, title, created.toString(), updated.toString()).forEach(::field)
        visible.values.forEach { message ->
            listOf(message.sourceId, message.parentId.orEmpty(), message.sender, message.createdAt.toString(),
                message.updatedAt.toString(), message.parts.size.toString()).forEach(::field)
            message.parts.forEach { field(it.reasoning.toString()); field(it.text) }
        }
        val version = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return ChatGPTHistoryConversation(ClaudeHistoryConversation(id, title, created, updated, visible, paths,
            children.values.count { it > 1 }, version), currentVisible, excluded, missingTimes, unfamiliar)
    }

    private data class ContentParts(val parts: List<ClaudeHistoryPart>, val references: Int = 0, val unfamiliar: Int = 0)
    private val mediaKinds = setOf("image_asset_pointer", "audio", "audio_perception", "audio_uncond",
        "audio_uncond_generation", "audio_generation", "real_time_user_audio_video", "video", "video_asset_pointer")
    private fun contentParts(content: JsonObject): ContentParts {
        val type = content.string("content_type")
        return when (type) {
            "text", "multimodal_text" -> {
                val parts = content["parts"] as? JsonArray ?: error("chatgpt_parts")
                var references = 0
                var unfamiliar = 0
                ContentParts(parts.map { part ->
                    when {
                        part is JsonPrimitive && part.isString -> ClaudeHistoryPart(part.content)
                        part is JsonObject -> {
                            val kind = part.string("content_type")
                            when {
                                kind in mediaKinds -> { references++; ClaudeHistoryPart("[ChatGPT 历史媒体引用（$kind）；文件未恢复，未读取或下载]") }
                                kind == "text" -> ClaudeHistoryPart(part.string("text"))
                                kind == "text_audio" || kind == "audio_transcription" -> {
                                    references++; ClaudeHistoryPart(part.string("text") + "\n[ChatGPT 历史音频引用；音频未恢复]")
                                }
                                else -> { unfamiliar++; unknownPart(part) }
                            }
                        }
                        else -> throw ArchiveReadException(ArchiveFailure.FORMAT)
                    }
                }, references, unfamiliar)
            }
            "code", "execution_output" -> ContentParts(listOf(ClaudeHistoryPart(inertText(content.string("text")))))
            "tether_quote", "sonic_webpage", "system_error" -> ContentParts(listOf(ClaudeHistoryPart(content.string("text"))))
            "thoughts" -> {
                val thoughts = content["thoughts"] as? JsonArray ?: error("chatgpt_thoughts")
                var unfamiliar = 0
                ContentParts(thoughts.map { element ->
                    val thought = element as? JsonObject ?: error("chatgpt_thought")
                    val text = listOfNotNull(thought.optionalString("summary"), thought.optionalString("content"))
                    if (text.isEmpty()) { unfamiliar++; unknownPart(thought) }
                    else ClaudeHistoryPart(text.joinToString("\n"), true)
                }, unfamiliar = unfamiliar)
            }
            "reasoning_recap" -> ContentParts(listOf(ClaudeHistoryPart(content.string("content"), true)))
            "text_audio", "audio_transcription" -> ContentParts(listOf(ClaudeHistoryPart(content.string("text")),
                ClaudeHistoryPart("[ChatGPT 历史音频引用；音频未恢复]")), 1)
            in mediaKinds -> ContentParts(listOf(ClaudeHistoryPart("[ChatGPT 历史媒体引用（$type）；文件未恢复，未读取或下载]")), 1)
            else -> ContentParts(listOf(unknownPart(content)), unfamiliar = 1)
        }
    }
    private fun unknownPart(content: JsonObject) = ClaudeHistoryPart(
        "[ChatGPT 尚未适配的历史内容；以下为只读原始内容 JSON，未执行或恢复附件]\n" + inertText(content.toString()))
    private fun inertText(text: String): String {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
        return "$fence\n$text\n$fence"
    }
    private fun identity(value: String) = value.also { require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) }
    private fun timestamp(value: JsonElement): Instant {
        val number = value as? JsonPrimitive ?: error("chatgpt_timestamp")
        require(!number.isString && number.content.length <= 64)
        val decimal = number.content.toBigDecimal()
        require(decimal.scale() in -20..9)
        require(decimal.signum() >= 0 && decimal < "253402300800".toBigDecimal())
        val seconds = decimal.setScale(0, RoundingMode.DOWN).longValueExact()
        val nanos = decimal.subtract(seconds.toBigDecimal()).movePointRight(9).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }
    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("chatgpt_string")
    private fun JsonObject.optionalString(key: String): String? = if (get(key) == null || get(key) == JsonNull) null else string(key)
    private fun JsonObject.optionalObject(key: String): JsonObject? =
        if (get(key) == null || get(key) == JsonNull) null else get(key) as? JsonObject ?: error("chatgpt_object")
}
