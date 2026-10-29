package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.boundary.AssistantBoundary

/** Explicit fields matter here: serialization defaults must not hide a closed request entrance. */
internal fun boundaryStateOutput(state: AssistantBoundary, conversation: String?, now: Long) = buildJsonObject {
    put("revision", state.revision)
    put("now_epoch_ms", now)
    put("grants", JsonArray(state.grants.map(::JsonPrimitive)))
    val pause = state.pause(conversation, now)
    put("paused", pause != null)
    put("conversation", (pause ?: state.conversations[conversation])?.let {
        buildJsonObject {
            put("paused", pause != null)
            put("reason", it.reason)
            put("scope", it.scope)
            put("until_epoch_ms", it.until?.let(::JsonPrimitive) ?: JsonNull)
            put("allow_request", it.allowRequest)
            put("request_used", it.requestUsed)
            put("request_count", it.requestCount)
            put("request_limit", me.rerere.rikkahub.data.orbis.boundary.BOUNDARY_REQUEST_LIMIT)
            put("requests_remaining", it.requestsRemaining)
            put("revision", it.revision)
        }
    } ?: JsonNull)
    put("assistant_paused", state.activeAssistantPause(now) != null)
    put("human_request", when {
        pause == null -> "当前对话未暂停。"
        !pause.allowRequest -> "你未开放继续申请，聊天底部暂停卡不会显示“申请继续”按钮。"
        pause.requestUsed -> "继续申请正在处理；每次暂停最多3次申请，结束后还有剩余次数时可再次申请。"
        pause.requestsRemaining == 0 -> "本次暂停的3次继续申请已用完，人类等待约定时间自动恢复。"
        else -> "人类回到当前聊天底部，在“此对话已由对方暂停”卡片点“申请继续”，填写后提交；需要有可用模型。"
    })
    put("selected_model_override", state.modelOverride?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun boundaryCatalogPage(
    state: AssistantBoundary,
    kind: String,
    catalog: Map<String, Map<String, String>>,
    sources: Map<String, String>,
    query: String = "",
    mcpId: String? = null,
    offset: Int = 0,
    limit: Int = 10,
): JsonObject {
    require(kind in setOf("model", "mcp", "tool")) { "boundary_catalog_kind_invalid" }
    require(offset >= 0 && limit in 1..20) { "boundary_page_invalid" }
    require(mcpId == null || kind == "tool") { "boundary_mcp_filter_requires_tool" }
    val entries = catalog[kind].orEmpty().entries.filter { (id, label) ->
        (query.isBlank() || id.contains(query, ignoreCase = true) || label.contains(query, ignoreCase = true)) &&
            (mcpId == null || sources[id] == mcpId)
    }
    val page = entries.drop(offset).take(limit).map { (id, label) -> buildJsonObject {
        put("id", id); put("label", label.take(100))
        put("enabled", state.permits(kind, id)); put("hidden", state.hidden(kind, id))
        if (kind == "mcp") {
            val ids = sources.filterValues { it == id }.keys
            put("tool_ids", JsonArray(ids.take(5).map(::JsonPrimitive)))
            put("tool_count", ids.size)
            put("tool_ids_has_more", ids.size > 5)
        }
        if (kind == "tool") {
            val source = sources[id]
            put("source_kind", if (source != null) "mcp" else if (id.startsWith("cloud_")) "native_cloud" else "native")
            put("mcp_id", source?.let(::JsonPrimitive) ?: JsonNull)
            put("effective_enabled", state.permits("tool", id) && (source == null || state.permits("mcp", source)))
        }
    } }
    return buildJsonObject {
        put("choices", buildJsonObject { put(kind, JsonArray(page)) })
        put("page", buildJsonObject {
            put("kind", kind); put("offset", offset); put("limit", limit)
            put("total", entries.size); put("returned", page.size)
            put("next_offset", if (offset + page.size < entries.size) JsonPrimitive(offset + page.size) else JsonNull)
        })
    }
}
