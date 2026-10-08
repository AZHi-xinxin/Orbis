package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

const val ORBIS_MEMORY_TOOL = "orbis_memory"

/** Owner and replay key are host-bound; model input cannot select another assistant's store. */
internal fun buildOrbisMemoryTool(
    assistantId: String,
    assistantExists: () -> Boolean,
    readOnly: Boolean = false,
    executeMemory: suspend (String, JsonObject, String) -> JsonObject,
) = Tool(
    name = ORBIS_MEMORY_TOOL,
    description = """
        你的本机随身便签，同一助手跨窗口可用，与 ST 和旧记忆库独立。一个工具完成存、改、删、看、设状态；不需要人类审批。
        存：只传 body 即可；可选 summary、tags、keywords、important、state。正文完整保存，默认 static（仅存），不自动提炼或改写。
        读：action=read，可用 id 或 query；支持 offset/limit 分页。改：action=update、id 和要修改的字段。删：action=delete、id，为可恢复的软删。
        状态：action=set_state、id、state=static/conditional/pinned/paused。暂停只停浮现；恢复用 action=restore（可指定 revision），版本查询 action=history、id。
        你自行决定内容、标签、摘要、关键词、重要性、状态和 minIntervalTurns（条件浮现最小人类轮次间隔）。工具可能返回词面相似候选，由你决定是否合并，不会自动合并。
        轻档完全不自动带入。标准档：常驻≤350，另有命中≤900估算token（摘要最多3条、原文最多1条）；独立档总≤2000（摘要最多5条、原文最多3条）。常驻最多7条且≤350估算token。
        条件浮现只有标签/关键词命中才带入；重要或多词命中才可带原文，每条原文≤300字。更长原文仍完整保存，请自行提供摘要供带入。没有语义检索或额外模型调用。
        停止自动带入不影响你存查。无需例行自检或为了凑配额写入。失败会返回原因，不能把失败说成成功；超限不截断原文。
    """.trimIndent() + if (readOnly) "\n本次参考会话仅允许 read/history，不允许改写。" else "",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        fun field(name: String, type: String, description: String) = put(name, buildJsonObject {
            put("type", type); put("description", description)
        })
        field("action", "string", "store（默认）、read、update、delete、set_state、restore、history")
        field("id", "string", "已有便签 ID；不能选择其他助手")
        field("body", "string", "完整正文；只有存入时必填，可长于300字")
        field("summary", "string", "可选，由你写的带入摘要，不影响完整正文")
        for (name in listOf("tags", "keywords")) put(name, buildJsonObject {
            put("type", "array"); put("items", buildJsonObject { put("type", "string") })
        })
        field("state", "string", "static、conditional、pinned、paused")
        field("important", "boolean", "命中时是否优先考虑300字以内原文")
        field("minIntervalTurns", "integer", "条件浮现间隔，0或1每次命中可浮现；重试不计轮次")
        field("query", "string", "主动查询词；不写则分页读取")
        field("offset", "integer", "分页起点")
        field("limit", "integer", "分页条数（有安全上限）")
        field("revision", "integer", "恢复到指定历史版本；不填只恢复软删/暂停")
        field("includeDeleted", "boolean", "读查询是否包括可恢复的软删记录")
    }) },
    execute = { input ->
        val response = try {
            val request = normalizeOrbisMemoryArguments(input)
            val action = request["action"]?.jsonPrimitive?.content ?: "store"
            when {
                !assistantExists() -> memoryToolError("assistant_unavailable", "助手已不存在，未改写。")
                readOnly && action !in setOf("read", "history") ->
                    memoryToolError("reference_read_only", "本次仅供参考，未改写便签。")
                else -> {
                    val invocation = currentCoroutineContext()[CloudToolInvocationContext]
                    // Only the host supplies durable replay identity. Refuse a write without it.
                    val key = invocation?.let { "${it.conversationId}:${it.messageId}:${it.toolCallId}" }
                    if (key == null && action !in setOf("read", "history"))
                        memoryToolError("invocation_missing", "缺少本次调用标识，未写入；请重新调用。")
                    else executeMemory(assistantId, request, key ?: "read-only")
                }
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            // No database path, note text, or raw exception reaches logs / error notices.
            memoryToolError("memory_unavailable", "本机便签暂不可用；未确认写入，请保留原文稍后重试同一次操作。")
        }
        listOf(UIMessagePart.Text(response.toString()))
    },
)

internal fun memoryToolError(code: String, message: String) = buildJsonObject {
    put("ok", false); put("code", code); put("message", message)
}

/** Forgiving names and optional tag formatting, without inventing omitted body or identity. */
internal fun normalizeOrbisMemoryArguments(input: JsonElement): JsonObject {
    val raw = when (input) {
        is JsonObject -> input
        is JsonPrimitive -> if (input.isString) buildJsonObject { put("body", input.content) }
            else return buildJsonObject { put("action", "invalid") }
        else -> return buildJsonObject { put("action", "invalid") }
    }
    val values = raw.toMutableMap()
    fun alias(target: String, vararg aliases: String) {
        if (target !in values) aliases.firstNotNullOfOrNull { raw[it] }?.let { values[target] = it }
        aliases.forEach(values::remove)
    }
    alias("action", "op", "operation")
    alias("body", "content", "text")
    alias("id", "note_id", "memory_id")
    alias("minIntervalTurns", "min_interval_turns")
    alias("includeDeleted", "include_deleted")
    val action = (values["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        ?: if ("body" in values) "store" else "read"
    values["action"] = JsonPrimitive(when (action) {
        "create", "save", "add", "存", "存入" -> "store"
        "edit", "改", "修改" -> "update"
        "query", "search", "get", "看", "读取" -> "read"
        "remove", "删", "删除" -> "delete"
        "state", "设状态" -> "set_state"
        "恢复" -> "restore"
        else -> action
    })
    for (name in listOf("tags", "keywords")) {
        val value = values[name]
        if (value is JsonPrimitive && value.isString) {
            values[name] = JsonArray(value.content.split(',', '，', '\n', ';', '；')
                .map(String::trim).filter(String::isNotEmpty).distinct().map(::JsonPrimitive))
        } else if (value == JsonNull) values.remove(name)
    }
    for (name in listOf("important", "includeDeleted")) {
        val value = values[name] as? JsonPrimitive
        if (value?.isString == true) value.content.trim().lowercase().toBooleanStrictOrNull()
            ?.let { values[name] = JsonPrimitive(it) }
    }
    for (name in listOf("minIntervalTurns", "offset", "limit", "revision")) {
        val value = values[name] as? JsonPrimitive
        if (value?.isString == true) value.content.trim().toIntOrNull()?.let { values[name] = JsonPrimitive(it) }
    }
    listOf("summary", "important", "includeDeleted", "minIntervalTurns", "offset", "limit", "revision", "state")
        .filter { values[it] == JsonNull }.forEach(values::remove)
    // These fields must never choose the authority scope, even if a provider sends extra fields.
    listOf("assistantId", "assistant_id", "owner", "operationKey", "operation_key").forEach(values::remove)
    return JsonObject(values)
}
