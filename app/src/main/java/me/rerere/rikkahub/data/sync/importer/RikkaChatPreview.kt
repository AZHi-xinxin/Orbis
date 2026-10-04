package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.uuid.Uuid

data class RikkaChatPreview(val fingerprint: String, val conversations: List<DeepSeekConversationPreview>)

/** Cursor-backed in production; fixtures can provide rows without Android, a real DB or uploads. */
internal interface RikkaChatSource : Closeable {
    fun conversations(): List<RikkaChatSnapshotReader.Chat>
    suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit)
}

internal object RikkaChatLimits {
    // Source admission is independent of the smaller target SQLite write budget. Conversion
    // can remove a legacy system payload, or grow tools/attachment references, so check again.
    const val MAX_NODE_BYTES = 1024 * 1024
    const val MAX_WINDOW_BYTES = 32L * 1024 * 1024
    const val MAX_WINDOW_NODES = 50_000
    const val MAX_WINDOW_MESSAGES = 100_000
    const val MAX_NODE_BRANCHES = 1024
}

internal fun requireRikkaSourceId(id: String) {
    require(id.length == 36 && runCatching { Uuid.parse(id) }.isSuccess) { "备份聊天或消息标识格式异常；原聊天未更改" }
}

internal fun decodeRikkaNode(node: RikkaChatSnapshotReader.Node): List<UIMessage> {
    requireRikkaSourceId(node.id)
    require(node.messages.toByteArray(Charsets.UTF_8).size <= RikkaChatLimits.MAX_NODE_BYTES) { "备份单条消息过大；原文未截断" }
    val messages = try { JsonInstant.decodeFromString<List<UIMessage>>(node.messages) }
    catch (_: kotlinx.serialization.SerializationException) {
        throw IllegalArgumentException("备份中的消息格式暂不兼容或已损坏；原聊天未更改")
    }
    require(messages.size <= RikkaChatLimits.MAX_NODE_BRANCHES) { "备份单条消息的分支过多" }
    require(if (messages.isEmpty()) node.selectIndex == 0 else node.selectIndex in messages.indices) { "备份消息分支索引无效" }
    require(messages.map { it.id }.distinct().size == messages.size) { "备份含重复消息分支标识" }
    return messages
}

/** Decode one bounded node at a time; no Conversation or whole-window JSON tree survives preview. */
internal suspend fun inspectRikkaSource(source: RikkaChatSource, fingerprint: String,
    checkCancelled: () -> Unit = {}): RikkaChatPreview {
    val identities = hashSetOf<String>()
    val previews = mutableListOf<DeepSeekConversationPreview>()
    for (chat in source.conversations()) {
        checkCancelled()
        requireRikkaSourceId(chat.id)
        require(identities.add(chat.id) && identities.size <= 10_000) { "备份窗口标识重复或数量过多" }
        var nodes = 0
        var messages = 0
        var branching = 0
        var bytes = 0L
        val messageIds = hashSetOf<Uuid>()
        source.visitNodes(chat.id) { row ->
            checkCancelled()
            bytes += row.messages.toByteArray(Charsets.UTF_8).size
            require(++nodes <= RikkaChatLimits.MAX_WINDOW_NODES && bytes <= RikkaChatLimits.MAX_WINDOW_BYTES) {
                "单个聊天窗口超过安全导入上限；请拆分备份，原文未截断"
            }
            val branches = decodeRikkaNode(row)
            messages += branches.size
            require(messages <= RikkaChatLimits.MAX_WINDOW_MESSAGES && branches.all { messageIds.add(it.id) }) {
                "单窗消息过多或消息标识重复；原文未截断"
            }
            if (branches.size > 1) branching++
        }
        val updated = Instant.ofEpochMilli(chat.updateAt)
        previews += DeepSeekConversationPreview(chat.id, chat.title, Instant.ofEpochMilli(chat.createAt),
            updated, nodes, messages, branching,
            listOf(DeepSeekBranchPreview("all", messages, updated, true)), "all", "all_branches_preserved")
    }
    return RikkaChatPreview(fingerprint, previews)
}

/** Shared by legacy all-at-once and selected-per-window import; never copy account/settings fields. */
internal suspend fun convertRikkaChat(source: RikkaChatSource, chat: RikkaChatSnapshotReader.Chat,
    assistantId: Uuid, mapPart: suspend (UIMessagePart, String) -> UIMessagePart,
    checkCancelled: () -> Unit = {}): Conversation {
    requireRikkaSourceId(chat.id)
    val nodes = mutableListOf<MessageNode>()
    source.visitNodes(chat.id) { node ->
        checkCancelled()
        val messages = decodeRikkaNode(node)
        if (messages.isNotEmpty()) {
            val converted = messages.map { message ->
                checkCancelled()
                message.copy(id = rikkaImportId("message", "${chat.id}/${message.id}"),
                    role = if (message.role == MessageRole.SYSTEM) MessageRole.ASSISTANT else message.role,
                    parts = if (message.role == MessageRole.SYSTEM)
                        listOf(UIMessagePart.Text("[此处为原备份的系统设定；仅聊天导入未包含其正文]"))
                    else message.parts.map { mapPart(it, chat.id) })
            }
            try { MessageNodeBudget.measureNode(converted) }
            catch (_: MessageNodeCapacityException) { throw ArchiveReadException(ArchiveFailure.NODE_LIMIT) }
            nodes += MessageNode(id = rikkaImportId("node", "${chat.id}/${node.id}"),
                messages = converted, selectIndex = node.selectIndex)
        }
    }
    return Conversation(id = rikkaImportId("conversation", chat.id), assistantId = assistantId,
        title = chat.title, createAt = Instant.ofEpochMilli(chat.createAt), updateAt = Instant.ofEpochMilli(chat.updateAt),
        messageNodes = nodes)
}

internal fun rikkaArchiveFingerprint(file: File, checkCancelled: () -> Unit = {}): String {
    require(file.isFile) { "备份文件不存在" }
    ArchiveCapacity.requireSize(file.length(), RikkaChatArchive.MAX_ARCHIVE_BYTES)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            checkCancelled()
            val count = stream.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= RikkaChatArchive.MAX_ARCHIVE_BYTES) { "导入源文件大小已变化" }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class PreparedRikkaChat(val conversation: Conversation, val attachmentReferences: Int,
    val rollbackAttachments: suspend () -> Unit)

/** Each commit and its receipt accounting are one non-cancellable boundary, never the whole ZIP. */
internal suspend fun importSelectedRikkaChats(source: RikkaChatSource, preview: RikkaChatPreview,
    selections: Set<String>, sink: DeepSeekImportSink,
    prepare: suspend (RikkaChatSnapshotReader.Chat) -> PreparedRikkaChat,
    onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
    var result = DeepSeekImportResult()
    var activeSource: String? = null
    try {
        require(selections.isNotEmpty()) { "请选择至少一个 Rikka 会话" }
        selections.forEach(::requireRikkaSourceId)
        val known = preview.conversations.map { it.sourceId }.toSet()
        require(selections.all { it in known }) { "所选 Rikka 会话已变化，请重新预览" }
        var completed = 0
        onProgress(DeepSeekImportProgress(selections.size, completed, result))
        for (chat in source.conversations()) {
            if (chat.id !in selections) continue
            currentCoroutineContext().ensureActive()
            activeSource = chat.id
            val targetId = rikkaImportId("conversation", chat.id)
            if (sink.exists(targetId)) result = result.copy(skipped = result.skipped + 1)
            else {
                val prepared = prepare(chat)
                var committed = false
                try {
                    check(prepared.conversation.id == targetId) { "导入目标标识不一致" }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        if (sink.insert(prepared.conversation)) {
                            committed = true
                            result = result.copy(imported = result.imported + 1,
                                messages = result.messages + prepared.conversation.messageNodes.sumOf { it.messages.size },
                                attachmentReferences = result.attachmentReferences + prepared.attachmentReferences)
                        } else result = result.copy(skipped = result.skipped + 1)
                    }
                    currentCoroutineContext().ensureActive()
                } finally {
                    if (!committed) withContext(NonCancellable) { prepared.rollbackAttachments() }
                }
            }
            completed++
            activeSource = null
            onProgress(DeepSeekImportProgress(selections.size, completed, result))
        }
        check(completed == selections.size) { "所选会话已变化" }
        return result
    } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
    catch (failure: Exception) {
        if (activeSource != null) result = result.copy(failed = result.failed + 1,
            failures = result.failures + DeepSeekImportFailure(activeSource, "该窗口未完成；已完成窗口保留，未覆盖现有聊天"))
        throw DeepSeekImportException(result, ArchiveCapacity.publicError(failure), ArchiveCapacity.reasonOf(failure))
    }
}
