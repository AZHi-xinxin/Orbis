package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File
import kotlin.uuid.Uuid

/** Preview then append selected conversations. Original ZIP/live databases/settings are never restored. */
class KelivoChatImporter internal constructor(private val context: Context, private val sink: DeepSeekImportSink) {
    constructor(context: Context, repository: ConversationRepository) : this(context, object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) = repository.insertImportedConversations(listOf(conversation)) == 1
    })

    suspend fun inspect(file: File): KelivoChatPreview = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        try {
            withSnapshot(file, null, { coroutine.ensureActive() }) { source, fingerprint ->
                inspectKelivoSource(source, fingerprint) { coroutine.ensureActive() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IllegalArgumentException("Kelivo 备份不兼容、损坏或超限；目前支持聊天 SQLite v3 的 v2 ZIP，原聊天未更改") }
    }

    suspend fun importSelected(file: File, assistantId: Uuid, selections: Set<String>, expectedFingerprint: String,
        onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        var result = DeepSeekImportResult()
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val coroutine = currentCoroutineContext()
                    require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) { "kelivo_preview_required" }
                    withSnapshot(file, expectedFingerprint, { coroutine.ensureActive() }) { source, fingerprint ->
                        val preview = inspectKelivoSource(source, fingerprint) { coroutine.ensureActive() }
                        importSelectedKelivoChats(source, preview, selections, assistantId, sink) {
                            result = it.result
                            onProgress(it)
                        }.also { result = it }
                    }
                }
            }
        } catch (cancelled: DeepSeekImportCancelledException) { throw cancelled }
        catch (failed: DeepSeekImportException) { throw failed }
        catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (_: Exception) { throw DeepSeekImportException(result) }
    }

    private suspend fun <T> withSnapshot(file: File, expected: String?, checkCancelled: () -> Unit,
        block: suspend (KelivoChatSnapshotReader, String) -> T): T {
        val fingerprint = KelivoChatArchive.fingerprint(file, checkCancelled)
        require(expected == null || expected == fingerprint) { "kelivo_archive_changed" }
        ChatImportStaging.create(context.cacheDir).use { staging ->
            val snapshot = KelivoChatArchive.extract(file, staging.payload, checkCancelled)
            require(fingerprint == KelivoChatArchive.fingerprint(file, checkCancelled)) { "kelivo_archive_changed" }
            checkCancelled()
            return KelivoChatSnapshotReader.open(snapshot.file, snapshot.conversationCount, snapshot.messageCount,
                checkCancelled).use { source -> block(source, fingerprint) }
        }
    }
    companion object { private val mutex = Mutex() }
}
