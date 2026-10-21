package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.OrbisHistoryRemovedInfo
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** The host fixes both identities. Results are quoted JSON text, never provider tool-call parts. */
internal fun createOrbisConversationHistoryTool(
    owner: Uuid,
    window: Uuid,
    readConversation: suspend () -> Conversation?,
    listRemoved: suspend (Conversation, Int, Int) -> List<OrbisHistoryRemovedInfo>,
    readRemoved: suspend (Conversation, String) -> UIMessage?,
    enabled: () -> Boolean,
    verifyCurrent: suspend (Conversation) -> Unit,
): Tool = Tool(
    name = "orbis_current_history",
    description = "按需只读当前对话的非当前回复分支或已删除记录；不查其他窗口或助手。" +
        "action=list，source=alternatives/deleted，limit默认20最大50，offset分页；只列ID和时间。" +
        "action=read，加message_id读取正文与历史工具记录，text_offset/max_chars分段，返回quoted_json文本。" +
        "对应原轮次被压缩移出后删除缓存清除。不会把旧回复放回对话或重跑旧工具。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "read").map(::JsonPrimitive))) })
        put("source", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("alternatives", "deleted").map(::JsonPrimitive))) })
        put("message_id", buildJsonObject { put("type", "string") })
        put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 50) })
        put("text_offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("max_chars", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 16000) })
    }, required = listOf("action", "source")) },
    needsApproval = { true },
    hostApproval = HostToolApproval("orbis:current_history:$window", "current-history-v1:$owner",
        "读取本窗口的非当前回复和已删除记录"),
    isApprovalCurrent = enabled,
    execute = { input ->
        check(enabled()) { "history_tool_not_enabled" }
        val args = input as? JsonObject ?: error("history_invalid_arguments")
        require(args.toString().length <= 4096) { "history_invalid_arguments" }
        fun string(name: String) = (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun integer(name: String, fallback: Int, range: IntRange): Int {
            if (name !in args) return fallback
            val value = (args[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            require(value != null && value in range) { "history_invalid_arguments" }
            return value
        }
        fun bound(value: Conversation?): Conversation {
            check(value != null && value.id == window && value.assistantId == owner && !value.isConsultation) {
                "history_scope_changed"
            }
            return value
        }
        val current = bound(readConversation())
        verifyCurrent(current)
        val action = string("action")
        val source = string("source")
        require(source in setOf("alternatives", "deleted")) { "history_invalid_source" }
        val alternatives = current.messageNodes.flatMap { node -> node.messages.mapIndexedNotNull { index, message ->
            if (index == node.selectIndex) null else node.id.toString() to message
        } }
        val activeIds = current.messageNodes.flatMap { it.messages }.map { it.id.toString() }.toSet()
        val result = when (action) {
            "list" -> {
                require(args.keys.all { it in setOf("action", "source", "offset", "limit") }) { "history_invalid_arguments" }
                val offset = integer("offset", 0, 0..1_000_000)
                val limit = integer("limit", 20, 1..50)
                val rows = if (source == "alternatives") alternatives.drop(offset).take(limit + 1).map { (node, message) ->
                    OrbisHistoryRemovedInfo(message.id.toString(), node, message.role.name, message.createdAt.toString(), 0L)
                } else listRemoved(current, limit + 1, offset)
                val visible = rows.take(limit).filter { source != "deleted" || it.messageId !in activeIds }
                buildJsonObject {
                    put("status", "ok"); put("source", source)
                    put("messages", buildJsonArray { visible.forEach { row -> add(buildJsonObject {
                        put("message_id", row.messageId); put("node_id", row.sourceNodeId)
                        put("role", row.role); put("created_at", row.createdAt)
                        if (source == "deleted") put("removed_at_ms", row.removedAt)
                    }) } })
                    put("has_more", rows.size > limit); put("next_offset", offset + minOf(rows.size, limit))
                }
            }
            "read" -> {
                require(args.keys.all { it in setOf("action", "source", "message_id", "text_offset", "max_chars") }) { "history_invalid_arguments" }
                val id = string("message_id") ?: error("history_invalid_id")
                require(runCatching { Uuid.parse(id).toString() == id }.getOrDefault(false)) { "history_invalid_id" }
                val offset = integer("text_offset", 0, 0..Int.MAX_VALUE)
                val length = integer("max_chars", 8000, 1..16000)
                val message = if (source == "alternatives") alternatives.firstOrNull { it.second.id.toString() == id }?.second
                    else if (id in activeIds) null else readRemoved(current, id)
                if (message == null) buildJsonObject { put("status", "not_found") } else {
                    check(message.id.toString() == id) { "history_message_changed" }
                    val quoted = buildJsonObject {
                        put("role", message.role.name); put("created_at", message.createdAt.toString())
                        put("parts", JsonInstant.encodeToJsonElement(message.parts))
                    }.toString()
                    val end = (offset.toLong() + length).coerceAtMost(quoted.length.toLong()).toInt()
                    require(offset <= quoted.length) { "history_offset_out_of_range" }
                    buildJsonObject {
                        put("status", "ok"); put("message_id", id); put("source", source)
                        put("quoted_json", quoted.substring(offset, end))
                        put("total_chars", quoted.length); put("next_text_offset", end); put("has_more", end < quoted.length)
                    }
                }
            }
            else -> error("history_invalid_action")
        }
        val after = bound(readConversation())
        verifyCurrent(after)
        // A same-run tool completion can update a body, but not change its tree/epoch or selected branch.
        check(enabled() && after.compactionEpoch == current.compactionEpoch &&
            after.messageNodes.map { it.id to (it.selectIndex to it.messages.map { message -> message.id }) } ==
            current.messageNodes.map { it.id to (it.selectIndex to it.messages.map { message -> message.id }) }) {
            "history_scope_changed"
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
