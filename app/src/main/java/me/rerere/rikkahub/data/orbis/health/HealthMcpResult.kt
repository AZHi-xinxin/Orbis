package me.rerere.rikkahub.data.orbis.health

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId

internal data class HealthCloudBatch(
    val days: Map<String, Map<String, JsonPrimitive>>,
    val dailySummary: Boolean = false,
) {
    val provenance: String get() = if (dailySummary)
        "已授权设备同步 · 步数按来源标注日期保存，不转换时区；日内数据可能尚未完整"
    else "已授权设备同步 · 按原始时间戳和手机时区归日"
    val notice: String get() = if (dailySummary)
        "；按来源日期同步步数，未将日均心率当作单次测量，未补造睡眠数据" else ""
}

/** Never scrape Markdown or replace missing measurements with zero. */
internal fun parseHealthMcpResult(
    result: CallToolResult, zone: ZoneId, from: LocalDate, through: LocalDate,
): HealthCloudBatch {
    check(result.isError != true) { "health_sync_tool_error" }
    val structured = result.structuredContent
    val text = if (structured != null) structured.toString() else {
        val content = result.content.singleOrNull() as? TextContent ?: error("health_cloud_format")
        content.text
    }
    require(text.length <= 2 * 1024 * 1024 && !through.isBefore(from) &&
        through.toEpochDay() - from.toEpochDay() <= 7) { "health_cloud_range" }
    val root = runCatching { healthJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: error("health_cloud_format")
    if (root["data"] is JsonArray) return HealthCloudBatch(parseHealthCloudData(text, zone, from, through))
    val data = root["data"] as? JsonObject ?: error("health_cloud_format")
    require(data.size <= 100) { "health_cloud_limit" }
    val days = linkedMapOf<String, Map<String, JsonPrimitive>>()
    data.forEach { (dateText, value) ->
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        require(date != null && date.toString() == dateText) { "health_cloud_format" }
        val row = value as? JsonObject ?: error("health_cloud_format")
        if (date !in from..through) return@forEach
        val steps = row["steps"]?.takeUnless { it == JsonNull } ?: return@forEach
        val primitive = steps as? JsonPrimitive ?: error("health_cloud_format")
        val count = primitive.intOrNull
        require(!primitive.isString && count != null && count in 0..200_000) { "health_cloud_format" }
        // These are source-day totals. Re-sync replaces this field, never adds it again.
        days[dateText] = mapOf("steps" to JsonPrimitive(count))
    }
    return HealthCloudBatch(days, dailySummary = true)
}
