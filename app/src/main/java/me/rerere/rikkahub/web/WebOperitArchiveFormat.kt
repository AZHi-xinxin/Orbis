package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

internal class WebOperitArchiveFormat(private val repository: ConversationRepository) : WebChatArchiveFormat {
    override val maxArchiveBytes = OperitChatArchive.MAX_ARCHIVE_BYTES
    override suspend fun inspect(file: File, checkCancelled: () -> Unit) = OperitChatArchive.inspect(file, checkCancelled)
    override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult =
        OperitChatImporter(repository).import(file, assistantId, selections, expectedFingerprint, onProgress)
}
