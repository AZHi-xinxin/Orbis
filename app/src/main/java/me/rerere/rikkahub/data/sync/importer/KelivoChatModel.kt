package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

data class KelivoChatPreview(val fingerprint: String, val conversations: List<DeepSeekConversationPreview>,
    val warnings: List<String> = emptyList(), val omittedAttachmentReferences: Int = 0)

internal data class KelivoChat(val id: String, val title: String, val createdMicros: Long,
    val updatedMicros: Long, val selectionsJson: String)
internal data class KelivoMessage(val id: String, val group: String?, val version: Int, val order: Long,
    val role: String, val timestampMicros: Long, val reasoning: String? = null, val attachmentReferences: Int = 0)
internal data class KelivoPart(val kind: String, val payload: String)
internal interface KelivoChatSource : Closeable {
    fun conversations(): List<KelivoChat>
    fun messages(chatId: String): List<KelivoMessage>
    fun parts(messageId: String): List<KelivoPart>
}

internal object KelivoChatLimits {
    const val MAX_PART_BYTES = 512 * 1024
    const val MAX_WINDOW_BYTES = 32L * 1024 * 1024
    const val MAX_WINDOW_MESSAGES = 50000
    const val MAX_PARTS_PER_MESSAGE = 256
}

internal fun kelivoImportId(kind: String, vararg identities: String): Uuid = Uuid.parse(UUID.nameUUIDFromBytes(
    ("orbis-kelivo-sqlite-v2/$kind/" + identities.joinToString("") { "${it.length}:$it" }).toByteArray(Charsets.UTF_8)).toString())

internal fun kelivoTimestamp(micros: Long): Instant {
    require(micros in 0..253402300799999999L) { "kelivo_timestamp" }
    return Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1000)
}

internal fun requireKelivoId(id: String) {
    require(id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 || it.code == 127 }) { "kelivo_id" }
}

/** Explicit group -> version selection; ambiguous or missing selections never silently choose another answer. */
internal fun selectedKelivoMessages(chat: KelivoChat, rows: List<KelivoMessage>): List<KelivoMessage> {
    requireKelivoId(chat.id)
    kelivoTimestamp(chat.createdMicros); kelivoTimestamp(chat.updatedMicros)
    require(rows.size <= KelivoChatLimits.MAX_WINDOW_MESSAGES && rows.map { it.id }.distinct().size == rows.size) {
        "kelivo_message_count_or_duplicate"
    }
    val selections = DeepSeekStrictJson.parse(chat.selectionsJson) as? JsonObject ?: error("kelivo_version_selections")
    val versions = selections.mapValues { (key, value) ->
        requireKelivoId(key)
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
            ?: error("kelivo_selected_version")
    }
    rows.forEach {
        requireKelivoId(it.id); it.group?.let(::requireKelivoId)
        require(it.version >= 0 && it.order >= 0 && it.role in setOf("user", "assistant", "system", "tool")) { "kelivo_message_header" }
        require(it.attachmentReferences in 0..10000) { "kelivo_attachment_count" }
        kelivoTimestamp(it.timestampMicros)
    }
    val groups = rows.groupBy { it.group ?: it.id }
    require(versions.keys.all { it in groups }) { "kelivo_unknown_selected_group" }
    val selected = groups.map { (group, messages) ->
        require(messages.map { it.version }.distinct().size == messages.size && messages.map { it.role }.distinct().size == 1) {
            "kelivo_ambiguous_group"
        }
        val version = versions[group]
        val chosen = if (version == null) messages.singleOrNull() ?: error("kelivo_missing_selected_version")
        else messages.singleOrNull { it.version == version } ?: error("kelivo_missing_selected_version")
        messages.minOf { it.order } to chosen
    }.sortedBy { it.first }
    require(selected.map { it.first }.distinct().size == selected.size) { "kelivo_message_order" }
    return selected.map { it.second }
}

internal data class ConvertedKelivo(val conversation: Conversation, val omittedAttachments: Int)

/** A historical document, never executable Tool parts, system settings, pending work or remote media. */
internal fun convertKelivoChat(source: KelivoChatSource, chat: KelivoChat, assistantId: Uuid,
    checkCancelled: () -> Unit = {}): ConvertedKelivo {
    val selected = selectedKelivoMessages(chat, source.messages(chat.id))
    var omitted = 0
    var bytes = 0L
    val nodes = selected.map { message ->
        checkCancelled()
        val metadata = buildJsonObject {
            put("import_source", "kelivo_sqlite_v2")
            put("source_conversation_id", chat.id)
            put("source_message_id", message.id)
            put("source_version", message.version)
            put("source_timestamp_us", message.timestampMicros)
        }
        val text = mutableListOf<String>()
        var media = 0
        if (message.role == "system") {
            text += "[Kelivo 原系统消息；聊天迁移不导入其正文或配置]"
        } else {
            val parts = source.parts(message.id)
            require(parts.size <= KelivoChatLimits.MAX_PARTS_PER_MESSAGE) { "kelivo_parts_count" }
            for (part in parts) {
                checkCancelled()
                require(part.payload.toByteArray(Charsets.UTF_8).size <= KelivoChatLimits.MAX_PART_BYTES) { "kelivo_part_size" }
                when (part.kind) {
                    "text" -> text += inertKelivoText(part.payload)
                    "reasoning", "thinking" -> text += "[Kelivo 历史思考，仅作记录]\n${inertKelivoText(part.payload)}"
                    "tool_call", "tool_result", "toolCall", "toolResult" ->
                        text += "[Kelivo 历史工具记录；不会执行或恢复授权]\n${inertKelivoText(part.payload)}"
                    "image", "file", "attachment", "audio", "video" -> media++
                    else -> text += "[Kelivo 暂不支持的历史内容类型；请在原应用中查看，未执行]"
                }
            }
            message.reasoning?.takeIf { it.isNotBlank() && it != "[]" && it != "null" }?.let {
                require(it.toByteArray(Charsets.UTF_8).size <= KelivoChatLimits.MAX_PART_BYTES) { "kelivo_reasoning_size" }
                text += "[Kelivo 历史思考分段，仅作记录]\n${inertKelivoText(it)}"
            }
        }
        val missing = maxOf(media, message.attachmentReferences)
        omitted += missing
        if (missing > 0) text += "[Kelivo 此消息包含 $missing 个历史附件引用；本次不复制、读取或下载附件实体]"
        if (message.role == "tool") text.add(0, "[Kelivo 历史工具结果，仅作记录；不会执行]")
        if (text.isEmpty() || text.all { it.isEmpty() }) text += "[Kelivo 原消息没有可导入的正文；未续传或重试]"
        val time = kotlin.time.Instant.parse(kelivoTimestamp(message.timestampMicros).toString())
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val ui = UIMessage(id = kelivoImportId("message", chat.id, message.id),
            role = if (message.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
            parts = text.map { UIMessagePart.Text(it, metadata = metadata) }, createdAt = time, finishedAt = time)
        val encoded = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
        require(encoded <= DeepSeekChatImporter.MAX_NODE_JSON_BYTES) { "kelivo_node_size" }
        bytes += encoded
        require(bytes <= KelivoChatLimits.MAX_WINDOW_BYTES) { "kelivo_window_size" }
        MessageNode(id = kelivoImportId("node", chat.id, message.id), messages = listOf(ui))
    }
    return ConvertedKelivo(Conversation(id = kelivoImportId("conversation", chat.id), assistantId = assistantId,
        title = chat.title, createAt = kelivoTimestamp(chat.createdMicros), updateAt = kelivoTimestamp(chat.updatedMicros),
        messageNodes = nodes), omitted)
}

// ImportedContentPolicy also disables active Markdown/HTML. Strip embedded attachment paths defensively;
// this only changes migrated presentation, never the source archive. Ordinary historical text is retained.
internal fun inertKelivoText(text: String): String = text
    .replace(Regex("\\[(?:image|file|audio|video):[^\\]\\r\\n]*]", RegexOption.IGNORE_CASE), "[Kelivo 历史附件引用；未导入]")
    .replace(Regex("!\\[[^\\]\\r\\n]*](?:\\([^\\r\\n]*?\\)|\\[[^\\]\\r\\n]*])?"), "[Kelivo 历史图片引用；未加载]")
    .replace(Regex("<(?:img|audio|video|source|iframe)\\b[^>]*>", RegexOption.IGNORE_CASE), "[Kelivo 历史媒体引用；未加载]")

internal fun inspectKelivoSource(source: KelivoChatSource, fingerprint: String,
    checkCancelled: () -> Unit = {}): KelivoChatPreview {
    val chats = source.conversations()
    require(chats.size in 1..10000 && chats.map { it.id }.distinct().size == chats.size) { "kelivo_chats" }
    var omitted = 0
    val neutralAssistant = Uuid.parse("00000000-0000-0000-0000-000000000000")
    val previews = chats.map { chat ->
        checkCancelled()
        val messages = source.messages(chat.id)
        val selected = selectedKelivoMessages(chat, messages)
        // Preflight every chosen message's eventual storage size before any conversation is committed.
        val converted = convertKelivoChat(source, chat, neutralAssistant, checkCancelled)
        omitted += converted.omittedAttachments
        val updated = kelivoTimestamp(chat.updatedMicros)
        DeepSeekConversationPreview(chat.id, chat.title, kelivoTimestamp(chat.createdMicros), updated,
            messages.size, selected.size, messages.groupBy { it.group ?: it.id }.count { it.value.size > 1 },
            listOf(DeepSeekBranchPreview("selected", selected.size, updated, true)), "selected", "kelivo_selected_versions")
    }
    return KelivoChatPreview(fingerprint, previews, listOf(
        "只追加当前选中的回答；未选中的版本仍在原 ZIP。重复导入同一会话会跳过，不覆盖现有窗口。",
        "不导入设置、密钥、人格、系统提示、记忆、技能、工作区或权限；工具记录仅为文字。",
        "附件实体不导入、不自动下载；明确引用显示占位。此全量备份可能含凭据，请勿公开分享。"
    ), omitted)
}

internal suspend fun importSelectedKelivoChats(source: KelivoChatSource, preview: KelivoChatPreview,
    selections: Set<String>, assistantId: Uuid, sink: DeepSeekImportSink,
    onProgress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
    var result = DeepSeekImportResult()
    var active: String? = null
    try {
        require(selections.isNotEmpty() && selections.all { wanted -> preview.conversations.any { it.sourceId == wanted } })
        var completed = 0
        onProgress(DeepSeekImportProgress(selections.size, completed, result))
        for (chat in source.conversations()) {
            if (chat.id !in selections) continue
            currentCoroutineContext().ensureActive()
            active = chat.id
            if (sink.exists(kelivoImportId("conversation", chat.id))) result = result.copy(skipped = result.skipped + 1)
            else {
                val coroutine = currentCoroutineContext()
                val converted = convertKelivoChat(source, chat, assistantId) { coroutine.ensureActive() }
                coroutine.ensureActive()
                withContext(NonCancellable) {
                    result = if (sink.insert(converted.conversation)) result.copy(imported = result.imported + 1,
                        messages = result.messages + converted.conversation.messageNodes.size,
                        attachmentReferences = result.attachmentReferences + converted.omittedAttachments)
                    else result.copy(skipped = result.skipped + 1)
                }
                coroutine.ensureActive()
            }
            completed++
            active = null
            onProgress(DeepSeekImportProgress(selections.size, completed, result))
        }
        require(completed == selections.size)
        return result
    } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
    catch (_: Exception) {
        if (active != null) result = result.copy(failed = result.failed + 1,
            failures = result.failures + DeepSeekImportFailure(active, "该会话未完成；已完成会话保留，现有聊天未覆盖"))
        throw DeepSeekImportException(result)
    }
}
