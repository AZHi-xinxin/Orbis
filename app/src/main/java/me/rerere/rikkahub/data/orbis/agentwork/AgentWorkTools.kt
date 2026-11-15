package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val DISCOVERY_TOOL = "orbis_tool_discover"
internal const val TASK_TOOL = "orbis_task"
internal fun agentReply(value: JsonObject) = listOf(UIMessagePart.Text(value.toString()))
internal fun agentString(args: JsonObject, key: String, fallback: String = ""): String =
    if (key !in args) fallback else (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_parameters")
internal fun agentProperty(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

/** Read only the actual selected catalogue, including MCP. Never invoke a tool while discovering it. */
internal fun discoveryTool(catalog: () -> List<Tool>) = Tool(
    name = DISCOVERY_TOOL,
    description = "查本轮真实工具。query按名称/用途搜索，多个关键词用空白分隔，匹配任一个；offset分页；已知工具名用name精确查询并取完整参数。只读，不替你执行或开启权限。搜索零匹配不等于工具未注册；目录仅代表本轮，不证明远端可用。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("query", agentProperty("string", "用途关键词或名称，多个词用空白分隔、匹配任一个，最多80字；省略或空白列出当前目录"))
        put("name", agentProperty("string", "需要完整schema时填写精确工具名"))
        put("offset", agentProperty("integer", "搜索分页，默认0，每页10条"))
    }) },
    execute = { input ->
        try {
            val args = input as? JsonObject ?: error("invalid_parameters")
            require(args.keys.all { it in setOf("query", "name", "offset") })
            val query = agentString(args, "query"); require(query.length <= 80)
            val name = agentString(args, "name"); require(name.length <= 200)
            val offset = if ("offset" !in args) 0 else args["offset"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("invalid_parameters")
            require(offset in 0..10000)
            val all = catalog().filter { it.isSelected() }.distinctBy { it.name }.sortedBy { it.name }
            val terms = query.split(Regex("[\\s\\u3000]+")).filter { it.isNotEmpty() }.distinct()
            val found = all.filter { if (name.isNotEmpty()) it.name == name else
                terms.isEmpty() || terms.any { term -> it.name.contains(term, true) || it.description.contains(term, true) } }
                .sortedByDescending { tool -> terms.count { term ->
                    tool.name.contains(term, true) || tool.description.contains(term, true)
                } }
            agentReply(buildJsonObject {
                val missingExactName = name.isNotEmpty() && found.isEmpty()
                put("ok", !missingExactName); put("registered", all.size); put("matches", found.size)
                if (missingExactName) put("code", "tool_not_in_current_catalog")
                put("match_mode", if (name.isNotEmpty()) "exact_name" else if (terms.isEmpty()) "all" else "any_keyword")
                if (name.isNotEmpty()) put("name", name) else put("query_terms", JsonArray(terms.map(::JsonPrimitive)))
                put("source", "current_host_function_catalog"); put("remote_health_checked", false)
                put("tools", buildJsonArray { found.drop(offset).take(10).forEach { tool -> add(buildJsonObject {
                    put("name", tool.name); put("description", tool.description.take(if (name.isEmpty()) 260 else 8000))
                    put("host_checks_current", tool.isApprovalCurrent())
                    put("execution_approval", "evaluated_with_arguments_when_called")
                    if (name.isNotEmpty()) tool.parameters()?.let { put("parameters", Json.encodeToJsonElement(InputSchema.serializer(), it)) }
                }) } })
                if (offset + 10 < found.size) put("next_offset", offset + 10)
                put("if_missing", "零匹配只说明本次搜索没有找到；已知名称用name精确查询当前目录。仅本轮有效的工具在后续轮次可能不再提供。也可检查助手开关、MCP连接、工作区和暂停状态，或调用orbis_self_check。不要猜工具名或声称已经执行。")
            })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { agentReply(buildJsonObject { put("ok", false); put("code", "discovery_query_invalid_or_tool_unavailable") }) }
    },
)

internal fun refreshDiscovery(tools: List<Tool>): List<Tool> = tools.map { original ->
    if (original.name != DISCOVERY_TOOL) original else original.copy(execute = discoveryTool { tools }.execute)
}

internal fun taskTool(store: AgentTaskStore, conversation: String, enabled: () -> Boolean) = Tool(
    name = TASK_TOOL,
    description = "持久任务卡：先list(offset分页)，read(id,receipt_offset)查看步骤与回执，再create(title,steps?,state?,summary?)或update(id,revision,steps,state,summary)。create只需title；省略steps时建立同标题的一步待办，提供steps时每步state须为pending（可省略）。create的state默认active，也可waiting/blocked；summary默认空。update的steps=[{title,state:pending/running/done}]，state=active/waiting/blocked/completed。最多20步。多步骤任务可先建卡，按实际进展更新；完成只是你的报告，宿主另记真实工具回执。暂停/取消仅人类控制，恢复后先查记录，不重复已做的操作。本窗口内读写，不触发后台运行。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list","read","create","update").map(::JsonPrimitive))) })
        put("offset", agentProperty("integer", "list分页，默认0，每页10项"))
        put("receipt_offset", agentProperty("integer", "read回执分页，默认0，每页10条"))
        put("title", agentProperty("string", "任务标题，1–120字"))
        put("id", agentProperty("string", "list返回的任务id"))
        put("revision", agentProperty("integer", "最近读取的revision"))
        put("state", agentProperty("string", "create可省略，默认active，允许active/waiting/blocked；update必填，另可completed"))
        put("summary", agentProperty("string", "进展或受阻原因，最多1000字；不要填写密钥"))
        put("steps", buildJsonObject { put("type", "array"); put("maxItems", 20); put("items", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {
                put("title", agentProperty("string", "步骤标题，1–200字")); put("state", agentProperty("string", "create只能pending或省略；update可pending/running/done"))
            }); put("required", JsonArray(listOf(JsonPrimitive("title"))))
        }) })
    }, listOf("action")) },
    isApprovalCurrent = enabled,
    execute = { input ->
        try {
            check(enabled()) { "task_disabled" }
            val args = input as? JsonObject ?: error("invalid_parameters")
            val action = agentString(args, "action")
            val allowed = when (action) { "list" -> setOf("action","offset"); "read" -> setOf("action","id","receipt_offset"); "create" -> setOf("action","title","steps","state","summary")
                "update" -> setOf("action","id","revision","steps","state","summary"); else -> error("invalid_parameters") }
            require(args.keys.all { it in allowed }) { "task_unexpected_fields" }
            fun steps(): List<AgentStep> = try { Json.decodeFromJsonElement(args.getValue("steps")) }
                catch (_: Exception) { throw IllegalArgumentException("task_invalid_steps") }
            fun offset(field: String): Int = if (field !in args) 0 else
                (args[field] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.also { require(it in 0..100) }
                    ?: error("invalid_parameters")
            fun detail(task: AgentTask, receiptOffset: Int = maxOf(0,task.receipts.size - 5)): JsonObject = buildJsonObject {
                Json.encodeToJsonElement(task.copy(receipts = task.receipts.drop(receiptOffset).take(10))).jsonObject.forEach { (key,value) -> put(key,value) }
                put("receipt_count",task.receipts.size); put("receipt_offset",receiptOffset)
                if (receiptOffset + 10 < task.receipts.size) put("next_receipt_offset",receiptOffset + 10)
            }
            val result = when (action) {
                "list" -> {
                    val tasks = store.list().filter { it.conversation == conversation }.sortedByDescending { it.updatedAt }
                    val start = offset("offset")
                    buildJsonObject { put("count",tasks.size); put("tasks",buildJsonArray {
                        tasks.drop(start).take(10).forEach { task -> add(buildJsonObject {
                            put("id",task.id); put("title",task.title); put("state",task.state)
                            put("revision",task.revision); put("updatedAt",task.updatedAt); put("receipt_count",task.receipts.size)
                        }) }
                    }); if (start + 10 < tasks.size) put("next_offset",start + 10) }
                }
                "read" -> detail(store.list().firstOrNull { it.conversation == conversation && it.id == agentString(args,"id") }
                    ?: error("task_missing"),offset("receipt_offset"))
                "create" -> {
                    val title = agentString(args,"title")
                    detail(store.create(conversation, title,
                        if ("steps" in args) steps() else listOf(AgentStep(title)),
                        state = agentString(args,"state","active"), summary = agentString(args,"summary")))
                }
                else -> detail(store.update(conversation, agentString(args,"id"), args.getValue("revision").jsonPrimitive.int,
                    steps(), agentString(args,"state"), agentString(args,"summary")))
            }
            agentReply(buildJsonObject { put("ok",true); put("data",result)
                put("receipt_note","returned表示工具已返回，不等于外部目标已验证；started且无最终回执须先核对，不能重放。") })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { agentReply(taskFailure(e)) }
    },
)

/** Only fixed diagnostic text is returned; filesystem paths and user data stay out of errors. */
private fun taskFailure(error: Exception): JsonObject {
    val messages = mapOf(
        "task_invalid_title" to "title须为1–120字的非空标题。",
        "task_invalid_initial_state" to "create的state只能active/waiting/blocked，省略时为active；完成任务请先read，再update。",
        "task_invalid_state" to "update的state只能active/waiting/blocked/completed。",
        "task_invalid_summary" to "summary最多1000字。",
        "task_invalid_steps" to "steps须为1–20个{title,state}对象；标题1–200字，state为pending/running/done，最多一步running。create可省略steps。",
        "task_create_steps_must_be_pending" to "create时每步state只能pending或省略；创建后用返回的id和revision调用update记录进展。",
        "task_unexpected_fields" to "参数不属于本action：create只接受action/title/steps/state/summary；update只接受action/id/revision/steps/state/summary；list只接受action/offset；read只接受action/id/receipt_offset。",
        "task_already_open" to "本窗口已有未结束任务；先list/read核对，继续更新已有任务，不要重复建卡。",
        "task_revision_conflict" to "任务已变化；先read获取当前revision，不覆盖人类操作。",
        "task_missing" to "当前窗口未找到该任务；先list核对id。",
        "task_store_full" to "任务记录已达到容量上限，原记录保留。",
        "task_store_invalid" to "任务存储校验未通过，原记录保留；请查看自检，不要反复写入。",
        "task_not_editable" to "任务已暂停、取消或完成；AI不能解除人类暂停。",
        "task_steps_incomplete" to "只有所有步骤都为done时才能报告completed。",
        "task_disabled" to "当前助手未开启任务工具。",
    )
    val code = error.message?.takeIf { it in messages } ?: when (error) {
        is java.io.IOException -> "task_storage_unavailable"
        is IllegalArgumentException, is NoSuchElementException -> "task_invalid_parameters"
        else -> "task_invalid_or_unavailable"
    }
    val message = messages[code] ?: if (code == "task_invalid_parameters")
        "请核对action与参数类型。最简创建：{\"action\":\"create\",\"title\":\"任务标题\"}；更新需id、整数revision、steps和state。"
    else "未取得可靠读写结果。先list/read核对是否已保存，不要自动重试创建；原记录不清空。"
    return buildJsonObject { put("ok",false); put("code",code); put("message",message) }
}
