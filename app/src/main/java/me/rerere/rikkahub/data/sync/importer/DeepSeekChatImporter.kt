package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

data class DeepSeekImportFailure(val sourceId: String, val reason: String)
data class DeepSeekImportResult(
    val imported: Int = 0, val skipped: Int = 0, val failed: Int = 0,
    val messages: Int = 0, val attachmentReferences: Int = 0,
    val failures: List<DeepSeekImportFailure> = emptyList(),
)
data class DeepSeekImportProgress(val total: Int, val completed: Int, val result: DeepSeekImportResult)
class DeepSeekImportCancelledException(val partialResult: DeepSeekImportResult) : CancellationException("聊天导入已取消；已完成的会话保留")
class DeepSeekImportException(val partialResult: DeepSeekImportResult) : IllegalArgumentException("聊天导入未完成；已完成的会话保留，现有聊天未覆盖")

internal interface DeepSeekImportSink {
    suspend fun exists(id: Uuid): Boolean
    /** Commit exactly one conversation atomically, returning false on an existing identity. */
    suspend fun insert(conversation: Conversation): Boolean
}

internal fun deepSeekImportId(kind: String, vararg identities: String): Uuid {
    val key = "orbis-deepseek-selected-v1/$kind/" + identities.joinToString("") { "${it.length}:$it" }
    return Uuid.parse(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString())
}

/** Explicit selected-path import. Other branches remain in the user's unmodified source ZIP. */
class DeepSeekChatImporter internal constructor(private val sink: DeepSeekImportSink) {
    constructor(repository: ConversationRepository) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) = repository.insertImportedConversations(listOf(conversation)) == 1
    })

    suspend fun import(
        file: File, assistantId: Uuid, selections: Map<String, String>,
        onProgress: (DeepSeekImportProgress) -> Unit = {},
    ): DeepSeekImportResult {
        var result = DeepSeekImportResult()
        try {
            return withContext(Dispatchers.IO) {
                importMutex.withLock {
                    val coroutine = currentCoroutineContext()
                    val checkCancelled = { coroutine.ensureActive() }
                    require(selections.isNotEmpty()) { "请选择至少一个 DeepSeek 会话分支" }
                    val signature = digest(file, checkCancelled)
                    // Validate the complete archive before the first write. Only lightweight previews survive.
                    val preview = DeepSeekArchive.inspect(file, checkCancelled)
                    val known = preview.conversations.associateBy { it.sourceId }
                    require(selections.all { (id, leaf) -> known[id]?.branches?.any { it.leafId == leaf } == true }) {
                        "所选 DeepSeek 会话或分支已变化，请重新预览"
                    }
                    require(signature == digest(file, checkCancelled)) { "DeepSeek 源文件已变化，请重新预览" }
                    var completed = 0
                    onProgress(DeepSeekImportProgress(selections.size, completed, result))
                    DeepSeekArchive.open(file, checkCancelled).use { archive ->
                        for (source in archive.conversations()) {
                            val leaf = selections[source.sourceId] ?: continue
                            checkCancelled()
                            val id = deepSeekImportId("conversation", source.sourceId, leaf)
                            if (sink.exists(id)) result = result.copy(skipped = result.skipped + 1)
                            else {
                                val converted = try { convert(source, leaf, assistantId, checkCancelled) }
                                catch (_: DeepSeekMessageTooLarge) {
                                    result = result.copy(failed = result.failed + 1, failures = result.failures +
                                        DeepSeekImportFailure(source.sourceId, "单条消息超过安全读取大小；该会话未导入，原文未截断"))
                                    null
                                }
                                if (converted != null) {
                                    checkCancelled()
                                    // Once a single-chat commit starts, finish it and account for it together.
                                    // Cancellation before/after this boundary never reports a committed chat as absent.
                                    withContext(NonCancellable) {
                                        val inserted = sink.insert(converted.conversation)
                                        if (inserted) result = result.copy(imported = result.imported + 1,
                                            messages = result.messages + converted.conversation.messageNodes.size,
                                            attachmentReferences = result.attachmentReferences + converted.attachmentReferences)
                                        else result = result.copy(skipped = result.skipped + 1)
                                    }
                                    checkCancelled()
                                }
                            }
                            completed++
                            onProgress(DeepSeekImportProgress(selections.size, completed, result))
                        }
                    }
                    result
                }
            }
        } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (_: Exception) { throw DeepSeekImportException(result) }
    }

    companion object {
        private val importMutex = Mutex()
        // Leave ample room below common 2 MiB CursorWindow rows; never truncate an oversized message.
        internal const val MAX_NODE_JSON_BYTES = 768 * 1024

        private fun digest(file: File, checkCancelled: () -> Unit): String {
            require(file.isFile && file.length() in 1..DeepSeekArchive.MAX_ARCHIVE_BYTES) { "deepseek_archive_size" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    checkCancelled()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= DeepSeekArchive.MAX_ARCHIVE_BYTES) { "deepseek_archive_size" }
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal data class Converted(val conversation: Conversation, val attachmentReferences: Int)
        internal fun convert(source: DeepSeekConversation, leaf: String, assistantId: Uuid,
            checkCancelled: () -> Unit = {}): Converted {
            var attachments = 0
            val nodes = source.pathTo(leaf, checkCancelled).map { node ->
                checkCancelled()
                val message = checkNotNull(node.message)
                val time = KotlinInstant.parse(message.createdAt.toString())
                val metadata = buildJsonObject {
                    put("import_source", "deepseek")
                    put("source_conversation_id", source.sourceId)
                    put("source_node_id", node.sourceId)
                    node.parentId?.let { put("source_parent_id", it) }
                    put("source_model", message.sourceModel)
                    put("source_timestamp", message.createdAt.toString())
                }
                val parts = buildList<UIMessagePart> {
                    message.fragments.forEach { fragment ->
                        checkCancelled()
                        when (fragment.type) {
                            "REQUEST", "RESPONSE" -> add(UIMessagePart.Text(checkNotNull(fragment.content)))
                            "THINK" -> add(UIMessagePart.Reasoning(checkNotNull(fragment.content), createdAt = time, finishedAt = time))
                            else -> {
                                // Keep historical content byte-for-byte as text; references remain inert data.
                                fragment.content?.let { add(UIMessagePart.Text(it)) }
                                val record = JsonObject(fragment.raw.filterKeys { it != "content" })
                                val label = if (fragment.type == "FILE") "历史附件引用；导出包未含附件，未下载" else "历史检索记录；未执行"
                                add(UIMessagePart.Text("[$label]\n$record"))
                                attachments += (fragment.raw["files"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                            }
                        }
                    }
                    // Keep an empty interrupted message and its source identity without inventing body text.
                    if (isEmpty()) add(UIMessagePart.Text(""))
                }.mapIndexed { index, part ->
                    if (index == 0) when (part) {
                        is UIMessagePart.Text -> part.copy(metadata = metadata)
                        is UIMessagePart.Reasoning -> part.copy(metadata = metadata)
                        else -> part
                    } else part
                }
                val ui = UIMessage(id = deepSeekImportId("message", source.sourceId, leaf, node.sourceId),
                    role = message.role, parts = parts, createdAt = time.toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishedAt = time.toLocalDateTime(TimeZone.currentSystemDefault()))
                if (JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size > MAX_NODE_JSON_BYTES)
                    throw DeepSeekMessageTooLarge()
                MessageNode(id = deepSeekImportId("node", source.sourceId, leaf, node.sourceId), messages = listOf(ui))
            }
            return Converted(Conversation(id = deepSeekImportId("conversation", source.sourceId, leaf),
                assistantId = assistantId, title = source.title, createAt = source.createdAt, updateAt = source.updatedAt,
                messageNodes = nodes), attachments)
        }
    }
    private class DeepSeekMessageTooLarge : IllegalArgumentException()
}
