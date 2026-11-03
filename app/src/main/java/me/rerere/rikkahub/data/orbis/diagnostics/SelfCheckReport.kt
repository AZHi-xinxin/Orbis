package me.rerere.rikkahub.data.orbis.diagnostics

import kotlinx.serialization.Serializable

/** Closed facts only. No chat, prompt, URL, credential, tool arguments or upstream body. */
@Serializable
internal data class SelfCheckItem(
    val code: String,
    val title: String,
    val status: String,
    val evidence: String,
    val next: String,
    val repairBy: String = "human",
)
@Serializable
internal data class SelfCheckReport(
    val version: String,
    val checkedAt: Long,
    val focus: String,
    val items: List<SelfCheckItem>,
    val recent: List<DiagnosticEvent> = emptyList(),
    val scope: String = "当前助手的本机配置与最近故障摘要；未发起网络测试",
)
@Serializable
internal data class DiagnosticEvent(val at: Long, val source: String, val code: String,
    val phase: String? = null, val httpStatus: Int? = null, val parameter: String? = null)

internal val selfCheckFocus = listOf("overview", "tools", "model", "theme", "mcp", "workspace", "stickers", "permissions")
internal val diagnosticCodes = mapOf(
    "model_schema_rejected" to "服务商拒绝了工具参数结构。查模型/工具自检；保留错误卡用于核对。不是关闭时间戳或重填密钥能解决的问题。",
    "model_auth_rejected" to "服务商拒绝认证。人类到设置→模型服务→当前服务核对密钥和账号；不要把密钥发到聊天。",
    "model_rate_limited" to "服务商限流或额度不足。检查账户额度，稍后再试；本机无法确认具体是哪一种。",
    "model_network_failed" to "本次连接失败或等待超时。检查网络与服务地址；当前摘要不能判定是手机、网关还是上游故障。",
    "tool_not_advertised" to "模型调用了本轮目录外的工具。查当前已开启工具；不要猜工具名。持续出现时反馈错误码和版本。",
    "tool_continuation_catalog_mismatch" to "网关续轮目录不一致。保留已执行结果，从新消息继续；不要自动重复写入。",
    "tool_continuation_lineage_mismatch" to "工具调用编号或顺序与旧轮不一致。保留已执行结果及错误卡，核对客户端续轮协议；不要重复执行写入。",
    "tool_continuation_context_lost" to "工具续轮缺少原会话上下文。保留已执行结果，从新消息继续；不要重复执行写入。",
    "upstream_invalid_stream" to "上游流式响应未能完整解析。保留已收到内容和工具结果，反馈该码与时间，不能据此认定工具没有执行。",
    "upstream_unavailable" to "网关报告上游暂不可用。保留当前内容，稍后从新消息继续。",
    "human_turn_in_progress" to "网关仍在处理该窗口的另一轮请求；等待旧轮结束，避免重复发送。",
    "model_request_rejected" to "服务商拒绝了请求参数。核对报告中的HTTP状态与参数名，保留原始错误卡用于定位。",
    "model_server_failed" to "本次请求收到服务端错误状态。不能仅凭此状态判断是网关还是上游故障。",
    "history_recovery_pending" to "本机旧轮恢复副本尚未完成核对，历史编辑被保护；新消息仍可发送。保留原记录及错误卡，不要清数据。",
    "checkpoint_processing_failed" to "本机恢复记录处理失败，原副本保留；此错误不代表模型或网络不可用。请反馈错误卡与时间。",
    "theme_invalid_parameters" to "主题调用参数不匹配。publish必须带id和expected_revision；apply使用id和revision。先read取当前版本，再按回执next_call操作。",
    "theme_revision_required" to "修改已有主题须传expected_revision；先read读取当前revision。",
    "theme_revision_conflict" to "主题版本已变化。先read取得新revision，再修改同一id。",
    "theme_revision_or_owner_conflict" to "主题版本或作者不匹配。先read使用当前revision；AI只能编辑自己的主题。",
    "theme_asset_missing" to "主题图片引用缺失。用orbis_theme read查看missing_asset_fields，再list查看已导入图片；重新导入并patch对应字段，或改为纯色背景。",
    "theme_asset_invalid" to "主题图片或文件名不符合要求。通过主题工作室导入图片或orbis_theme import_asset，使用返回的真实文件名。",
    "theme_draft_missing" to "该主题没有草稿。先read确认；修改用patch，发布已有草稿用publish。",
    "theme_not_found" to "当前助手找不到这个主题id。先list使用本助手真实id。",
    "theme_store_full" to "主题库达到存储限制。到侧栏→自定义美化整理不需要的主题；保留当前使用版本。",
    "theme_store_invalid" to "主题库无法完整读取。保留现有数据，复制自检报告反馈；不要反复覆盖文件。",
    "theme_invalid_package_or_storage" to "主题内容或保存失败。先read核对是否已保存，再依据具体字段诊断处理。",
    "theme_tool_disabled" to "主题工具当前未开启。当前助手设置→本地工具→AI自定义美化。",
    "sticker_image_size" to "图片大小须在8MiB以内；选较小原图或重新截图。",
    "sticker_image_dimensions" to "图片边长须在4096像素以内；缩小图片后重新选择。",
    "sticker_image_format" to "选择PNG、JPEG、GIF或WebP图片；可用系统截图另存后再选。",
    "sticker_image_invalid" to "图片编码未通过检查；重新保存原图后再选。",
    "sticker_storage_reload_required" to "上次保存结果尚未核实。重开表情库核对是否已经存在，再决定是否重试。",
    "sticker_import_unavailable" to "无法重新读取所选图片。点＋重新从系统图片选择器选图后再保存。",
    "sticker_import_invalid" to "图片来源不可用。请从系统图片选择器重新选择本机图片。",
    "sticker_storage_unavailable" to "本机表情库读写失败。检查剩余空间；重开表情库核对保存结果后再决定是否重试。",
    "sticker_invalid_tags" to "表情标签不符合要求：最多12个，每个最多40字，合计最多240字。",
    "sticker_library_full" to "表情库达到数量或容量限制。先整理不需要的表情再导入。",
    "sticker_storage_invalid" to "表情库数据无法完整读取。保留原数据和错误码反馈。",
    "sticker_storage_error" to "表情库保存失败。检查剩余空间，重开表情库核对；不需要另开AI工具权限。",
    "weather_city_missing" to "天气城市未配置：北斗导航→手机与陪伴→本地资料→天气城市，填写并保存。",
    "weather_city_invalid" to "天气城市格式无效；修改城市名称或纬度,经度。",
    "weather_city_not_found" to "天气城市未匹配；核对拼写，可加省份或国家。",
    "weather_city_ambiguous" to "天气城市存在重名；添加省份或填写纬度,经度，未任意选择地点。",
    "weather_timeout" to "天气源等待超时；这不是模型或全部工具不可用。",
    "weather_network_failed" to "天气源网络连接失败；检查该站点可达性，不需要重填模型密钥。",
    "weather_rate_limited" to "天气源限流，稍后再查。",
    "weather_upstream_failed" to "天气源返回异常状态，未取得有效天气。",
    "weather_response_invalid" to "天气数据格式不完整或异常，未冒充查询成功。",
    "weather_sources_unavailable" to "主、备用天气源均未返回可用数据；检查网络或稍后再查。",
    "anniversary_data_invalid" to "纪念日数据无法解析；到手机与陪伴→本地资料核对，保留原记录。",
    "service_disabled" to "陪伴服务已关闭；可在手机与陪伴中启用。",
    "service_unavailable" to "陪伴服务尚未就绪；在手机与陪伴查看运行状态。",
    "operation_unavailable" to "本机工具未完成，旧回执没有提供更细原因；需对应错误卡，不直接推断为网络或权限。",
    "unclassified_failure" to "已有失败记录，但摘要不足以确定原因。保留对应错误卡，复制自检报告供核对。",
)

/** Classify without returning any matched body, token, arbitrary code or field value. */
internal fun diagnosticCode(message: String): String {
    val text = message.take(64 * 1024)
    return when {
        text.contains("tool_continuation_catalog_mismatch") -> "tool_continuation_catalog_mismatch"
        text.contains("tool_not_advertised") -> "tool_not_advertised"
        (text.contains("function_declarations", true) || text.contains("functionDeclarations", true)) &&
            (text.contains("Invalid JSON", true) || text.contains("missing field", true) || text.contains("INVALID_ARGUMENT")) -> "model_schema_rejected"
        // ICU treats a leading [: as a POSIX property opener inside a character class.
        Regex("""(?i)(?:\bHTTP\s*|response[ :]+|code[" :]+)(401|403)\b""").containsMatchIn(text) -> "model_auth_rejected"
        Regex("""(?i)(?:\bHTTP\s*|response[ :]+|code[" :]+)429\b""").containsMatchIn(text) -> "model_rate_limited"
        text.contains("timeout", true) || text.contains("timed out", true) || text.contains("Unable to resolve host", true) || text.contains("Failed to connect", true) -> "model_network_failed"
        text in diagnosticCodes -> text
        else -> "unclassified_failure"
    }
}

/** Read machine fields before display prose. Wrappers and arbitrary upstream data stay private. */
internal fun diagnosticFailure(error: Throwable, at: Long = System.currentTimeMillis()): DiagnosticEvent {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && chain.size < 8 && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    val http = chain.filterIsInstance<me.rerere.ai.util.HttpException>().firstOrNull()
    val machineCode = chain.filterIsInstance<me.rerere.ai.util.HttpException>()
        .firstNotNullOfOrNull { it.code?.takeIf { code -> code in diagnosticCodes } }
    val historyPending = chain.any { it.message == "旧轮恢复记录尚未核对，原历史仍保留；新消息仍可发送。" }
    val checkpoint = chain.any { it is me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointException }
    val status = http?.httpStatus?.takeIf { it in 100..599 }
    val proseCode = chain.firstNotNullOfOrNull { diagnosticCode(it.message.orEmpty()).takeUnless { code -> code == "unclassified_failure" } }
    val code = machineCode ?: when {
        historyPending -> "history_recovery_pending"
        checkpoint -> "checkpoint_processing_failed"
        status in setOf(401, 403) -> "model_auth_rejected"
        status == 429 -> "model_rate_limited"
        status != null && status >= 500 -> "model_server_failed"
        proseCode != null -> proseCode
        status in setOf(400, 422) -> "model_request_rejected"
        chain.any { it is java.net.SocketTimeoutException || it is java.net.UnknownHostException || it is java.net.ConnectException } -> "model_network_failed"
        else -> "unclassified_failure"
    }
    return DiagnosticEvent(at, "model", code,
        phase = when { historyPending || checkpoint -> "local_recovery"; status != null -> "http_response"; else -> null },
        httpStatus = status, parameter = me.rerere.ai.util.safeRejectedParameter(http?.rejectedParameter))
}

internal fun DiagnosticEvent.sanitized() = copy(
    phase = phase?.takeIf { it in setOf("local_recovery", "http_response") },
    httpStatus = httpStatus?.takeIf { it in 100..599 },
    parameter = me.rerere.ai.util.safeRejectedParameter(parameter),
)

/** Decode both flat and nested tool receipts without retaining arbitrary upstream strings. */
internal fun toolDiagnosticCode(failure: kotlinx.serialization.json.JsonObject): String {
    val nested = failure["error"] as? kotlinx.serialization.json.JsonObject
    return (listOf("code", "error", "reason").map { failure[it] } + nested?.get("code"))
        .filterIsInstance<kotlinx.serialization.json.JsonPrimitive>()
        .map { it.content }.firstOrNull { it in diagnosticCodes } ?: "unclassified_failure"
}

internal fun weatherConfigurationItem(configured: Boolean) = SelfCheckItem(
    code = if (configured) "weather_city_configured" else "weather_city_missing",
    title = "天气城市",
    status = if (configured) "info" else "blocked",
    evidence = if (configured) "已保存天气城市；未发起天气网络请求，不能据此判定服务可用。" else "尚未保存天气城市；不会使用出口IP猜测位置。",
    next = "北斗导航→手机与陪伴→本地资料→天气城市，填写并保存。",
)
