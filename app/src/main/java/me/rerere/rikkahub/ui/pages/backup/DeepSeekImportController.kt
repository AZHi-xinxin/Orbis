package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.*
import java.io.File
import kotlin.uuid.Uuid

data class DeepSeekImportUiState(
    val busy: Boolean = false,
    val phase: String = "",
    val preview: DeepSeekArchivePreview? = null,
    val result: String? = null,
)

/** Owned by BackupVM, not a lazy-list item; scrolling/rotation cannot restart an import. */
class DeepSeekImportController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: ConversationRepository,
    private val assistantId: () -> Uuid,
) {
    private val mutableState = MutableStateFlow(DeepSeekImportUiState())
    val state = mutableState.asStateFlow()
    private var stagedFile: File? = null
    private var destination: Uuid? = null
    private var job: Job? = null

    fun preview(uri: Uri) {
        if (mutableState.value.busy) return
        discard()
        val target = runCatching(assistantId).getOrElse {
            mutableState.value = DeepSeekImportUiState(result = "身份设置还未加载，请稍后再试。")
            return
        }
        mutableState.value = DeepSeekImportUiState(busy = true, phase = "正在本机读取并检查导出包…")
        job = scope.launch {
            var temporary: File? = null
            var retained = false
            try {
                val preview = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    val file = File.createTempFile("deepseek-import-", ".zip", context.cacheDir)
                    temporary = file
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytes = 0L
                            while (true) {
                                coroutine.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= DeepSeekArchive.MAX_ARCHIVE_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    DeepSeekArchive.inspect(file) { coroutine.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                stagedFile = temporary
                destination = target
                retained = true
                mutableState.value = DeepSeekImportUiState(preview = preview)
            } catch (_: CancellationException) {
                mutableState.value = DeepSeekImportUiState(result = "已取消读取，没有导入聊天。")
            } catch (_: Exception) {
                mutableState.value = DeepSeekImportUiState(result =
                    "无法读取此 DeepSeek 官方 ZIP：格式不兼容、文件损坏或超过大小限制。现有聊天未更改。")
            } finally {
                if (!retained) temporary?.delete()
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun import(selections: Map<String, String>) {
        if (mutableState.value.busy || selections.isEmpty()) return
        val file = stagedFile ?: return
        val target = destination ?: return
        val selected = selections.toMap()
        mutableState.update { it.copy(busy = true, phase = "正在校验并导入所选路径…", result = null) }
        job = scope.launch {
            try {
                val result = DeepSeekChatImporter(repository).import(file, target, selected) { progress ->
                    mutableState.update { it.copy(phase = "已处理 ${progress.completed} / ${progress.total} 个会话…") }
                }
                mutableState.value = DeepSeekImportUiState(result = summary(result))
            } catch (cancelled: DeepSeekImportCancelledException) {
                mutableState.value = DeepSeekImportUiState(result = "已停止；已完成的会话保留。\n" + summary(cancelled.partialResult))
            } catch (failure: DeepSeekImportException) {
                mutableState.value = DeepSeekImportUiState(result = "导入未全部完成；可以重新选择原 ZIP 继续，已有路径会跳过。\n" + summary(failure.partialResult))
            } catch (_: CancellationException) {
                mutableState.value = DeepSeekImportUiState(result = "已停止。重新导入会自动跳过已完成的会话。")
            } catch (_: Exception) {
                mutableState.value = DeepSeekImportUiState(result = "导入未完成；现有聊天未被覆盖。可以重新选择原 ZIP。")
            } finally {
                file.delete()
                stagedFile = null
                destination = null
                mutableState.update { it.copy(busy = false, preview = null) }
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun dismissResult() { mutableState.update { it.copy(result = null) } }
    fun discard() {
        if (mutableState.value.busy) return
        stagedFile?.delete()
        stagedFile = null
        destination = null
        mutableState.value = DeepSeekImportUiState()
    }
    fun close() {
        if (mutableState.value.busy) job?.cancel() else discard()
    }

    private fun summary(result: DeepSeekImportResult): String = buildString {
        append("已导入 ${result.imported} 个会话路径、${result.messages} 条消息；跳过 ${result.skipped} 个已有路径；${result.failed} 个未导入。")
        if (result.attachmentReferences > 0) append("\n有 ${result.attachmentReferences} 个附件引用；官方 ZIP 没有这些文件本体，只保留说明，不自动联网下载。")
        result.failures.map { it.reason }.distinct().forEach { append("\n").append(it) }
        append("\n未覆盖原聊天、未导入账号/密钥/工具授权，也没有向模型发送消息。其他分支仍在原 ZIP 中，请保留该文件。")
    }
}
