package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

internal data class ClaudeHistoryPart(val text: String, val reasoning: Boolean = false)
internal data class ClaudeHistoryMessage(
    val sourceId: String, val parentId: String?, val sender: String, val createdAt: Instant,
    val updatedAt: Instant, val sourceIndex: Int, val parts: List<ClaudeHistoryPart>,
    val attachmentReferences: Int, val differentTextCopy: Boolean,
)
internal data class ClaudeHistoryPath(val leafId: String, val messageCount: Int, val missingParent: Boolean)
internal data class ClaudeHistoryConversation(
    val sourceId: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val messages: LinkedHashMap<String, ClaudeHistoryMessage>, val paths: List<ClaudeHistoryPath>,
    val branchPoints: Int, val contentVersion: String,
) {
    fun pathTo(path: ClaudeHistoryPath, checkCancelled: () -> Unit = {}): List<ClaudeHistoryMessage> {
        if (messages.isEmpty()) return emptyList()
        val result = ArrayList<ClaudeHistoryMessage>(path.messageCount)
        var cursor: ClaudeHistoryMessage? = messages.getValue(path.leafId)
        while (cursor != null) {
            checkCancelled()
            check(result.size < path.messageCount) { "claude_invalid_path" }
            result += cursor
            cursor = cursor.parentId?.let(messages::get)
        }
        check(result.size == path.messageCount) { "claude_invalid_path" }
        result.reverse()
        return result
    }
}

/** Claude conversations.json only. Account/settings fields never become imported data.
 * A forest is expanded into explicit root-to-leaf rows, never guessed to have one active branch. */
object ClaudeChatArchive {
    const val MAX_ARCHIVE_BYTES = ArchiveCapacity.MAX_STREAM_JSON_BYTES
    internal const val SELECTED_PATH = "claude-path-v1"
    internal const val MAX_CONVERSATIONS = 10_000
    internal const val MAX_MESSAGES = 1_000_000
    internal const val MAX_PATHS_PER_CONVERSATION = 1024
    internal const val MAX_PATHS = 10_000
    internal const val MAX_EXPANDED_MESSAGES = 1_000_000L

    internal fun selectionKey(conversationId: String, leafId: String): String =
        "${conversationId.length}:$conversationId/${leafId.length}:$leafId"

    fun inspect(file: File, checkCancelled: () -> Unit = {}): DeepSeekArchivePreview = safe {
        open(file, checkCancelled).use { archive ->
            var sourceCount = 0
            var uniqueMessages = 0
            var copiedMessages = 0L
            var missingParents = 0
            var differentText = 0
            var attachmentReferences = 0
            val previews = buildList {
                for (source in archive.conversations()) {
                    sourceCount++
                    uniqueMessages += source.messages.size
                    missingParents += source.messages.values.count { it.parentId != null && it.parentId !in source.messages }
                    differentText += source.messages.values.count { it.differentTextCopy }
                    attachmentReferences += source.messages.values.sumOf { it.attachmentReferences }
                    source.paths.forEachIndexed { index, path ->
                        checkCancelled()
                        copiedMessages += path.messageCount
                        add(DeepSeekConversationPreview(
                            sourceId = selectionKey(source.sourceId, path.leafId),
                            title = source.title + if (source.paths.size > 1) " · 路径 ${index + 1}/${source.paths.size}" else "",
                            createdAt = source.createdAt, updatedAt = source.updatedAt,
                            totalNodes = source.messages.size, messageCount = source.messages.size,
                            branchPointCount = source.branchPoints,
                            branches = listOf(DeepSeekBranchPreview(SELECTED_PATH, path.messageCount,
                                source.messages[path.leafId]?.updatedAt ?: source.updatedAt, true)),
                            defaultLeafId = SELECTED_PATH,
                            defaultSelectionReason = if (path.missingParent) "claude_complete_path_missing_parent" else "claude_complete_path",
                        ))
                    }
                }
            }
            DeepSeekArchivePreview(previews, buildList {
                add("发现 $sourceCount 个 Claude 源会话、$uniqueMessages 条不同的原消息，共 ${previews.size} 条可选路径。全选可保留全部原消息；不同路径的公共前文会重复，合计 $copiedMessages 条导入消息。")
                if (missingParents > 0) add("有 $missingParents 处父消息未包含在导出中，已从现有消息开始保留路径并标记缺口；没有猜补或丢弃消息。")
                if (differentText > 0) add("$differentText 条消息的文字副本与分块内容不同，将两者都保留并标明来源，避免遗漏。")
                if (attachmentReferences > 0) add("有 $attachmentReferences 条附件引用（不是实际文件数量）；只保留文字说明，不读取或下载附件。")
                add("历史工具仅作为文字记录，不执行、不恢复工具授权；不导入账号、连接配置、批准凭据或签名。请私密保管原文件。")
                add("相同源会话的相同内容版本会跳过；源会话内容变化后再次导入将另建版本窗口，不更新或覆盖旧记录。")
            })
        }
    }

    internal fun open(file: File, checkCancelled: () -> Unit = {}): ArchiveReader {
        checkCancelled()
        require(file.isFile) { "claude_missing_file" }
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
            private fun account(count: Int) {
                bytes += count
                ArchiveCapacity.requireSize(bytes, MAX_ARCHIVE_BYTES)
            }
        }
        private val reader = PushbackReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(32 * 1024), 1)

        fun conversations(): Sequence<ClaudeHistoryConversation> = sequence {
            check(!consumed); consumed = true
            val first = reader.read()
            if (first != -1 && first != '\uFEFF'.code) reader.unread(first)
            val identities = hashSetOf<String>()
            var messages = 0L
            var paths = 0L
            var expanded = 0L
            for (item in DeepSeekStrictJson.arrayItems(reader, checkCancelled)) {
                checkCancelled()
                val source = parseConversation(item as? JsonObject ?: error("claude_conversation_object"), checkCancelled)
                require(identities.add(source.sourceId) && identities.size <= MAX_CONVERSATIONS) { "claude_conversation_ids" }
                messages += source.messages.size
                paths += source.paths.size
                expanded += source.paths.sumOf { it.messageCount.toLong() }
                if (messages > MAX_MESSAGES || paths > MAX_PATHS || expanded > MAX_EXPANDED_MESSAGES)
                    throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                yield(source)
            }
            require(identities.isNotEmpty()) { "claude_empty_archive" }
        }
        override fun close() = reader.close()
    }

    internal fun parseConversation(raw: JsonObject, checkCancelled: () -> Unit = {}): ClaudeHistoryConversation {
        val sourceId = identity(raw.string("uuid"))
        val title = raw.string("name").also { require(it.length <= 512) { "claude_title" } }
        val createdAt = timestamp(raw.string("created_at"))
        val updatedAt = timestamp(raw.string("updated_at"))
        val rawMessages = raw["chat_messages"] as? JsonArray ?: error("claude_messages_array")
        require(rawMessages.size <= MAX_MESSAGES) { "claude_message_limit" }
        val messages = linkedMapOf<String, ClaudeHistoryMessage>()
        rawMessages.forEachIndexed { index, element ->
            checkCancelled()
            val message = element as? JsonObject ?: error("claude_message_object")
            val id = identity(message.string("uuid"))
            require(id !in messages) { "claude_duplicate_message" }
            val sender = message.string("sender")
            require(sender == "human" || sender == "assistant") { "claude_sender" }
            val parent = message.optionalString("parent_message_uuid")?.let(::identity)
            require(parent != id) { "claude_cycle" }
            val body = message.optionalString("text")
            val content = message.optionalArray("content")
            require(body != null || content != null) { "claude_missing_content" }
            val parts = content.orEmpty().map { block ->
                historyPart(block as? JsonObject ?: error("claude_content_object"))
            }.toMutableList()
            val textBlocks = content.orEmpty().filter { (it as JsonObject).string("type") == "text" }
                .joinToString("") { (it as JsonObject).string("text") }
            val differentText = !body.isNullOrEmpty() && !content.isNullOrEmpty() && body != textBlocks
            if (content.isNullOrEmpty()) parts += ClaudeHistoryPart(body.orEmpty())
            else if (differentText) parts += ClaudeHistoryPart("[Claude 原始文字副本；与上方分块内容不同，完整保留]\n$body")
            var references = 0
            for (field in listOf("files", "attachments")) {
                val attachments = message.optionalArray(field).orEmpty()
                require(attachments.size <= 10_000) { "claude_attachment_limit" }
                for (attachment in attachments) {
                    checkCancelled()
                    val record = attachment as? JsonObject ?: error("claude_attachment_object")
                    val safeRecord = JsonObject(record.filterKeys { it in setOf(
                        "file_name", "file_uuid", "file_size", "file_type", "extracted_content") })
                    parts += ClaudeHistoryPart("[Claude 历史附件引用；不含已恢复的文件，未读取或下载]\n" + inertJson(safeRecord))
                    references++
                }
            }
            if (parts.isEmpty()) parts += ClaudeHistoryPart("")
            messages[id] = ClaudeHistoryMessage(id, parent, sender, timestamp(message.string("created_at")),
                timestamp(message.string("updated_at")), index, parts, references, differentText)
        }
        // Iterative memoized parent walk: accepts an out-of-order forest and detects cycles in O(nodes).
        // Missing parents are explicit roots, not a reason to discard the entire component.
        val depths = hashMapOf<String, Int>()
        val missing = hashMapOf<String, Boolean>()
        val children = hashMapOf<String, Int>()
        messages.values.forEach { node ->
            checkCancelled()
            node.parentId?.takeIf { it in messages }?.let { children[it] = (children[it] ?: 0) + 1 }
            if (node.sourceId in depths) return@forEach
            val walk = mutableListOf<ClaudeHistoryMessage>()
            val visiting = hashSetOf<String>()
            var cursor: ClaudeHistoryMessage? = node
            while (cursor != null && cursor.sourceId !in depths) {
                checkCancelled()
                require(visiting.add(cursor.sourceId)) { "claude_cycle" }
                walk += cursor
                cursor = cursor.parentId?.let(messages::get)
            }
            var depth = cursor?.let { depths.getValue(it.sourceId) } ?: 0
            val absent = cursor?.let { missing.getValue(it.sourceId) }
                ?: (walk.last().parentId != null)
            for (visited in walk.asReversed()) {
                depths[visited.sourceId] = ++depth
                missing[visited.sourceId] = absent
            }
        }
        val leaves = messages.values.filter { it.sourceId !in children }
        if (leaves.size > MAX_PATHS_PER_CONVERSATION) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
        val paths = leaves.map { ClaudeHistoryPath(it.sourceId, depths.getValue(it.sourceId), missing.getValue(it.sourceId)) }
            .ifEmpty { listOf(ClaudeHistoryPath("", 0, false)) }
        if (paths.sumOf { it.messageCount.toLong() } > MAX_EXPANDED_MESSAGES)
            throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
        // Hash only imported content, not account/summary/approval metadata or object key order.
        // Incremental length framing avoids an extra serialized copy of the entire source window.
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            checkCancelled()
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        listOf(sourceId, title, createdAt.toString(), updatedAt.toString(), messages.size.toString()).forEach(::field)
        messages.values.forEach { message ->
            listOf(message.sourceId, message.parentId.orEmpty(), message.sender, message.createdAt.toString(),
                message.updatedAt.toString(), message.parts.size.toString(), message.attachmentReferences.toString()).forEach(::field)
            message.parts.forEach { field(it.reasoning.toString()); field(it.text) }
        }
        val version = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return ClaudeHistoryConversation(sourceId, title, createdAt, updatedAt, messages, paths,
            children.values.count { it > 1 }, version)
    }

    private fun historyPart(block: JsonObject): ClaudeHistoryPart = when (block.string("type")) {
        "text" -> ClaudeHistoryPart(block.string("text"))
        "thinking" -> {
            val text = block.string("thinking")
            val incomplete = listOf("truncated", "cut_off").any { key ->
                (block[key] as? JsonPrimitive)?.content == "true"
            }
            ClaudeHistoryPart(text + if (incomplete) "\n[源导出标记此段思考不完整；未补写]" else "", true)
        }
        "tool_use", "tool_result" -> {
            // Explicit projection excludes approval credentials, server URLs, context and signatures.
            val record = JsonObject(block.filterKeys { it in setOf("type", "name", "id", "tool_use_id",
                "input", "content", "structured_content", "display_content", "message", "is_error") })
            ClaudeHistoryPart("[Claude 历史工具记录；只读文字，未执行]\n" + inertJson(record))
        }
        else -> throw ArchiveReadException(ArchiveFailure.FORMAT)
    }

    /** A fence longer than every source backtick run prevents imported tool JSON escaping into markup. */
    private fun inertJson(value: JsonObject): String {
        val encoded = value.toString()
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(encoded).maxOfOrNull { it.value.length } ?: 0) + 1))
        return "$fence" + "json\n" + encoded + "\n$fence"
    }
    private fun identity(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "claude_id" }
    }
    private fun timestamp(value: String): Instant = Instant.parse(value).also {
        require(it.epochSecond in 0..253402300799L) { "claude_timestamp" }
    }
    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("claude_string")
    private fun JsonObject.optionalString(key: String): String? =
        if (get(key) == null || get(key) == JsonNull) null else string(key)
    private fun JsonObject.optionalArray(key: String): JsonArray? =
        if (get(key) == null || get(key) == JsonNull) null else get(key) as? JsonArray ?: error("claude_array")
    internal inline fun <T> safe(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ArchiveReadException) { throw failure }
    catch (_: java.nio.charset.CharacterCodingException) { throw ArchiveReadException(ArchiveFailure.INVALID_UTF8) }
    catch (_: java.io.IOException) { throw ArchiveReadException(ArchiveFailure.READ_WRITE) }
    catch (_: Exception) { throw ArchiveReadException(ArchiveFailure.FORMAT) }
}
