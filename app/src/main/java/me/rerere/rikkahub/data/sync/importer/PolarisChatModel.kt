package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

data class PolarisChatPreview(
    val fingerprint: String, val conversations: List<DeepSeekConversationPreview>,
    val warnings: List<String> = emptyList(),
)

internal data class PolarisMessage(
    val id: String, val role: String, val content: String, val timestampMillis: Long,
    val thinkingText: String?, val attachmentKinds: List<String>,
)
internal data class PolarisConversation(
    val id: String, val title: String, val updatedMillis: Long,
    val messages: List<PolarisMessage>, val omittedSystemMessages: Int,
)
internal data class ConvertedPolaris(val conversation: Conversation, val attachmentReferences: Int)

internal object PolarisChatLimits {
    const val MAX_CONVERSATIONS = 2000
    const val MAX_TOTAL_MESSAGES = 100000
    const val MAX_WINDOW_MESSAGES = 50000
    const val MAX_TEXT_BYTES = 512 * 1024
    const val MAX_WINDOW_BYTES = 32L * 1024 * 1024
}

internal fun polarisImportId(kind: String, vararg identities: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes(("orbis-polaris-export-v1/$kind/" +
        identities.joinToString("") { "${it.length}:$it" }).toByteArray(Charsets.UTF_8)).toString())

internal fun polarisTimestamp(millis: Long): Instant {
    require(millis in 0..253402300799999L) { "polaris_timestamp" }
    return Instant.ofEpochMilli(millis)
}

private fun requirePolarisId(id: String) {
    require(id.isNotBlank() && id.length <= 256 && id.none { it.code < 32 || it.code == 127 }) {
        "polaris_id"
    }
}
private fun JsonObject.text(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("polaris_message_string")
private fun JsonObject.number(key: String): Long =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("polaris_message_number")

/** Parse only the stable chat projection. Prompt, task, tool ledger and all settings stay in the ZIP. */
internal fun parsePolarisConversation(raw: JsonObject): PolarisConversation {
    val id = raw.text("id").also(::requirePolarisId)
    val kind = raw.text("kind")
    val title = raw.text("title").also { require(it.toByteArray(Charsets.UTF_8).size <= 4096) }
    val updated = raw.number("updatedAt").also(::polarisTimestamp)
    val array = raw["messages"] as? JsonArray ?: error("polaris_messages_array")
    require(array.size <= PolarisChatLimits.MAX_WINDOW_MESSAGES) { "polaris_window_messages" }
    val ids = hashSetOf<String>()
    var omittedSystem = 0
    val messages = array.mapNotNull { element ->
        val message = element as? JsonObject ?: error("polaris_message_object")
        val messageId = message.text("id").also(::requirePolarisId)
        require(ids.add(messageId)) { "polaris_duplicate_message" }
        val role = message.text("role")
        val timestamp = message.number("timestamp").also(::polarisTimestamp)
        val content = message.text("content")
        require(content.toByteArray(Charsets.UTF_8).size <= PolarisChatLimits.MAX_TEXT_BYTES) {
            "polaris_content_size"
        }
        val thinking = when (val value = message["thinkingText"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content ?: error("polaris_thinking_type")
            else -> error("polaris_thinking_type")
        }?.also { require(it.toByteArray(Charsets.UTF_8).size <= PolarisChatLimits.MAX_TEXT_BYTES) }
        val attachments = when (val value = message["attachments"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> {
                require(value.size <= 64) { "polaris_attachment_count" }
                value.map { attachment ->
                    val kind = (attachment as? JsonObject)?.text("kind") ?: error("polaris_attachment_type")
                    require(kind.length in 1..128) { "polaris_attachment_type" }
                    kind
                }
            }
            else -> error("polaris_attachment_type")
        }
        when (role) {
            "user", "assistant" -> PolarisMessage(messageId, role, content, timestamp, thinking, attachments)
            "system" -> { omittedSystem++; null }
            else -> error("polaris_message_role")
        }
    }
    // Group-room state and runtime messages are not imported as assistant conversations.
    require(kind == "direct") { "polaris_unsupported_conversation_kind" }
    return PolarisConversation(id, title, updated, messages, omittedSystem)
}

internal fun inertPolarisText(text: String): String = text
    .replace(Regex("\\[(?:image|file|audio|video):[^\\]\\r\\n]*]", RegexOption.IGNORE_CASE),
        "[北极星历史附件引用；未导入]")
    .replace(Regex("!\\[[^\\]\\r\\n]*](?:\\([^\\r\\n]*?\\)|\\[[^\\]\\r\\n]*])?"),
        "[北极星历史图片引用；未加载]")
    .replace(Regex("<(?:img|audio|video|source|iframe)\\b[^>]*>", RegexOption.IGNORE_CASE),
        "[北极星历史媒体引用；未加载]")

private fun polarisUiMessage(chat: PolarisConversation, source: PolarisMessage): Pair<UIMessage, Int> {
    val time = polarisTimestamp(source.timestampMillis)
    val local = kotlin.time.Instant.parse(time.toString()).toLocalDateTime(TimeZone.currentSystemDefault())
    val metadata = buildJsonObject {
        put("import_source", "polaris_export_v1")
        put("source_conversation_id", chat.id)
        put("source_message_id", source.id)
        put("source_timestamp_ms", source.timestampMillis)
    }
    val body = inertPolarisText(source.content)
    val thought = source.thinkingText?.let(::inertPolarisText)?.takeIf { it.isNotBlank() }
    val references = maxOf(source.attachmentKinds.size,
        if (body != source.content || thought != source.thinkingText && thought != null) 1 else 0)
    val parts = buildList<UIMessagePart> {
        if (source.role == "assistant" && thought != null) add(UIMessagePart.Reasoning(thought,
            kotlin.time.Instant.parse(time.toString()), kotlin.time.Instant.parse(time.toString()), metadata))
        add(UIMessagePart.Text(body, metadata = metadata))
        if (source.attachmentKinds.isNotEmpty()) {
            val counts = source.attachmentKinds.groupingBy { kind ->
                when (kind) {
                    "image" -> "图片"
                    "audio" -> "音频"
                    "video" -> "视频"
                    "file" -> "文件"
                    else -> "附件"
                }
            }.eachCount()
            val label = counts.entries.joinToString("、") { (kind, count) -> "$kind $count" }
            add(UIMessagePart.Text("[北极星历史附件：$label；未导入文件实体，也不会自动下载]", metadata = metadata))
        }
    }
    val ui = UIMessage(id = polarisImportId("message", chat.id, source.id),
        role = if (source.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
        parts = parts, createdAt = local, finishedAt = local)
    return ui to references
}

/** Preflight every stored node before a selected window can be committed. */
internal fun preflightPolarisConversation(chat: PolarisConversation, checkCancelled: () -> Unit = {}): Int {
    require(chat.messages.size <= PolarisChatLimits.MAX_WINDOW_MESSAGES)
    var bytes = 0L
    var references = 0
    for (message in chat.messages) {
        checkCancelled()
        val (ui, omitted) = polarisUiMessage(chat, message)
        val size = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
        require(size <= DeepSeekChatImporter.MAX_NODE_JSON_BYTES) { "polaris_node_size" }
        bytes += size
        require(bytes <= PolarisChatLimits.MAX_WINDOW_BYTES) { "polaris_window_size" }
        references += omitted
    }
    return references
}

internal fun convertPolarisConversation(chat: PolarisConversation, assistantId: Uuid,
    checkCancelled: () -> Unit = {}): ConvertedPolaris {
    var references = 0
    var bytes = 0L
    val nodes = chat.messages.map { message ->
        checkCancelled()
        val (ui, omitted) = polarisUiMessage(chat, message)
        val size = JsonInstant.encodeToString(listOf(ui)).toByteArray(Charsets.UTF_8).size
        require(size <= DeepSeekChatImporter.MAX_NODE_JSON_BYTES) { "polaris_node_size" }
        bytes += size
        require(bytes <= PolarisChatLimits.MAX_WINDOW_BYTES) { "polaris_window_size" }
        references += omitted
        MessageNode(id = polarisImportId("node", chat.id, message.id), messages = listOf(ui))
    }
    val created = chat.messages.minOfOrNull { it.timestampMillis }?.let(::polarisTimestamp)
        ?: polarisTimestamp(chat.updatedMillis)
    return ConvertedPolaris(Conversation(id = polarisImportId("conversation", chat.id),
        assistantId = assistantId, title = chat.title, createAt = created,
        updateAt = polarisTimestamp(chat.updatedMillis), messageNodes = nodes), references)
}
