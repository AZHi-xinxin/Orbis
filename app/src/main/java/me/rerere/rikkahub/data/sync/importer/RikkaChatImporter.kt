package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.DatabaseBackup
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.uuid.Uuid

internal fun rikkaImportId(kind: String, source: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("orbis-rikka-chat-v1/$kind/$source".toByteArray(Charsets.UTF_8)).toString())

/** Import is additive and idempotent; it never installs settings/assistants or overwrites a live DB. */
class RikkaChatImporter(private val context: Context, private val repository: ConversationRepository,
    private val files: FilesManager) {
    /** Only private staging is extracted; settings and credentials are never read. */
    suspend fun inspect(archive: File): RikkaChatPreview = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val checkCancelled = { coroutine.ensureActive() }
        withSnapshot(archive, null, checkCancelled) { incoming, _, fingerprint ->
            inspectRikkaSource(incoming, fingerprint, checkCancelled)
        }
    }

    suspend fun importSelected(archive: File, assistantId: Uuid, selections: Set<String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        var accountedResult = DeepSeekImportResult()
        try {
            return withContext(Dispatchers.IO) {
                importMutex.withLock {
                    val coroutine = currentCoroutineContext()
                    val checkCancelled = { coroutine.ensureActive() }
                    require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) { "请先预览 Rikka 导出包" }
                    withSnapshot(archive, expectedFingerprint, checkCancelled) { incoming, staging, fingerprint ->
                        // Preflight every bounded row before committing the first selected window.
                        val preview = inspectRikkaSource(incoming, fingerprint, checkCancelled)
                        importSelectedRikkaChats(incoming, preview, selections, object : DeepSeekImportSink {
                            override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
                            override suspend fun insert(conversation: Conversation): Boolean {
                                ArchiveCapacity.requireSpace(context.filesDir.usableSpace, 2 * RikkaChatLimits.MAX_WINDOW_BYTES)
                                return repository.insertImportedConversations(listOf(conversation)) == 1
                            }
                        }, prepare = { chat ->
                            val attachments = AttachmentSession(staging)
                            try {
                                val conversation = convertRikkaChat(incoming, chat, assistantId, attachments::mapPart, checkCancelled)
                                PreparedRikkaChat(conversation, attachments.createdFiles.size + attachments.missing,
                                    attachments::rollback)
                            } catch (failure: Throwable) {
                                withContext(NonCancellable) { attachments.rollback() }
                                throw failure
                            }
                        }, onProgress = { progress ->
                            accountedResult = progress.result
                            onProgress(progress)
                        }).also { accountedResult = it }
                    }
                }
            }
        } catch (cancelled: DeepSeekImportCancelledException) { throw cancelled }
        catch (failed: DeepSeekImportException) { throw failed }
        catch (_: CancellationException) { throw DeepSeekImportCancelledException(accountedResult) }
        catch (failure: Exception) { throw DeepSeekImportException(accountedResult, ArchiveCapacity.publicError(failure), ArchiveCapacity.reasonOf(failure)) }
    }

    // Phone import validates every window first, then commits one window at a time. It must not
    // retain the entire archive in RAM. A later failure reports retained progress explicitly.
    suspend fun import(archive: File, assistantId: Uuid,
        onProgress: (RikkaChatImportResult) -> Unit = {}): RikkaChatImportResult = accountRikkaWholeImport(onProgress = onProgress) { accounting ->
        importMutex.withLock {
            val coroutine = currentCoroutineContext()
            val checkCancelled = { coroutine.ensureActive() }
            withSnapshot(archive, null, checkCancelled) { incoming, staging, _ ->
                inspectRikkaSource(incoming, "local-phone-preflight", checkCancelled)
                for (chat in incoming.conversations()) {
                    checkCancelled()
                    if (repository.existsConversationById(rikkaImportId("conversation", chat.id))) accounting.skipped()
                    else {
                        val attachments = AttachmentSession(staging)
                        var committed = false
                        try {
                            ArchiveCapacity.requireSpace(context.filesDir.usableSpace, RikkaChatLimits.MAX_WINDOW_BYTES)
                            val conversation = convertRikkaChat(incoming, chat, assistantId, attachments::mapPart, checkCancelled)
                            withContext(NonCancellable) {
                                if (repository.insertImportedConversations(listOf(conversation)) == 1) {
                                    committed = true
                                    accounting.imported(attachments.createdFiles.size, attachments.missing)
                                } else accounting.skipped()
                            }
                        } finally {
                            if (!committed) withContext(NonCancellable) { attachments.rollback() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> withSnapshot(archive: File, expectedFingerprint: String?, checkCancelled: () -> Unit,
        block: suspend (RikkaChatSnapshotReader, File, String) -> T): T {
        val fingerprint = rikkaArchiveFingerprint(archive, checkCancelled)
        require(expectedFingerprint == null || expectedFingerprint == fingerprint) { "Rikka 源文件已变化，请重新预览" }
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "rikka-chats-").toFile()
        try {
            val snapshot = readRikkaStage(RikkaChatReadStage.ARCHIVE) {
                RikkaChatArchive.extract(archive, staging, checkCancelled)
            }
            checkCancelled()
            val normalizationReserve = snapshot.length() + File(staging, "rikka_hub.db-wal").length()
            ArchiveCapacity.requireSpace(staging.usableSpace, normalizationReserve)
            readRikkaStage(RikkaChatReadStage.DATABASE) { DatabaseBackup.normalize(context, snapshot) }
            checkCancelled()
            require(fingerprint == rikkaArchiveFingerprint(archive, checkCancelled)) { "Rikka 源文件已变化，请重新预览" }
            return readRikkaStage(RikkaChatReadStage.SCHEMA) {
                RikkaChatSnapshotReader.open(context, snapshot, checkCancelled)
            }.use { incoming ->
                block(incoming, staging, fingerprint)
            }
        } finally {
            // Exact private directory created above; never remove source archives or app data.
            staging.deleteRecursively()
        }
    }

    private inner class AttachmentSession(private val staging: File) {
        val createdFiles = mutableListOf<Long>()
        var missing = 0
            private set
        private val attachments = mutableMapOf<String, String>()

        suspend fun rollback() { createdFiles.forEach { files.delete(it) } }

        suspend fun mapPart(part: UIMessagePart, conversationKey: String): UIMessagePart {
            currentCoroutineContext().ensureActive()
            @Suppress("DEPRECATION")
            if (part is UIMessagePart.ToolCall) return UIMessagePart.Text("[历史工具调用 ${part.toolName}；未执行]")
            @Suppress("DEPRECATION")
            if (part is UIMessagePart.ToolResult) return UIMessagePart.Text("[历史工具结果 ${part.toolName}]\n${part.content}")
            if (part is UIMessagePart.Tool) return part.copy(
                output = if (part.output.isEmpty()) listOf(UIMessagePart.Text("[导入的历史工具调用；未执行]"))
                    else part.output.map { mapPart(it, conversationKey) },
                approvalState = ToolApprovalState.Denied("历史导入不携带工具授权"),
            )
            val url = when (part) {
                is UIMessagePart.Image -> part.url
                is UIMessagePart.Audio -> part.url
                is UIMessagePart.Video -> part.url
                is UIMessagePart.Document -> part.url
                else -> return part
            }
            RikkaChatContentDecoder.remoteAttachmentReference(url, part.metadata)?.let { return it }
            val name = RikkaChatArchive.uploadName(url)
            val source = name?.let { File(staging, "upload/$it") }?.takeIf { it.isFile }
            if (source == null) { missing++; return UIMessagePart.Text("[原备份未包含可恢复附件]", metadata = part.metadata) }
            // Separate uploads per conversation; deleting one imported window cannot break another.
            val key = "$conversationKey/${requireNotNull(name)}"
            val local = attachments[key] ?: run {
                require(source.length() <= Int.MAX_VALUE)
                RikkaChatArchive.requireExtractionSpace(context.filesDir.usableSpace, source.length().toInt())
                val entity = files.saveUploadFromUri(source.toUri(), source.name)
                createdFiles += entity.id
                files.getFile(entity).toUri().toString().also { attachments[key] = it }
            }
            return when (part) {
                is UIMessagePart.Image -> part.copy(url = local)
                is UIMessagePart.Audio -> part.copy(url = local)
                is UIMessagePart.Video -> part.copy(url = local)
                is UIMessagePart.Document -> part.copy(url = local)
                else -> part
            }
        }
    }

    companion object { private val importMutex = Mutex() }
}
