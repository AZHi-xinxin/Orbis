package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.boundary.*

/** The same owner-bound control tool in the original conversation, with only its decision actions. */
internal fun createBoundaryRecoveryTool(host: BoundaryHost, owner: String, conversation: String, recoveryRevision: Long,
    bindingCurrent: () -> Boolean, verifyConversation: suspend () -> Boolean): Tool {
    val original = createBoundaryTool(host, owner, conversation, { emptyMap() }, bindingCurrent, verifyConversation)
    val schema = original.parameters() as InputSchema.Obj
    val recoveryScope = host.pause(owner, conversation)?.scope ?: "conversation"
    val allowed = setOf("scope", "action", "revision", "reason", "allow_request", "until_epoch_ms")
    val properties = buildJsonObject {
        schema.properties.filterKeys { it in allowed }.forEach { (key, value) -> put(key, value) }
        put("action", buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("state", "pause", "resume").map(::JsonPrimitive)))
        })
    }
    return original.copy(
        description = original.description + " 本轮是原对话中人类提交的继续申请，当前暂停范围scope=$recoveryScope，原聊天仍保持暂停。请结合原上下文答复；愿意继续用resume，明确保持暂停用pause并选allow_request及1分钟至24小时内的until_epoch_ms。可先state查当前revision和now_epoch_ms。任一暂停/恢复决定成功后结束本轮；本轮沿用已授权工具，可结合上下文处理申请；普通输入仍暂停。同一次暂停最多3次申请，只有文字或查状态也计入次数，剩余额度见state。",
        parameters = { schema.copy(properties = properties) },
        isApprovalCurrent = { bindingCurrent() && host.recoveryPending(owner, conversation, recoveryRevision) },
        execute = { arguments ->
            val raw = arguments as? JsonObject
            val args = raw?.let { if ("scope" in it) it else JsonObject(it + ("scope" to JsonPrimitive(recoveryScope))) }
            if (!host.recoveryPending(owner, conversation, recoveryRevision) || args == null || args.keys.any { it !in allowed } ||
                (args["action"] as? JsonPrimitive)?.contentOrNull !in setOf("state", "pause", "resume") ||
                ((args["scope"] as? JsonPrimitive)?.contentOrNull != recoveryScope &&
                    !((args["action"] as? JsonPrimitive)?.contentOrNull == "pause" &&
                        (args["scope"] as? JsonPrimitive)?.contentOrNull == "assistant"))) {
                listOf(UIMessagePart.Text("本次未执行。继续申请期间可用state查看状态，以pause或resume决定当前对话；其他能力的选择保持原样。"))
            } else original.execute(args)
        },
    )
}

internal fun createBoundaryTool(host: BoundaryHost, owner: String, conversation: String?,
    catalog: () -> Map<String, Map<String, String>>, bindingCurrent: () -> Boolean, verifyConversation: suspend () -> Boolean = { true },
    alternateModelAvailable: (String) -> Boolean = { catalog()["model"]?.containsKey(it) == true },
    toolSources: () -> Map<String, String> = { emptyMap() }) = Tool(
    name = BOUNDARY_TOOL,
    description = "自主决定对话和能力。pause/resume的scope=conversation只影响当前窗口（默认），scope=assistant影响本助手全部旧/新窗口。state查状态/revision；加kind=model/mcp/tool分页查ID。写入带revision；pause还必须填1分钟至24小时内的until_epoch_ms，并明确allow_request：true开放本次暂停最多3次提前申请，false关闭。暂停成功即结束本轮，不再生成收尾。详细操作查orbis_help guide/boundaries。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("state", "pause", "resume", "enable", "disable").map(::JsonPrimitive))) })
        put("scope", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("conversation", "assistant").map(::JsonPrimitive))); put("description", "pause/resume范围：conversation当前窗口，assistant整个当前助手（含以后新建窗口）；两种暂停分别恢复。省略为conversation。") })
        put("kind", buildJsonObject { put("type", "string"); put("enum", JsonArray(BOUNDARY_CATEGORIES.map(::JsonPrimitive))); put("description", "state不填或conversation仅查状态；model/mcp/tool分页查询对应目录。pause/resume默认conversation。") })
        listOf("id", "reason", "alternate_model_id").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        put("hidden", buildJsonObject { put("type", "boolean") })
        put("allow_request", buildJsonObject { put("type", "boolean"); put("description", "pause必填，由你选择。true：暂停卡显示申请继续，本次pause最多3次，助手范围跨窗口共享；false：不开放申请。漏填不会执行暂停。") })
        put("revision", buildJsonObject { put("type", "integer"); put("description", "pause/resume/enable/disable必填；使用state或上次成功写入返回的最新revision。") })
        put("until_epoch_ms", buildJsonObject { put("type", "integer"); put("description", "pause必填的恢复时间（毫秒时间戳），距调用时至少60秒、至多24小时；state返回now_epoch_ms可用于计算。漏填或超范围不会暂停，到期自动恢复。") })
        put("query", buildJsonObject { put("type", "string"); put("description", "state目录按名称或完整ID搜索。") })
        put("mcp_id", buildJsonObject { put("type", "string"); put("description", "state(kind=tool)只查询此MCP的工具。") })
        put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 20); put("description", "目录每页条数，默认10。") })
    }, required = listOf("action")) },
    needsApproval = { false },
    isApprovalCurrent = { bindingCurrent() && host.state(owner).grants.isNotEmpty() },
    execute = { arguments ->
        val result = try {
            check(bindingCurrent() && verifyConversation()) { "boundary_owner_changed" }
            val args = arguments.jsonObject
            fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
            val action = text("action") ?: error("boundary_action_missing")
            require(action != "select_model") { "boundary_use_orbis_configure_select_model" }
            require(action in setOf("state", "pause", "resume", "enable", "disable")) { "boundary_action_invalid" }
            if (action != "state") {
                val kind = text("kind") ?: if (action in setOf("pause", "resume")) "conversation" else error("boundary_kind_missing")
                val id = text("id").orEmpty()
                val revision = args["revision"]?.jsonPrimitive?.longOrNull ?: error("boundary_revision_missing")
                val allowRequest = args["allow_request"]?.jsonPrimitive?.booleanOrNull
                require(action != "pause" || allowRequest != null) { "boundary_allow_request_missing" }
                if (kind != "conversation") require(catalog()[kind]?.containsKey(id) == true) { "boundary_target_unavailable" }
                val alternate = text("alternate_model_id")
                require(alternate == null || alternateModelAvailable(alternate)) { "boundary_alternative_unavailable" }
                host.update(owner) { state -> changeBoundary(state, revision, conversation, action, kind, id,
                    args["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                    text("reason").orEmpty(), args["until_epoch_ms"]?.jsonPrimitive?.longOrNull,
                    allowRequest ?: false, alternate, System.currentTimeMillis(), scope = text("scope") ?: "conversation") }
            }
            val state = host.state(owner)
            buildJsonObject {
                put("ok", true)
                if (action in setOf("pause", "resume")) put("operation_scope", text("scope") ?: "conversation")
                boundaryStateOutput(state, conversation, System.currentTimeMillis()).forEach { (key, value) -> put(key, value) }
                val kind = text("kind")
                if (action == "state" && kind != null && kind != "conversation") {
                    boundaryCatalogPage(state, kind, catalog(), toolSources(), text("query").orEmpty(), text("mcp_id"),
                        args["offset"]?.jsonPrimitive?.intOrNull ?: 0, args["limit"]?.jsonPrimitive?.intOrNull ?: 10)
                        .forEach { (key, value) -> put(key, value) }
                }
                put("note", "选择已由宿主执行。停用的MCP/工具立即停止执行；为完成当前自动续轮，原工具目录保持到本次生成结束，下一次新生成更新目录。暂停、停用本轮模型或改变模型内置能力后不再发起本轮下一次请求；原配置、历史与草稿保留。")
                put("id_help", "state默认只查状态；要查ID加kind=model/mcp/tool，可用query搜索及offset/limit翻页。MCP只预览5个tool_ids，完整成员用state(kind=tool,mcp_id=该MCP的id)分页查。工具ID原样使用；切模型用orbis_configure(action=select_model)。")
            }
        } catch (e: IllegalArgumentException) { boundaryFailure(e.message ?: "boundary_invalid_arguments", host.state(owner).revision)
        } catch (e: IllegalStateException) { boundaryFailure(e.message ?: "boundary_unavailable", host.state(owner).revision) }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun boundaryFailure(code: String, revision: Long) = buildJsonObject {
    put("ok", false); put("code", code); put("revision", revision)
    put("now_epoch_ms", System.currentTimeMillis())
    put("help", when (code) {
        "boundary_use_orbis_configure_select_model" -> "切换当前模型请调用orbis_configure(action=select_model)；orbis_boundary用enable/disable决定能力是否可用。"
        "boundary_revision_missing" -> "本次未执行。当前版本已在revision返回，按当前意图带revision重试；pause同时必须填写allow_request=true或false。state不带kind即可简短核对状态。"
        "boundary_revision_changed" -> "本次未执行。先用state简短核对当前选择，再带最新revision重新决定；暂停、恢复和能力变更共用版本号。"
        "boundary_allow_request_missing" -> "本次未暂停。请明确allow_request=true（人类可在聊天底部暂停卡点申请继续）或false（不开放申请），保留revision再调用pause。暂停成功会结束本轮，说明请在暂停前说。"
        "boundary_deadline_missing", "boundary_deadline_out_of_range" -> "本次未暂停。pause必须填until_epoch_ms，距当前now_epoch_ms至少60000、至多86400000毫秒；例如休息5分钟填now_epoch_ms+300000。同时明确allow_request并带最新revision。"
        "boundary_page_invalid" -> "offset须大于等于0，limit为1到20；使用page.next_offset继续查询。"
        else -> "调用orbis_boundary(action=state)核对授权与choices中的准确id。单个工具用kind=tool；切换当前模型用orbis_configure(action=select_model)。"
    })
}
