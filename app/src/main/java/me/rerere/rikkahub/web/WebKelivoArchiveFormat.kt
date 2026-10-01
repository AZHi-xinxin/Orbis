package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

internal class WebKelivoArchiveFormat(private val importer: KelivoChatImporter) : WebChatArchiveFormat {
    override val maxArchiveBytes = KelivoChatArchive.MAX_ARCHIVE_BYTES

    override suspend fun inspect(file: File, checkCancelled: () -> Unit): DeepSeekArchivePreview {
        checkCancelled()
        val preview = importer.inspect(file)
        checkCancelled()
        return DeepSeekArchivePreview(preview.conversations, preview.warnings)
    }

    override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult {
        require(selections.values.all { it == "selected" })
        return importer.importSelected(file, assistantId, selections.keys, expectedFingerprint, onProgress)
    }
}
