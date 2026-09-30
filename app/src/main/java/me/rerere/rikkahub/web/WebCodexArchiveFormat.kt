package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

internal class WebCodexArchiveFormat(private val repository: ConversationRepository) : WebChatArchiveFormat {
    override val maxArchiveBytes = CodexChatImporter.MAX_ARCHIVE_BYTES
    override suspend fun inspect(file: File, checkCancelled: () -> Unit): DeepSeekArchivePreview {
        val source = file.inputStream().use { CodexChatArchive.parse(it, checkCancelled = checkCancelled) }
        val updated = source.messages.lastOrNull()?.timestamp ?: source.createdAt
        return DeepSeekArchivePreview(listOf(DeepSeekConversationPreview(source.sourceSessionId,
            CodexChatImporter.title(source), source.createdAt, updated, source.messages.size, source.messages.size, 0,
            listOf(DeepSeekBranchPreview("text", source.messages.size, updated, true)), "text", "codex_text_only")))
    }
    override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult {
        require(selections.size == 1 && selections.values.single() == "text")
        return CodexChatImporter(repository).import(file, assistantId, selections.keys.single(), expectedFingerprint, onProgress)
    }
}
