package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.ai.mcp.McpStatus

/** These are display labels, never URLs, credential fields, or exception text. */
internal fun orbisSettingsLabel(value: String?, fallback: String): String {
    val label = value?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")?.trim().orEmpty()
    if (label.isBlank() || Regex("(?i)([a-z][a-z0-9+.-]*://|bearer\\s|api[-_ ]?key\\s*[:=]|token\\s*[:=]|sk-[a-z0-9]{8,}|[a-z]:[\\\\/])")
            .containsMatchIn(label)) return fallback
    return if (label.codePointCount(0, label.length) > 64)
        label.substring(0, label.offsetByCodePoints(0, 63)).trimEnd() + "…" else label
}

/** Saved player default, not a provider's synthesis speed or a live playback measurement. */
internal fun orbisSavedPlaybackSpeed(value: Float): String =
    if (value.isFinite() && value > 0f) "${value}×（已保存）" else "未确定"

internal data class OrbisMcpOverview(
    val configured: String,
    val connection: String,
    val selected: String,
)

/** Connection and configuration are separate facts; no state implies this request received tools. */
internal fun orbisMcpOverview(
    globallyEnabled: Boolean,
    assistantSelected: Boolean?,
    status: McpStatus?,
): OrbisMcpOverview = OrbisMcpOverview(
    configured = if (globallyEnabled) "配置：已开启" else "配置：已关闭",
    connection = when (status) {
        null -> "连接：暂无状态"
        McpStatus.Idle -> "连接：未连接"
        McpStatus.Connecting -> "连接：连接中"
        McpStatus.Connected -> "连接：已连接"
        is McpStatus.Reconnecting -> "连接：重连中"
        is McpStatus.Error -> "连接：出错"
        McpStatus.NeedsAuthorization -> "连接：需要授权"
        McpStatus.Authorizing -> "连接：授权中"
    },
    selected = when (assistantSelected) {
        null -> "当前AI：未确定"
        true -> "当前AI：已选用"
        false -> "当前AI：未选用"
    },
)

internal enum class OrbisSpeechRule { AUTO_READ, QUOTED_ONLY, OUTSIDE_BRACKETS }

internal data class OrbisSpeechRules(
    val autoRead: Boolean,
    val quotedOnly: Boolean,
    val outsideBrackets: Boolean,
) {
    fun withRule(rule: OrbisSpeechRule, enabled: Boolean): OrbisSpeechRules = when (rule) {
        OrbisSpeechRule.AUTO_READ -> copy(autoRead = enabled)
        OrbisSpeechRule.QUOTED_ONLY -> copy(quotedOnly = enabled)
        OrbisSpeechRule.OUTSIDE_BRACKETS -> copy(outsideBrackets = enabled)
    }
}

/** The first lazy item holds both the heading and model card, keeping MCP deep-link index stable. */
internal fun orbisSettingsInitialItem(startAtMcp: Boolean): Int = if (startAtMcp) 1 else 0

internal enum class OrbisToolGroup { WORK, PLAY }
internal enum class OrbisToolDestination { WORKSPACE, ATTACHMENTS, LOCAL_CAPABILITIES, GAMES, STICKERS, BLUETOOTH_TOY }
internal data class OrbisToolEntry(
    val destination: OrbisToolDestination,
    val group: OrbisToolGroup,
    val glyph: String,
    val title: String,
    val subtitle: String,
)

/** Only implemented destinations; never add a placeholder action merely to fill the grid. */
internal val orbisToolEntries = listOf(
    OrbisToolEntry(OrbisToolDestination.WORKSPACE, OrbisToolGroup.WORK, "⌘", "工作区", "文件、终端与技能"),
    OrbisToolEntry(OrbisToolDestination.ATTACHMENTS, OrbisToolGroup.WORK, "▰", "聊天附件", "已保存的消息文件"),
    OrbisToolEntry(OrbisToolDestination.LOCAL_CAPABILITIES, OrbisToolGroup.WORK, "⌁", "本地能力", "当前 AI 工具开关"),
    OrbisToolEntry(OrbisToolDestination.GAMES, OrbisToolGroup.PLAY, "●○", "游戏机", "本地九路五子棋"),
    OrbisToolEntry(OrbisToolDestination.STICKERS, OrbisToolGroup.PLAY, "☺", "表情库", "图片与共享标签"),
    OrbisToolEntry(OrbisToolDestination.BLUETOOTH_TOY, OrbisToolGroup.PLAY, "◉", "蓝牙 Toy", "设备连接与本地控制"),
)
