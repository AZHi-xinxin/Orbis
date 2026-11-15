package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

const val ORBIS_MEMORY_TOOL = "orbis_memory"

/** Linked libraries and replay keys are host-bound; arbitrary owner input grants no access. */
internal fun buildOrbisMemoryTool(
    assistantId: String,
    assistantExists: () -> Boolean,
    readOnly: Boolean = false,
    libraries: () -> Map<String, String> = { mapOf(assistantId to "当前助手") },
    executeMemory: suspend (String, JsonObject, String) -> JsonObject,
) = Tool(
    name = ORBIS_MEMORY_TOOL,
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_memory") + if (readOnly) "\n本次参考会话仅允许 read/history，不允许改写。" else "",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        fun field(name: String, type: String, description: String) = put(name, buildJsonObject {
            put("type", type); put("description", description)
        })
        field("action", "string", "store、read（无正文时默认）、update、delete、set_state、restore、history、libraries（已连通库列表）")
        field("id", "string", "已有便签 ID；范围由人类的跨助手记忆开关决定")
        field("body", "string", "完整正文；只有存入时必填，可长于300字")
        field("summary", "string", "可选，由你写的带入摘要，不影响完整正文")
        put("emotion", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
            put("enum", JsonArray(me.rerere.rikkahub.data.orbis.memory.OrbisMemoryEmotions.labels.map(::JsonPrimitive) + JsonNull)) })
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
        field("library_id", "string", "可选：libraries 返回的已连通库 ID；不填时查询全部已连通库，新记忆存当前助手库")
    }) },
    execute = { input ->
        val response = try {
            val request = normalizeOrbisMemoryArguments(input)
            val action = request["action"]?.jsonPrimitive?.content ?: "store"
            when {
                !assistantExists() -> memoryToolError("assistant_unavailable", "助手已不存在，未改写。")
                action == "libraries" -> buildJsonObject {
                    put("ok", true)
                    put("libraries", buildJsonArray {
                        libraries().forEach { (id, name) ->
                            add(buildJsonObject { put("assistantId", id); put("name", name) })
                        }
                    })
                }
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
