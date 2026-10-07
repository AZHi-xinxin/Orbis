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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid

internal fun claudeImportId(kind: String, vararg identities: String): Uuid = Uuid.parse(UUID.nameUUIDFromBytes(
    ("orbis-claude-path-v1/$kind/" + identities.joinToString("") { "${it.length}:$it" })
        .toByteArray(Charsets.UTF_8)).toString())

/** Additive history only. Each selected path commits atomically; it never routes a model/tool call. */
class ClaudeChatImporter internal constructor(private val sink: DeepSeekImportSink) {
    constructor(repository: ConversationRepository) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) =
            repository.insertImportedConversations(listOf(conversation)) == 1
    })

    suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>, expectedFingerprint: String,
        onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        var result = DeepSeekImportResult()
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val coroutine = currentCoroutineContext()
                    val checkCancelled = { coroutine.ensureActive() }
                    require(expectedFingerprint.matches(Regex("[0-9a-f]{64}")))
                    require(selections.isNotEmpty() && selections.values.all { it == ClaudeChatArchive.SELECTED_PATH })
                    ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace, file.length())
                    val snapshot = File.createTempFile("orbis-claude-", ".json", file.parentFile)
                    try {
                        file.inputStream().use { input -> snapshot.outputStream().use { output ->
                            RikkaChatArchive.copyLimited(input, output, ClaudeChatArchive.MAX_ARCHIVE_BYTES, checkCancelled,
                                beforeWrite = { count -> RikkaChatArchive.requireExtractionSpace(snapshot.parentFile!!.usableSpace, count) })
                        } }
                        require(archiveFingerprint(snapshot, checkCancelled) == expectedFingerprint) { "claude_preview_changed" }
                        // Validate the complete immutable archive before any path is committed.
                        val preview = ClaudeChatArchive.inspect(snapshot, checkCancelled)
                        val known = preview.conversations.mapTo(hashSetOf()) { it.sourceId }
                        require(selections.keys.all { it in known }) { "claude_unknown_selection" }
                        // Predictable conversion/serialized-row limits are validation errors too.
                        // Preflight every selected path before the first sink write, one path at a time.
                        // The second pass reads the same private immutable bytes; no full archive of
                        // converted Conversations is retained and no model/tool action is possible.
                        ClaudeChatArchive.open(snapshot, checkCancelled).use { archive ->
                            for (source in archive.conversations()) {
                                for ((index, path) in source.paths.withIndex()) {
                                    if (ClaudeChatArchive.selectionKey(source.sourceId, path.leafId) !in selections) continue
                                    checkCancelled()
                                    convert(source, path, index, assistantId, checkCancelled)
                                }
                            }
                        }
                        var completed = 0
                        onProgress(DeepSeekImportProgress(selections.size, 0, result))
                        ClaudeChatArchive.open(snapshot, checkCancelled).use { archive ->
                            for (source in archive.conversations()) {
                                for ((index, path) in source.paths.withIndex()) {
                                    val key = ClaudeChatArchive.selectionKey(source.sourceId, path.leafId)
                                    if (key !in selections) continue
                                    checkCancelled()
                                    val id = claudeImportId("conversation", source.sourceId, path.leafId, source.contentVersion)
                                    if (sink.exists(id)) result = result.copy(skipped = result.skipped + 1)
                                    else {
                                        ArchiveCapacity.requireSpace(snapshot.parentFile!!.usableSpace, 2L * ArchiveCapacity.MAX_WINDOW_CHARS)
                                        val converted = convert(source, path, index, assistantId, checkCancelled)
                                        checkCancelled()
                                        withContext(NonCancellable) {
                                            result = if (sink.insert(converted.conversation)) result.copy(
                                                imported = result.imported + 1,
                                                messages = result.messages + converted.conversation.messageNodes.size,
                                                attachmentReferences = result.attachmentReferences + converted.attachmentReferences)
                                            else result.copy(skipped = result.skipped + 1)
                                        }
                                        checkCancelled()
                                    }
                                    completed++
                                    onProgress(DeepSeekImportProgress(selections.size, completed, result))
                                }
                            }
                        }
                        result
                    } finally { snapshot.delete() }
                }
            }
        } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (failure: Exception) { throw DeepSeekImportException(result, ArchiveCapacity.publicError(failure), ArchiveCapacity.reasonOf(failure)) }
    }

    companion object {
        private val mutex = Mutex()
        fun archiveFingerprint(file: File, checkCancelled: () -> Unit = {}): String {
            require(file.isFile)
            ArchiveCapacity.requireSize(file.length(), ClaudeChatArchive.MAX_ARCHIVE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var bytes = 0L
                while (true) {
                    checkCancelled()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes += count
                    ArchiveCapacity.requireSize(bytes, ClaudeChatArchive.MAX_ARCHIVE_BYTES)
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        internal data class Converted(val conversation: Conversation, val attachmentReferences: Int)
        internal fun convert(source: ClaudeHistoryConversation, path: ClaudeHistoryPath, pathIndex: Int,
            assistantId: Uuid, checkCancelled: () -> Unit = {}): Converted {
            var serializedBytes = 0L
            var attachments = 0
            val nodes = source.pathTo(path, checkCancelled).mapIndexed { index, message ->
                checkCancelled()
                val metadata = buildJsonObject {
                    put("import_source", "claude_export_v1")
                    put("source_conversation_id", source.sourceId)
                    put("source_message_id", message.sourceId)
                    message.parentId?.let { put("source_parent_id", it) }
                    put("source_message_index", message.sourceIndex)
                    put("source_path_leaf", path.leafId)
                    put("source_content_version", source.contentVersion)
                    put("source_created_at", message.createdAt.toString())
                    put("source_updated_at", message.updatedAt.toString())
                    if (index == 0 && path.missingParent) put("source_parent_missing", true)
                    if (message.differentTextCopy) put("source_text_copy_preserved", true)
                }
                val created = kotlin.time.Instant.parse(message.createdAt.toString())
                val finished = kotlin.time.Instant.parse(message.updatedAt.toString())
                val parts = buildList<UIMessagePart> {
                    if (index == 0 && path.missingParent) add(UIMessagePart.Text(
                        "[Claude 导出未包含此路径的父消息；从现有内容开始，未猜补前文]\n", metadata = metadata))
                    message.parts.forEach { part ->
                        if (part.reasoning) add(UIMessagePart.Reasoning(part.text, created, finished, metadata))
                        else add(UIMessagePart.Text(part.text, metadata = metadata))
                    }
                }
                val ui = UIMessage(id = claudeImportId("message", source.sourceId, path.leafId, source.contentVersion, message.sourceId),
                    role = if (message.sender == "human") MessageRole.USER else MessageRole.ASSISTANT,
                    parts = parts, createdAt = created.toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishedAt = finished.toLocalDateTime(TimeZone.currentSystemDefault()))
                val bytes = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
                if (bytes > DeepSeekChatImporter.MAX_NODE_JSON_BYTES) throw ArchiveReadException(ArchiveFailure.NODE_LIMIT)
                serializedBytes += bytes
                if (serializedBytes > ArchiveCapacity.MAX_WINDOW_CHARS) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                attachments += message.attachmentReferences
                MessageNode(id = claudeImportId("node", source.sourceId, path.leafId, source.contentVersion, message.sourceId), messages = listOf(ui))
            }
            return Converted(Conversation(id = claudeImportId("conversation", source.sourceId, path.leafId, source.contentVersion),
                assistantId = assistantId, title = source.title + if (source.paths.size > 1) " · 路径 ${pathIndex + 1}/${source.paths.size}" else "",
                messageNodes = nodes, createAt = source.createdAt, updateAt = source.updatedAt), attachments)
        }
    }
}
