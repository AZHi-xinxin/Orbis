package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.net.URLConnection

internal fun deepSeekTimestamp(value: JsonElement?): Instant? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?.takeIf { time -> time.epochSecond in 0..253402300799L }
    }

internal fun deepSeekMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "csv" -> "text/csv"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}

fun UIMessage.deepSeekTimeUnknown(): Boolean = parts.any {
    it.metadata?.get("import_source") == JsonPrimitive("deepseek") &&
        it.metadata?.get("source_timestamp_unknown") == JsonPrimitive(true)
}

/** UI-only projection: the stored/request part stays inert historical text, never a tool call. */
internal fun UIMessagePart.Text.deepSeekHistoryTool(): UIMessagePart.ServerTool? {
    val raw = metadata?.get("deepseek_history_tool") as? JsonObject ?: return null
    if (metadata?.get("import_source") != JsonPrimitive("deepseek")) return null
    return UIMessagePart.ServerTool(
        toolCallId = "deepseek-history",
        toolName = "历史记录 · " + ((raw["type"] as? JsonPrimitive)?.content ?: "检索"),
        input = JsonObject(raw.filterKeys { it in setOf("queries", "url", "pattern") }),
        output = raw,
        status = if ((raw["status"] as? JsonPrimitive)?.content in setOf("FAILED", "ERROR")) ServerToolStatus.FAILED else ServerToolStatus.COMPLETED,
    )
}
