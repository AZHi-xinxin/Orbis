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
internal data class DiagnosticEvent(val at: Long, val source: String, val code: String)

internal val selfCheckFocus = listOf("overview", "tools", "model", "theme", "mcp", "workspace", "stickers", "permissions")
internal val diagnosticCodes = mapOf(
    "model_schema_rejected" to "服务商拒绝了工具参数结构。查模型/工具自检；保留错误卡用于核对。不是关闭时间戳或重填密钥能解决的问题。",
    "model_auth_rejected" to "服务商拒绝认证。人类到设置→模型服务→当前服务核对密钥和账号；不要把密钥发到聊天。",
    "model_rate_limited" to "服务商限流或额度不足。检查账户额度，稍后再试；本机无法确认具体是哪一种。",
    "model_network_failed" to "本次连接失败或等待超时。检查网络与服务地址；当前摘要不能判定是手机、网关还是上游故障。",
    "tool_not_advertised" to "模型调用了本轮目录外的工具。查当前已开启工具；不要猜工具名。持续出现时反馈错误码和版本。",
    "tool_continuation_catalog_mismatch" to "网关续轮目录不一致。保留已执行结果，从新消息继续；不要自动重复写入。",
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
