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
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessageAnnotation
import kotlin.time.Duration.Companion.seconds
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
    val skippedSummaries: Int = 0,
    val restoredAttachments: Int = 0,
)
data class DeepSeekImportProgress(val total: Int, val completed: Int, val result: DeepSeekImportResult)
class DeepSeekImportCancelledException(val partialResult: DeepSeekImportResult) : CancellationException("聊天导入已取消；已完成的会话保留")
class DeepSeekImportException(val partialResult: DeepSeekImportResult, val publicDetail: String? = null,
    val failureReason: ArchiveFailure? = null) :
    IllegalArgumentException("聊天导入未完成；已完成的会话保留，现有聊天未覆盖")

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
class DeepSeekChatImporter internal constructor(private val sink: DeepSeekImportSink,
    private val attachmentStore: DeepSeekAttachmentStore? = null) {
    constructor(repository: ConversationRepository, files: me.rerere.rikkahub.data.files.FilesManager? = null, cacheDir: File? = null) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) = repository.insertImportedConversations(listOf(conversation)) == 1
    }, if (files != null && cacheDir != null) ManagedDeepSeekAttachmentStore(files, cacheDir) else null)

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
                                ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace)
                                val attachments = DeepSeekAttachmentSession(archive, attachmentStore)
                                var committed = false
                                try {
                                val converted = try {
                                    val local = attachments.restore(source.pathTo(leaf, checkCancelled), checkCancelled)
                                    convert(source, leaf, assistantId, local, checkCancelled)
                                }
                                catch (_: DeepSeekMessageTooLarge) {
                                    result = result.copy(failed = result.failed + 1, failures = result.failures +
                                        DeepSeekImportFailure(source.sourceId, "单条消息超过安全读取大小；该会话未导入，原文未截断"))
                                    null
                                }
                                if (converted != null) {
                                    checkCancelled()
                                    ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace, converted.serializedBytes * 2)
                                    // Once a single-chat commit starts, finish it and account for it together.
                                    // Cancellation before/after this boundary never reports a committed chat as absent.
                                    withContext(NonCancellable) {
                                        val inserted = sink.insert(converted.conversation)
                                        committed = inserted
                                        if (inserted) result = result.copy(imported = result.imported + 1,
                                            messages = result.messages + converted.conversation.messageNodes.size,
                                            attachmentReferences = result.attachmentReferences + converted.attachmentReferences,
                                            restoredAttachments = result.restoredAttachments + converted.restoredAttachments)
                                        else result = result.copy(skipped = result.skipped + 1)
                                    }
                                    checkCancelled()
                                }
                                } finally { if (!committed) withContext(NonCancellable) { attachments.rollback() } }
                            }
                            completed++
                            onProgress(DeepSeekImportProgress(selections.size, completed, result))
                        }
                    }
                    result
                }
            }
        } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (failure: Exception) { throw DeepSeekImportException(result, ArchiveCapacity.publicError(failure), ArchiveCapacity.reasonOf(failure)) }
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

        internal data class Converted(val conversation: Conversation, val attachmentReferences: Int, val serializedBytes: Long = 0, val restoredAttachments: Int = 0)
        internal fun convert(source: DeepSeekConversation, leaf: String, assistantId: Uuid,
            localAttachments: Map<String, UIMessagePart> = emptyMap(), checkCancelled: () -> Unit = {}): Converted {
            var attachments = 0
            var restored = 0
            var serializedBytes = 0L
            val nodes = source.pathTo(leaf, checkCancelled).flatMap { node ->
                checkCancelled()
                val message = checkNotNull(node.message)
                val time = KotlinInstant.parse(message.createdAt.toString())
                val metadata = buildJsonObject {
                    put("import_source", "deepseek")
                    put("source_conversation_id", source.sourceId)
                    put("source_node_id", node.sourceId)
                    node.parentId?.let { put("source_parent_id", it) }
                    put("source_model", message.sourceModel)
                    if (message.sourceMetadata.isNotEmpty()) put("source_message_metadata", message.sourceMetadata)
                    if (message.sourceMetadata["source_timestamp_unknown"] == JsonPrimitive(true)) {
                        put("source_timestamp_unknown", true)
                        put("source_timestamp_raw", message.sourceMetadata["source_timestamp_raw"] ?: JsonNull)
                    } else put("source_timestamp", message.createdAt.toString())
                }
                val citations = mutableListOf<UIMessageAnnotation.UrlCitation>()
                val parts = buildList<UIMessagePart> {
                    message.fragments.forEach { fragment ->
                        checkCancelled()
                        when (fragment.type) {
                            "REQUEST", "RESPONSE" -> add(UIMessagePart.Text(fragment.content ?: "[DeepSeek 历史片段；原样保留]\n${fragment.raw}"))
                            "FILE" -> {
                                val files = fragment.raw["files"] as? kotlinx.serialization.json.JsonArray
                                files.orEmpty().forEach { entry ->
                                    attachments++
                                    val record = entry as JsonObject
                                    val path = (record["export_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                    val part = path?.let(localAttachments::get)
                                    if (part != null) { add(part); restored++ }
                                    else add(UIMessagePart.Text("[历史附件引用；原包未包含可恢复文件，未联网下载]\n$record"))
                                }
                                if (files.isNullOrEmpty()) add(UIMessagePart.Text("[历史附件引用；未下载]\n${fragment.raw}"))
                            }
                            "THINK" -> if (fragment.content != null) {
                                val elapsed = (fragment.raw["elapsed_secs"] as? JsonPrimitive)?.doubleOrNull
                                    ?.takeIf { it.isFinite() && it >= 0 && it <= 315360000.0 }
                                add(UIMessagePart.Reasoning(fragment.content!!, createdAt = time,
                                    finishedAt = time + (elapsed ?: 0.0).seconds,
                                    metadata = buildJsonObject { put("source_fragment", JsonObject(fragment.raw.filterKeys { it != "content" })) }))
                            } else add(UIMessagePart.Text("[DeepSeek 历史片段；原样保留]\n${fragment.raw}"))
                            else -> {
                                // Keep historical content byte-for-byte as text; references remain inert data.
                                fragment.content?.let { add(UIMessagePart.Text(it)) }
                                val record = JsonObject(fragment.raw.filterKeys { it != "content" || fragment.content == null })
                                val label = when (fragment.type) {
                                    "TEMPLATE_RESPONSE", "CONTENT_RISK", "TIP" -> "DeepSeek 历史提示；原样保留"
                                    "SEARCH", "TOOL_SEARCH", "TOOL_OPEN", "TOOL_FIND", "TOOL_IMAGE_SEARCH", "READ_LINK" -> "历史检索记录；未执行"
                                    "PARTIAL_FILE_READ" -> "历史文件读取提示；原样保留"
                                    else -> "DeepSeek 历史片段；原样保留，未执行"
                                }
                                val historyTool = fragment.type in setOf("SEARCH", "TOOL_SEARCH", "TOOL_OPEN", "TOOL_FIND", "TOOL_IMAGE_SEARCH", "READ_LINK")
                                add(UIMessagePart.Text("[$label]\n$record", metadata = if (historyTool && record.toString().length <= 32768) buildJsonObject {
                                    put("import_source", "deepseek"); put("deepseek_history_tool", record)
                                } else null))
                                if (historyTool) {
                                    val sources = listOf(fragment.raw) + (fragment.raw["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                                    sources.forEach { item ->
                                        val url = (item["url"] as? JsonPrimitive)?.content.orEmpty()
                                        if (url.startsWith("https://") || url.startsWith("http://")) citations += UIMessageAnnotation.UrlCitation(
                                            title = (item["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: url, url = url)
                                    }
                                }
                                attachments += (fragment.raw["files"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                            }
                        }
                    }
                    // Keep an empty interrupted message and its source identity without inventing body text.
                    if (isEmpty()) add(UIMessagePart.Text(""))
                }.mapIndexed { index, part ->
                    if (index == 0) when (part) {
                        is UIMessagePart.Text -> part.copy(metadata = JsonObject(part.metadata.orEmpty() + metadata))
                        is UIMessagePart.Reasoning -> part.copy(metadata = JsonObject(part.metadata.orEmpty() + metadata))
                        is UIMessagePart.Image -> part.copy(metadata = JsonObject(part.metadata.orEmpty() + metadata))
                        is UIMessagePart.Document -> part.copy(metadata = JsonObject(part.metadata.orEmpty() + metadata))
                        else -> part
                    } else part
                }
                val ui = UIMessage(id = deepSeekImportId("message", source.sourceId, leaf, node.sourceId),
                    role = message.role, parts = parts, annotations = citations.distinctBy { it.url }, createdAt = time.toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishedAt = time.toLocalDateTime(TimeZone.currentSystemDefault()))
                partitionDeepSeekMessage(ui, source.sourceId, leaf, node.sourceId, checkCancelled).also { segments ->
                    segments.forEach { serializedBytes += JsonInstant.encodeToString(it.messages).toByteArray(Charsets.UTF_8).size }
                }
            }
            return Converted(Conversation(id = deepSeekImportId("conversation", source.sourceId, leaf),
                assistantId = assistantId, title = source.title, createAt = source.createdAt, updateAt = source.updatedAt,
                messageNodes = nodes, isPinned = source.original["pinned"] == JsonPrimitive(true)), attachments, serializedBytes, restored)
        }
    }
    private class DeepSeekMessageTooLarge : IllegalArgumentException()
}
