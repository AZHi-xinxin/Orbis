package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.uuid.Uuid

data class DeepSeekImportUiState(
    val busy: Boolean = false,
    val phase: String = "",
    val preview: DeepSeekArchivePreview? = null,
    val result: String? = null,
    val destinationName: String? = null,
)

enum class ChatArchiveSource(val label: String, val maxBytes: Long) {
    DEEPSEEK("DeepSeek 官方 ZIP", DeepSeekArchive.MAX_ARCHIVE_BYTES),
    OPERIT("Operit v2 JSON", OperitChatArchive.MAX_ARCHIVE_BYTES),
    KELIVO("Kelivo 安卓 ZIP", KelivoChatArchive.MAX_ARCHIVE_BYTES),
    POLARIS("北极星 ZIP", PolarisChatArchive.MAX_ARCHIVE_BYTES),
}

/** Owned by BackupVM, not a lazy-list item; scrolling/rotation cannot restart an import. */
class DeepSeekImportController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: ConversationRepository,
    val source: ChatArchiveSource = ChatArchiveSource.DEEPSEEK,
    private val assistantName: (Uuid) -> String?,
    private val assistantId: () -> Uuid,
) {
    private val mutableState = MutableStateFlow(DeepSeekImportUiState())
    val state = mutableState.asStateFlow()
    private var stagedLease: ChatImportStaging.Lease? = null
    private var destination: Uuid? = null
    private var job: Job? = null
    private var fingerprint: String? = null
    val sourceLabel: String get() = source.label
    val selectedAnswersOnly: Boolean get() = source != ChatArchiveSource.DEEPSEEK

    init { scope.launch(Dispatchers.IO) { runCatching { ChatImportStaging.prune(context.cacheDir) } } }

    fun preview(uri: Uri) {
        if (mutableState.value.busy) return
        discard()
        val target = runCatching { assistantId().also { requireImportTargetName(it, assistantName) } }.getOrElse {
            mutableState.value = DeepSeekImportUiState(result = "身份设置还未加载，请稍后再试。")
            return
        }
        mutableState.value = DeepSeekImportUiState(busy = true, phase = "正在本机读取并检查导出包…")
        val work = scope.launch(start = CoroutineStart.LAZY) {
            var temporary: ChatImportStaging.Lease? = null
            var retained = false
            try {
                val preview = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    val lease = ChatImportStaging.create(context.cacheDir)
                    temporary = lease
                    val file = java.io.File(lease.payload, "archive.bin")
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        ChatImportStaging.copyArchive(input, file, source.maxBytes, { coroutine.ensureActive() })
                    }
                    when (source) {
                        ChatArchiveSource.OPERIT -> {
                            val result = OperitChatArchive.inspect(file) { coroutine.ensureActive() }
                            fingerprint = OperitChatImporter.archiveFingerprint(file) { coroutine.ensureActive() }
                            result
                        }
                        ChatArchiveSource.KELIVO -> {
                            val result = KelivoChatImporter(context, repository).inspect(file)
                            fingerprint = result.fingerprint
                            DeepSeekArchivePreview(result.conversations, result.warnings)
                        }
                        ChatArchiveSource.POLARIS -> {
                            val result = PolarisChatImporter(repository).inspect(file)
                            fingerprint = result.fingerprint
                            DeepSeekArchivePreview(result.conversations, result.warnings)
                        }
                        ChatArchiveSource.DEEPSEEK -> DeepSeekArchive.inspect(file) { coroutine.ensureActive() }
                    }
                }
                currentCoroutineContext().ensureActive()
                val name = requireImportTargetName(target, assistantName)
                stagedLease = temporary
                destination = target
                retained = true
                mutableState.value = DeepSeekImportUiState(preview = preview, destinationName = name)
            } catch (_: CancellationException) {
                mutableState.value = DeepSeekImportUiState(result = "已取消读取，没有导入聊天。")
            } catch (_: Exception) {
                mutableState.value = DeepSeekImportUiState(result =
                    "无法读取此 $sourceLabel：格式不兼容、文件损坏、超过大小限制或本机可用空间不足。现有聊天未更改。")
            } finally {
                if (!retained) temporary?.close()
                mutableState.update { it.copy(busy = false) }
            }
        }
        job = work
        work.invokeOnCompletion { cause ->
            if (cause is CancellationException) mutableState.update {
                it.copy(busy = false, result = it.result ?: "已取消读取，没有导入聊天。")
            }
        }
        work.start()
    }

    fun import(selections: Map<String, String>) {
        if (mutableState.value.busy || selections.isEmpty()) return
        val lease = stagedLease ?: return
        val file = java.io.File(lease.payload, "archive.bin")
        val target = destination ?: return
        val name = runCatching { requireImportTargetName(target, assistantName) }.getOrElse {
            discard()
            mutableState.value = DeepSeekImportUiState(result = "原先选择的助手已不可用，未导入任何聊天。请重新选择助手和原文件。")
            return
        }
        val selected = selections.toMap()
        mutableState.update { it.copy(busy = true, phase = "正在校验并导入所选路径…", result = null, destinationName = name) }
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Recheck after dispatch as the destination may have been removed while queued.
                requireImportTargetName(target, assistantName)
                require(source !in setOf(ChatArchiveSource.KELIVO, ChatArchiveSource.POLARIS) ||
                    selected.values.all { it == "selected" })
                val progress: (DeepSeekImportProgress) -> Unit = { progress ->
                    mutableState.update { it.copy(phase = "已处理 ${progress.completed} / ${progress.total} 个会话…") }
                }
                val result = when (source) {
                    ChatArchiveSource.OPERIT -> OperitChatImporter(repository).import(file, target, selected,
                        requireNotNull(fingerprint), progress)
                    ChatArchiveSource.KELIVO -> KelivoChatImporter(context, repository).importSelected(file, target,
                        selected.keys, requireNotNull(fingerprint), progress)
                    ChatArchiveSource.POLARIS -> PolarisChatImporter(repository).importSelected(file, target,
                        selected.keys, requireNotNull(fingerprint), progress)
                    ChatArchiveSource.DEEPSEEK -> DeepSeekChatImporter(repository).import(file, target, selected, progress)
                }
                mutableState.value = DeepSeekImportUiState(result = summary(result))
            } catch (cancelled: DeepSeekImportCancelledException) {
                mutableState.value = DeepSeekImportUiState(result = "已停止；已完成的会话保留。\n" + summary(cancelled.partialResult))
            } catch (failure: DeepSeekImportException) {
                mutableState.value = DeepSeekImportUiState(result = "导入未全部完成；可以重新选择原文件继续，已有路径会跳过。\n" + summary(failure.partialResult))
            } catch (_: CancellationException) {
                mutableState.value = DeepSeekImportUiState(result = "已停止。重新导入会自动跳过已完成的会话。")
            } catch (_: Exception) {
                mutableState.value = DeepSeekImportUiState(result = "导入未完成；现有聊天未被覆盖。可以重新选择原文件。")
            }
        }
        job = work
        // Registered before start: cancellation before the body begins still releases the full source ZIP.
        lease.releaseOnCompletion(work)
        work.invokeOnCompletion { cause ->
            if (stagedLease === lease) {
                stagedLease = null
                destination = null
                fingerprint = null
                mutableState.update { it.copy(busy = false, preview = null,
                    result = it.result ?: if (cause is CancellationException) "已停止，未开始导入。" else null) }
            }
        }
        work.start()
    }

    fun cancel() { job?.cancel() }
    fun dismissResult() { mutableState.update { it.copy(result = null) } }
    fun discard() {
        if (mutableState.value.busy) return
        stagedLease?.close()
        stagedLease = null
        destination = null
        fingerprint = null
        mutableState.value = DeepSeekImportUiState()
    }
    fun close() {
        if (mutableState.value.busy) job?.cancel() else discard()
    }

    private fun summary(result: DeepSeekImportResult): String = buildString {
        append("已导入 ${result.imported} 个会话路径、${result.messages} 条消息；跳过 ${result.skipped} 个已有路径；${result.failed} 个未导入。")
        if (result.attachmentReferences > 0) append("\n有 ${result.attachmentReferences} 个附件引用；未导入文件本体，只保留说明，不自动联网下载。")
        if (result.skippedSummaries > 0) append("\n本次新增会话跳过 ${result.skippedSummaries} 条内部摘要；没有把摘要当作聊天或系统提示，原导出文件未改动。")
        result.failures.map { it.reason }.distinct().forEach { append("\n").append(it) }
        append("\n未覆盖原聊天、未导入账号/密钥/工具授权，也没有向模型发送消息。请保留原文件。")
    }
}

/** A frozen target never silently follows a later current-assistant switch. */
internal fun requireImportTargetName(id: Uuid, lookup: (Uuid) -> String?): String =
    checkNotNull(lookup(id)) { "import_target_missing" }.ifBlank { "未命名 AI" }

internal fun importPreviewCounts(conversation: DeepSeekConversationPreview, branch: DeepSeekBranchPreview): String =
    if (conversation.defaultSelectionReason.startsWith("kelivo_selected"))
        "导入当前回答 ${branch.messageCount} 条 · 源消息（含备用版本）${conversation.totalNodes} 条"
    else if (conversation.defaultSelectionReason == "polaris_original_order")
        "导入 ${branch.messageCount} 条 · 原窗口 ${conversation.totalNodes} 条（只读聊天，不导入配置）"
    else "所选路径 ${branch.messageCount} 条 · 原窗口 ${conversation.messageCount} 条 · ${conversation.branches.size} 条可选路径"
