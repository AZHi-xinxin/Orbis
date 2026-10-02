package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File
import kotlin.uuid.Uuid

/** Preview and append the human-visible direct conversations from a Polaris export. */
class PolarisChatImporter internal constructor(private val sink: DeepSeekImportSink) {
    constructor(repository: ConversationRepository) : this(object : DeepSeekImportSink {
        override suspend fun exists(id: Uuid) = repository.existsConversationById(id)
        override suspend fun insert(conversation: Conversation) =
            repository.insertImportedConversations(listOf(conversation)) == 1
    })

    suspend fun inspect(file: File): PolarisChatPreview = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val checkCancelled = { coroutine.ensureActive() }
        val fingerprint = PolarisChatArchive.fingerprint(file, checkCancelled)
        val previews = mutableListOf<DeepSeekConversationPreview>()
        var totalMessages = 0
        var omittedSystem = 0
        var omittedReferences = 0
        var omittedNonDirect = 0
        PolarisChatArchive.open(file, checkCancelled).use { archive ->
            for (chat in archive.conversations()) {
                checkCancelled()
                totalMessages += chat.messages.size + chat.omittedSystemMessages
                require(totalMessages <= PolarisChatLimits.MAX_TOTAL_MESSAGES) { "polaris_total_messages" }
                omittedSystem += chat.omittedSystemMessages
                omittedReferences += preflightPolarisConversation(chat, checkCancelled)
                val updated = polarisTimestamp(chat.updatedMillis)
                val created = chat.messages.minOfOrNull { it.timestampMillis }?.let(::polarisTimestamp) ?: updated
                previews += DeepSeekConversationPreview(chat.id, chat.title, created, updated,
                    chat.messages.size + chat.omittedSystemMessages, chat.messages.size, 0,
                    listOf(DeepSeekBranchPreview("selected", chat.messages.size, updated, true)),
                    "selected", "polaris_original_order")
            }
            omittedNonDirect = archive.skippedNonDirect
        }
        require(previews.isNotEmpty()) { "polaris_no_direct_conversations" }
        require(fingerprint == PolarisChatArchive.fingerprint(file, checkCancelled)) { "polaris_archive_changed" }
        PolarisChatPreview(fingerprint, previews, buildList {
            add("按北极星原数组顺序追加会话；重复导入跳过已有窗口，不合并到现有窗口。")
            if (omittedSystem > 0) add("已跳过 $omittedSystem 条北极星历史系统消息，不作为聊天或提示词导入。")
            if (omittedNonDirect > 0) add("已跳过 $omittedNonDirect 个暂不支持的群组窗口，原 ZIP 中的记录仍保留。")
            if (omittedReferences > 0) add("有 $omittedReferences 处历史媒体引用；只留占位，不读取或下载附件。")
            add("人格、运行状态、任务、工具记录、设置与资源文件不会导入。原始导出包请私密保管。")
        })
    }

    suspend fun importSelected(file: File, assistantId: Uuid, selections: Set<String>,
        expectedFingerprint: String, onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        var result = DeepSeekImportResult()
        var active: String? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val coroutine = currentCoroutineContext()
                    val checkCancelled = { coroutine.ensureActive() }
                    require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) { "polaris_preview_required" }
                    require(selections.isNotEmpty()) { "polaris_selection_required" }
                    require(expectedFingerprint == PolarisChatArchive.fingerprint(file, checkCancelled)) {
                        "polaris_archive_changed"
                    }
                    // Full preflight of every window precedes the first commit.
                    val preview = inspect(file)
                    require(preview.fingerprint == expectedFingerprint &&
                        selections.all { selected -> preview.conversations.any { it.sourceId == selected } }) {
                        "polaris_selection_changed"
                    }
                    var completed = 0
                    onProgress(DeepSeekImportProgress(selections.size, completed, result))
                    PolarisChatArchive.open(file, checkCancelled).use { archive ->
                        for (chat in archive.conversations()) {
                            if (chat.id !in selections) continue
                            checkCancelled()
                            active = chat.id
                            if (sink.exists(polarisImportId("conversation", chat.id))) {
                                result = result.copy(skipped = result.skipped + 1)
                            } else {
                                val converted = convertPolarisConversation(chat, assistantId, checkCancelled)
                                checkCancelled()
                                withContext(NonCancellable) {
                                    result = if (sink.insert(converted.conversation)) result.copy(
                                        imported = result.imported + 1,
                                        messages = result.messages + converted.conversation.messageNodes.size,
                                        attachmentReferences = result.attachmentReferences + converted.attachmentReferences)
                                    else result.copy(skipped = result.skipped + 1)
                                }
                                checkCancelled()
                            }
                            completed++
                            active = null
                            onProgress(DeepSeekImportProgress(selections.size, completed, result))
                        }
                    }
                    require(completed == selections.size) { "polaris_selection_changed" }
                    result
                }
            }
        } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        catch (_: Exception) {
            if (active != null) result = result.copy(failed = result.failed + 1,
                failures = result.failures + DeepSeekImportFailure(active, "该北极星窗口未完成；已完成窗口保留，现有聊天未覆盖"))
            throw DeepSeekImportException(result)
        }
    }

    companion object { private val mutex = Mutex() }
}
