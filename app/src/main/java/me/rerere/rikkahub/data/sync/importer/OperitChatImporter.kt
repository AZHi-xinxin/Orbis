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

internal fun operitImportId(kind: String, sourceId: String, message: String = ""): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("orbis-operit-v2/$kind/${sourceId.length}:$sourceId/${message.length}:$message"
        .toByteArray(Charsets.UTF_8)).toString())

/** Append-only selected-answer import. No tools, settings, credentials, prompts, workspace or media execution. */
class OperitChatImporter internal constructor(private val sink: DeepSeekImportSink) {
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
                    require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) { "请先预览 Operit 聊天文件" }
                    require(selections.isNotEmpty() && selections.values.all {
                        it == OperitChatArchive.SELECTED_PATH || it == OperitChatArchive.CORRECTED_COPY_PATH
                    })
                    // Freeze the explicitly previewed bytes before validating/committing; a source file changed
                    // during or after preview cannot alter a partly committed import. Only this private temp is deleted.
                    ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace, file.length())
                    val snapshot = File.createTempFile("orbis-operit-", ".json", file.parentFile)
                    try {
                        file.inputStream().use { input -> snapshot.outputStream().use { output ->
                            RikkaChatArchive.copyLimited(input, output, OperitChatArchive.MAX_ARCHIVE_BYTES, checkCancelled,
                                beforeWrite = { count -> RikkaChatArchive.requireExtractionSpace(snapshot.parentFile!!.usableSpace, count) })
                        } }
                        require(archiveFingerprint(snapshot, checkCancelled) == expectedFingerprint) {
                            "Operit 源文件已变化，请重新预览"
                        }
                        val preview = OperitChatArchive.inspect(snapshot, checkCancelled)
                        val known = preview.conversations.mapTo(hashSetOf()) { it.sourceId }
                        require(selections.keys.all { it in known }) { "Operit 所选会话已变化，请重新预览" }
                        var completed = 0
                        onProgress(DeepSeekImportProgress(selections.size, completed, result))
                        OperitChatArchive.open(snapshot, checkCancelled).use { archive ->
                            for (source in archive.conversations()) {
                                if (source.sourceId !in selections) continue
                                checkCancelled()
                                val correctedCopy = selections[source.sourceId] == OperitChatArchive.CORRECTED_COPY_PATH
                                val namespace = if (correctedCopy) "${source.sourceId}/corrected-v1/$expectedFingerprint" else source.sourceId
                                val id = operitImportId(if (correctedCopy) "conversation-corrected-v1" else "conversation", namespace)
                                if (sink.exists(id)) result = result.copy(skipped = result.skipped + 1)
                                else {
                                    ArchiveCapacity.requireSpace(snapshot.parentFile!!.usableSpace, 2L * ArchiveCapacity.MAX_WINDOW_CHARS)
                                    val converted = try { convert(source, assistantId, checkCancelled, namespace, correctedCopy) }
                                    catch (_: OperitMessageTooLarge) {
                                        result = result.copy(failed = result.failed + 1, failures = result.failures +
                                            DeepSeekImportFailure(source.sourceId, "单条消息超过安全读取大小；该会话未导入，原文未截断"))
                                        null
                                    }
                                    if (converted != null) {
                                        checkCancelled()
                                        withContext(NonCancellable) {
                                            result = if (sink.insert(converted)) result.copy(imported = result.imported + 1,
                                                messages = result.messages + converted.messageNodes.size,
                                                skippedSummaries = result.skippedSummaries + source.omittedSummaryCount,
                                                attachmentReferences = result.attachmentReferences + source.messages.sumOf { it.attachmentReferences })
                                            else result.copy(skipped = result.skipped + 1)
                                        }
                                        checkCancelled()
                                    }
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
        fun archiveFingerprint(file: File, checkCancelled: () -> Unit = {}): String {
            require(file.isFile && file.length() in 1..OperitChatArchive.MAX_ARCHIVE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var bytes = 0L
                while (true) {
                    checkCancelled()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= OperitChatArchive.MAX_ARCHIVE_BYTES)
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        internal fun convert(source: OperitConversation, assistantId: Uuid,
            checkCancelled: () -> Unit = {}, idNamespace: String = source.sourceId,
            correctedCopy: Boolean = false): Conversation {
            fun importedId(kind: String, index: String = "") = operitImportId(
                if (correctedCopy) "$kind-corrected-v1" else kind, idNamespace, index)
            var serializedBytes = 0L
            val nodes = source.messages.map { message ->
                checkCancelled()
                val split = splitOperitReasoningContent(message.text, message.sender, checkCancelled)
                val metadata = buildJsonObject {
                    put("import_source", "operit_json_v2")
                    put("source_conversation_id", source.sourceId)
                    put("source_message_index", message.sourceIndex)
                    put("source_timestamp_ms", message.timestamp.toEpochMilli())
                    put("source_selected_variant", message.selectedVariant)
                    put("source_variant_count", message.variantCount)
                    put("source_role_name", message.roleName)
                    put("source_model", message.sourceModel)
                    put("source_created_at_local", source.sourceCreatedAt)
                    put("source_updated_at_local", source.sourceUpdatedAt)
                    put("source_timezone_assumption", source.assumedTimeZone)
                    put("omitted_attachment_references", message.attachmentReferences)
                    if (split.slices.any { it.reasoning }) put("import_reasoning_format", "operit_think_v1")
                    if (split.ambiguous) put("import_reasoning_unparsed", true)
                    if (correctedCopy) put("import_corrected_copy", true)
                }
                val time = kotlin.time.Instant.parse(message.timestamp.toString()).toLocalDateTime(TimeZone.currentSystemDefault())
                val parts = split.slices.map { slice ->
                    if (slice.reasoning) UIMessagePart.Reasoning(slice.text,
                        kotlin.time.Instant.parse(message.timestamp.toString()),
                        kotlin.time.Instant.parse(message.timestamp.toString()), metadata)
                    else UIMessagePart.Text(slice.text, metadata = metadata)
                } +
                    if (message.attachmentReferences > 0) listOf(UIMessagePart.Text(
                        "[Operit 历史附件引用；本次仅导入文字，未读取或下载附件]", metadata = metadata)) else emptyList()
                val ui = UIMessage(id = importedId("message", message.sourceIndex.toString()),
                    role = if (message.sender == "user") MessageRole.USER else MessageRole.ASSISTANT,
                    parts = parts, createdAt = time, finishedAt = time)
                val nodeBytes = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
                if (nodeBytes > DeepSeekChatImporter.MAX_NODE_JSON_BYTES)
                    throw OperitMessageTooLarge()
                serializedBytes += nodeBytes
                if (serializedBytes > ArchiveCapacity.MAX_WINDOW_CHARS) throw ArchiveReadException(ArchiveFailure.WINDOW_LIMIT)
                MessageNode(id = importedId("node", message.sourceIndex.toString()), messages = listOf(ui))
            }
            return Conversation(id = importedId("conversation"), assistantId = assistantId,
                title = source.title + if (correctedCopy) "（整理副本）" else "",
                messageNodes = nodes, createAt = source.createdAt, updateAt = source.updatedAt)
        }
    }

    private class OperitMessageTooLarge : IllegalArgumentException()
}
