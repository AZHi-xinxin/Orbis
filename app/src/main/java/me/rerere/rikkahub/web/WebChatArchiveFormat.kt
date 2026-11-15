package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

/** Common transport projection; the legacy DeepSeek-named DTO carries only inert preview data.
 * Each source parser retains its own format validation and never imports executable settings.
 */
internal interface WebChatArchiveFormat {
    val maxArchiveBytes: Long
    suspend fun inspect(file: File, checkCancelled: () -> Unit): DeepSeekArchivePreview
    suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult
}

internal class WebDeepSeekArchiveFormat(private val repository: ConversationRepository,
    private val files: me.rerere.rikkahub.data.files.FilesManager? = null, private val cache: File? = null) : WebChatArchiveFormat {
    override val maxArchiveBytes = DeepSeekArchive.MAX_ARCHIVE_BYTES
    override suspend fun inspect(file: File, checkCancelled: () -> Unit) = DeepSeekArchive.inspect(file, checkCancelled)
    override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit) =
        DeepSeekChatImporter(repository, files, cache).import(file, assistantId, selections, onProgress)
}
