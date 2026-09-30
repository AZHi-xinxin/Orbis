package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

internal class WebRikkaArchiveFormat(private val importer: RikkaChatImporter) : WebChatArchiveFormat {
    override val maxArchiveBytes = RikkaChatArchive.MAX_ARCHIVE_BYTES
    override suspend fun inspect(file: File, checkCancelled: () -> Unit): DeepSeekArchivePreview {
        checkCancelled()
        val preview = importer.inspect(file)
        checkCancelled()
        return DeepSeekArchivePreview(preview.conversations)
    }
    override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult {
        require(selections.values.all { it == "all" })
        return importer.importSelected(file, assistantId, selections.keys, expectedFingerprint, onProgress)
    }
}
