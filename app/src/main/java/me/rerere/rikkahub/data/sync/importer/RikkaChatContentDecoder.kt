package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A foreign chat is data, not a serialized application session. Project only conversation
 * content into new objects so added model/provider/approval fields cannot restore runtime state.
 * Database structure, source/target byte limits and branch identity checks live at the caller.
 */
internal object RikkaChatContentDecoder {
    const val IMPORT_SOURCE = "rikka_chat_v1"
    const val SYSTEM_PLACEHOLDER = "[此处为原备份的系统设定；仅聊天导入未包含其正文]"
    private const val UNKNOWN_PLACEHOLDER = "[原备份含暂不支持的内容；已保留可识别文字，其余内容请查看原备份]"
    private val epoch = LocalDateTime(1970, 1, 1, 0, 0)

    fun marker(timestampFallback: Boolean = false, generatedId: Boolean = false,
        unknownPart: Boolean = false): JsonObject = buildJsonObject {
        put("import_source", IMPORT_SOURCE)
        if (timestampFallback) put("timestamp_fallback", true)
        if (generatedId) put("source_id_generated", true)
        if (unknownPart) put("unsupported_content", true)
    }

    fun decode(nodeId: String, source: String): List<UIMessage> {
        val rows = try { DeepSeekStrictJson.parse(source) as? JsonArray ?: invalid() }
        catch (failure: ArchiveReadException) { throw failure }
        catch (_: Exception) { invalid() }
        if (rows.size > RikkaChatLimits.MAX_NODE_BRANCHES) throw ArchiveReadException(ArchiveFailure.NODE_LIMIT)
        return rows.mapIndexed { index, element -> message(nodeId, index, element as? JsonObject ?: invalid()) }
    }

    private fun message(nodeId: String, index: Int, value: JsonObject): UIMessage {
        val sourceId = value["id"]
        val generatedId = sourceId == null || sourceId == JsonNull
        val id = if (generatedId) rikkaImportId("missing-source-message", "$nodeId/$index") else {
            val text = string(sourceId) ?: invalid()
            requireRikkaSourceId(text)
            Uuid.parse(text)
        }
        val sourceRole = requiredString(value, "role").lowercase(Locale.ROOT)
        val role = when (sourceRole) {
            "user" -> MessageRole.USER
            "assistant", "tool" -> MessageRole.ASSISTANT
            "system", "developer" -> MessageRole.SYSTEM
            else -> invalid()
        }
        val sourceParts = value["parts"] as? JsonArray ?: invalid()
        val createdAt = date(value["createdAt"])
        val metadata = marker(timestampFallback = createdAt == null, generatedId = generatedId)
        val actualDate = createdAt ?: epoch
        val parts = if (role == MessageRole.SYSTEM) {
            // Never even inspect a system prompt's content or attachments for projection.
            listOf(UIMessagePart.Text(SYSTEM_PLACEHOLDER, metadata))
        } else buildList<UIMessagePart> {
            if (sourceRole == "tool") add(UIMessagePart.Text("[历史工具消息；仅保留内容，未执行]", metadata))
            sourceParts.forEach { add(part(it as? JsonObject ?: invalid(), metadata, actualDate)) }
        }
        return UIMessage(id = id, role = role, parts = parts, createdAt = actualDate,
            finishedAt = date(value["finishedAt"]))
    }

    private fun part(value: JsonObject, metadata: JsonObject, messageDate: LocalDateTime): UIMessagePart {
        return when (string(value["type"])?.lowercase(Locale.ROOT)) {
            "text" -> UIMessagePart.Text(requiredString(value, "text"), metadata)
            "reasoning" -> {
                val fallback = Instant.parse(java.time.LocalDateTime.parse(messageDate.toString())
                    .toInstant(ZoneOffset.UTC).toString())
                val createdAt = instant(value["createdAt"]) ?: fallback
                UIMessagePart.Reasoning(requiredString(value, "reasoning"), createdAt,
                    instant(value["finishedAt"]) ?: createdAt, metadata,
                    if (string(value["reasoningType"]) == "summary_text") ReasoningType.SUMMARY_TEXT
                    else ReasoningType.REASONING_TEXT)
            }
            "image", "video", "audio", "document" -> {
                val url = requiredString(value, "url")
                remoteAttachmentReference(url, metadata) ?: when (string(value["type"])?.lowercase(Locale.ROOT)) {
                    "image" -> UIMessagePart.Image(url, metadata)
                    "video" -> UIMessagePart.Video(url, metadata)
                    "audio" -> UIMessagePart.Audio(url, metadata)
                    else -> UIMessagePart.Document(url, string(value["fileName"]) ?: "历史附件",
                        string(value["mime"]) ?: "application/octet-stream", metadata)
                }
            }
            "tool", "tool_call", "tool_result", "server_tool" -> UIMessagePart.Text(toolText(value), metadata)
            else -> {
                // Future parts may contain useful prose alongside arbitrary session metadata.
                // Keep only explicitly recognized strings, never serialize the unknown object.
                val body = listOf("text", "reasoning", "content").mapNotNull { string(value[it]) }
                    .joinToString("\n")
                UIMessagePart.Text(if (body.isEmpty()) UNKNOWN_PLACEHOLDER else "$body\n$UNKNOWN_PLACEHOLDER",
                    JsonObject(metadata + ("unsupported_content" to JsonPrimitive(true))))
            }
        }
    }

    /** No structured remote media: some native/web media views load such URLs on render. */
    fun remoteAttachmentReference(url: String, metadata: JsonObject? = null): UIMessagePart.Text? {
        val scheme = url.trimStart()
        if (!scheme.startsWith("https://", ignoreCase = true) && !scheme.startsWith("http://", ignoreCase = true)) return null
        return UIMessagePart.Text("[历史附件引用；未下载]\n$url",
            JsonObject((metadata ?: marker()) + ("import_source" to JsonPrimitive(IMPORT_SOURCE))))
    }

    private fun toolText(value: JsonObject): String = buildString {
        val name = string(value["toolName"]) ?: "未命名工具"
        append("[历史工具记录：").append(name).append("；仅保留文字，未执行]")
        for ((key, title) in listOf("input" to "输入", "arguments" to "参数", "output" to "输出", "content" to "结果")) {
            val payload = value[key] ?: continue
            if (payload == JsonNull) continue
            append('\n').append(title).append(":\n").append(
                if (key == "output" && string(value["type"])?.lowercase(Locale.ROOT) == "tool")
                    toolParts(payload)
                else payloadString(payload))
        }
    }

    /** Business payloads may themselves have a `type`; never mistake those for serialized parts. */
    private fun payloadString(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /** The old Tool.output field is a list of serialized UIMessageParts, not arbitrary result JSON. */
    private fun toolParts(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("\n") { toolParts(it) }
        is JsonObject -> {
            // Do not import metadata/approvals or turn media URLs into live attachments.
            val text = listOf("text", "reasoning", "content").mapNotNull { string(value[it]) }.joinToString("\n")
            when {
                text.isNotEmpty() -> text
                string(value["url"]) != null -> "[历史工具附件；请在原备份查看]"
                else -> "[非文本历史工具内容；请在原备份查看]"
            }
        }
    }

    private fun date(value: JsonElement?): LocalDateTime? {
        val text = string(value) ?: return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime().toString()) }.getOrNull()
    }

    private fun instant(value: JsonElement?): Instant? = string(value)?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }

    private fun requiredString(value: JsonObject, key: String): String = string(value[key]) ?: invalid()
    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw IllegalArgumentException("备份中的消息结构暂不兼容或已损坏；原聊天未更改")
}
