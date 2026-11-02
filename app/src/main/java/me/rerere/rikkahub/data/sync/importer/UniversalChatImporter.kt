package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

/** Explicit conversion contract; the existing sink appends each window in one DB transaction. */
class UniversalChatImporter internal constructor(private val sink: DeepSeekImportSink) {
    constructor(repository: ConversationRepository) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) = repository.insertImportedConversations(listOf(conversation)) == 1
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
                    require(selections.isNotEmpty() && selections.values.all { it == UniversalChatArchive.PATH })
                    ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace, file.length())
                    val snapshot = File.createTempFile("orbis-universal-", ".json", file.parentFile)
                    try {
                        file.inputStream().use { input -> snapshot.outputStream().use { output ->
                            RikkaChatArchive.copyLimited(input, output, UniversalChatArchive.MAX_ARCHIVE_BYTES, checkCancelled,
                                beforeWrite = { count -> RikkaChatArchive.requireExtractionSpace(snapshot.parentFile!!.usableSpace, count) })
                        } }
                        if (archiveFingerprint(snapshot, checkCancelled) != expectedFingerprint)
                            throw UniversalChatFormatException("文件", "与刚才预览相同的内容，请重新预览")
                        val importedAt = Instant.now()
                        // Inspect all windows, including late envelope validation and serialized row
                        // limits, before writing anything. Subsequent passes use this immutable copy.
                        val preview = UniversalChatArchive.inspect(snapshot, checkCancelled, importedAt)
                        val known = preview.conversations.mapTo(hashSetOf()) { it.sourceId }
                        if (!known.containsAll(selections.keys)) throw UniversalChatFormatException("选择", "预览中实际存在的会话")
                        var completed = 0
                        onProgress(DeepSeekImportProgress(selections.size, 0, result))
                        UniversalChatArchive.open(snapshot, checkCancelled).use { archive ->
                            for (source in archive.conversations()) {
                                if (source.id !in selections) continue
                                checkCancelled()
                                val id = identity("conversation", assistantId.toString(), source.id, source.contentVersion)
                                if (sink.exists(id)) result = result.copy(skipped = result.skipped + 1)
                                else {
                                    ArchiveCapacity.requireSpace(snapshot.parentFile!!.usableSpace, ArchiveCapacity.MAX_WINDOW_CHARS.toLong())
                                    val conversation = convert(source, assistantId, importedAt, checkCancelled)
                                    checkCancelled()
                                    withContext(NonCancellable) {
                                        result = if (sink.insert(conversation)) result.copy(imported = result.imported + 1,
                                            messages = result.messages + conversation.messageNodes.size)
                                        else result.copy(skipped = result.skipped + 1)
                                    }
                                    checkCancelled()
                                }
                                completed++
                                onProgress(DeepSeekImportProgress(selections.size, completed, result))
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
        fun archiveFingerprint(file: File, checkCancelled: () -> Unit = {}): String =
            ClaudeChatImporter.archiveFingerprint(file, checkCancelled)

        private fun identity(kind: String, vararg values: String): Uuid = Uuid.parse(UUID.nameUUIDFromBytes(
            ("orbis-universal-chat-v1/$kind/" + values.joinToString("") { "${it.length}:$it" }).toByteArray(Charsets.UTF_8)).toString())

        internal fun convert(source: UniversalConversation, assistantId: Uuid, importedAt: Instant,
            checkCancelled: () -> Unit = {}): Conversation {
            val id = identity("conversation", assistantId.toString(), source.id, source.contentVersion)
            var serialized = 0L
            val nodes = source.messages.mapIndexed { index, message ->
                checkCancelled()
                val metadata = buildJsonObject {
                    put("import_source", "orbis_universal_v1"); put("source_conversation_id", source.id)
                    put("source_message_index", index); put("source_content_version", source.contentVersion)
                    put("source_time_missing", message.timestamp == null)
                    message.timestamp?.let { put("source_created_at", it.toString()) }
                }
                val at = kotlin.time.Instant.parse((message.timestamp ?: importedAt).toString())
                val parts = buildList<UIMessagePart> {
                    if (message.reasoning.isNotEmpty()) add(UIMessagePart.Reasoning(message.reasoning, at, at, metadata))
                    add(UIMessagePart.Text(message.content, metadata = metadata))
                }
                val ui = UIMessage(id = identity("message", id.toString(), index.toString()),
                    role = if (message.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
                    parts = parts, createdAt = at.toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishedAt = at.toLocalDateTime(TimeZone.currentSystemDefault()))
                val size = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
                if (size > DeepSeekChatImporter.MAX_NODE_JSON_BYTES)
                    throw UniversalChatFormatException("${source.location}.messages[$index]", "序列化后不超过1MiB的单条消息；请按原顺序拆成相同role的连续消息")
                serialized += size
                if (serialized > ArchiveCapacity.MAX_WINDOW_CHARS) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                MessageNode(id = identity("node", id.toString(), index.toString()), messages = listOf(ui))
            }
            val times = source.messages.mapNotNull { it.timestamp }
            return Conversation(id = id, assistantId = assistantId, title = source.title,
                messageNodes = nodes, createAt = times.minOrNull() ?: importedAt, updateAt = times.maxOrNull() ?: importedAt)
        }
    }
}
