package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class RikkaChatImportResult(val imported: Int = 0, val skipped: Int = 0,
    val attachments: Int = 0, val missingAttachments: Int = 0)

class RikkaChatImportCancelledException(val partialResult: RikkaChatImportResult) :
    CancellationException("Rikka 聊天导入已停止；已完成的窗口保留")

class RikkaPartialImportException(val partialResult: RikkaChatImportResult, detail: String) : IllegalArgumentException(
    "已保留 ${partialResult.imported} 个已导入窗口，跳过 ${partialResult.skipped} 个已有窗口；其余未完成。$detail 可重新选择原包继续，已有窗口会跳过。") {
    val imported: Int get() = partialResult.imported
    val skipped: Int get() = partialResult.skipped
}

/** Counts live outside the dispatched block: prompt cancellation on its return cannot erase a commit. */
internal class RikkaWholeImportAccounting(private val onProgress: (RikkaChatImportResult) -> Unit) {
    var result = RikkaChatImportResult()
        private set

    fun imported(attachments: Int, missingAttachments: Int) {
        require(attachments >= 0 && missingAttachments >= 0)
        result = result.copy(imported = result.imported + 1,
            attachments = result.attachments + attachments,
            missingAttachments = result.missingAttachments + missingAttachments)
        onProgress(result)
    }

    fun skipped() {
        result = result.copy(skipped = result.skipped + 1)
        onProgress(result)
    }
}

internal suspend fun accountRikkaWholeImport(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onProgress: (RikkaChatImportResult) -> Unit = {},
    import: suspend (RikkaWholeImportAccounting) -> Unit,
): RikkaChatImportResult {
    val accounting = RikkaWholeImportAccounting(onProgress)
    try {
        return withContext(dispatcher) { import(accounting); accounting.result }
    } catch (_: CancellationException) {
        throw RikkaChatImportCancelledException(accounting.result)
    } catch (failure: Exception) {
        throw RikkaPartialImportException(accounting.result, ArchiveCapacity.publicError(failure))
    }
}
