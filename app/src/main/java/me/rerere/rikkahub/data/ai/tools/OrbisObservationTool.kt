package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.RescueWindowMetadata

internal fun observationWindowsTool(
    enabled: () -> Boolean,
    read: suspend (Int, Int) -> List<RescueWindowMetadata>,
) = Tool(
    name = "orbis_list_windows",
    description = "分页列出当前助手的所有普通对话框名称与ID。limit默认20最大100，offset从0开始；has_more为true时继续下一页。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("limit", buildJsonObject { put("type", "integer") })
        put("offset", buildJsonObject { put("type", "integer") })
    }) },
    execute = { args ->
        val payload = if (!enabled()) buildJsonObject { put("status", "observation_disabled") } else {
            val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
            val offset = (args.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 1_000_000)
            val rows = read(limit + 1, offset)
            if (!enabled()) buildJsonObject { put("status", "observation_disabled") } else buildJsonObject {
                put("windows", buildJsonArray { rows.take(limit).forEach { row -> add(buildJsonObject {
                    put("id", row.id); put("title", row.title)
                }) } })
                put("has_more", rows.size > limit); put("next_offset", offset + minOf(rows.size, limit))
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
