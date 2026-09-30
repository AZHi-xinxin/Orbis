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
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid

internal fun codexImportId(kind: String, session: String, source: String = ""): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("orbis-codex-chat-v1/$kind/${session.length}:$session/${source.length}:$source"
        .toByteArray(Charsets.UTF_8)).toString())

/** Original rollout text only. No source settings, tools, system prompt, attachments or permissions. */
internal class CodexChatImporter(private val sink: DeepSeekImportSink) {
    constructor(repository: ConversationRepository) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) =
            repository.insertImportedConversations(listOf(conversation)) == 1
    })

    suspend fun import(file: File, assistantId: Uuid, sourceId: String, expectedFingerprint: String,
        onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        var result = DeepSeekImportResult()
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val coroutine = currentCoroutineContext()
                    val checkCancelled = { coroutine.ensureActive() }
                    require(archiveFingerprint(file, checkCancelled) == expectedFingerprint)
                    val source = file.inputStream().use { CodexChatArchive.parse(it, checkCancelled = checkCancelled) }
                    require(source.sourceSessionId == sourceId && archiveFingerprint(file, checkCancelled) == expectedFingerprint)
                    onProgress(DeepSeekImportProgress(1, 0, result))
                    val id = codexImportId("conversation", sourceId)
                    if (sink.exists(id)) result = result.copy(skipped = 1)
                    else {
                        val converted = convert(source, assistantId, checkCancelled)
                        checkCancelled()
                        withContext(NonCancellable) {
                            result = if (sink.insert(converted)) result.copy(imported = 1,
                                messages = converted.messageNodes.size, attachmentReferences = source.omittedAttachmentCount)
                            else result.copy(skipped = 1)
                        }
                        checkCancelled()
                    }
                    onProgress(DeepSeekImportProgress(1, 1, result))
                    result
                }
            }
        } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (_: Exception) { throw DeepSeekImportException(result) }
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        private val mutex = Mutex()

        fun title(source: CodexChatArchiveData): String = source.messages.firstOrNull { it.role == CodexArchiveRole.USER }
            ?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80) ?: "Codex ${source.sourceSessionId.take(8)}"

        fun archiveFingerprint(file: File, checkCancelled: () -> Unit = {}): String {
            require(file.isFile && file.length() in 1..MAX_ARCHIVE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    checkCancelled()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_ARCHIVE_BYTES)
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        internal fun convert(source: CodexChatArchiveData, assistantId: Uuid,
            checkCancelled: () -> Unit = {}): Conversation {
            val nodes = source.messages.map { message ->
                checkCancelled()
                val metadata = buildJsonObject {
                    put("import_source", "codex_original_jsonl")
                    put("source_session_id", source.sourceSessionId)
                    put("source_line", message.sourceLine)
                    put("source_timestamp", message.timestamp.toString())
                    put("source_kind", message.sourceKind.name)
                    message.sourceMessageId?.let { put("source_message_id", it) }
                    message.channel?.let { put("source_channel", it) }
                    put("omitted_attachments", message.omittedAttachmentCount)
                }
                val time = kotlin.time.Instant.parse(message.timestamp.toString()).toLocalDateTime(TimeZone.currentSystemDefault())
                val ui = UIMessage(id = codexImportId("message", source.sourceSessionId, message.sourceLine.toString()),
                    role = if (message.role == CodexArchiveRole.USER) MessageRole.USER else MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(message.text, metadata = metadata)) +
                        if (message.omittedAttachmentCount > 0) listOf(UIMessagePart.Text(
                            "[原记录含 ${message.omittedAttachmentCount} 个图片/附件引用；本次仅导入文字，未读取或下载附件]")) else emptyList(),
                    createdAt = time, finishedAt = time)
                require(JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size <= 768 * 1024) {
                    "message_too_large"
                }
                MessageNode(id = codexImportId("node", source.sourceSessionId, message.sourceLine.toString()), messages = listOf(ui))
            }
            return Conversation(id = codexImportId("conversation", source.sourceSessionId), assistantId = assistantId,
                title = title(source), createAt = source.createdAt,
                updateAt = source.messages.lastOrNull()?.timestamp ?: source.createdAt, messageNodes = nodes)
        }
    }
}
