package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lover.connect.ui.components.StarSwitch
import me.rerere.rikkahub.data.orbis.OrbisInboxState
import me.rerere.rikkahub.data.ai.tools.orbisSentinelGuide
import me.rerere.rikkahub.data.orbis.sentinel.sentinelNextDueAtMs
import me.rerere.rikkahub.data.orbis.sentinel.SENTINEL_SYSTEM_TYPES
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelAction
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelExecution
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelExecutionStatus
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelNotificationLevel
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRearm
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRule
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelState
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelType
import me.rerere.rikkahub.ui.components.message.orbisEventReceivedTime
import me.rerere.rikkahub.ui.components.message.orbisEventSourceLabel

/**
 * Human-facing inspection. Only the master switch changes rule configuration; explicit recovery
 * acknowledges old undelivered events without changing rules or replaying them.
 * No service/store lookup, permission request, rule editor or prompt transformation lives here.
 */
@Composable
internal fun OrbisSentinelPanel(
    state: OrbisSentinelState,
    records: List<OrbisSentinelExecution> = state.executions,
    assistantNames: Map<String, String> = emptyMap(),
    conversationNames: Map<String, String> = emptyMap(),
    inbox: OrbisInboxState = OrbisInboxState(),
    runtimeRunning: Boolean = false,
    runtimeError: String? = null,
    legacyOwned: Boolean = false,
    saving: Boolean = false,
    notice: String? = null,
    recoveryConversationIds: Set<String> = emptySet(),
    recoveryNotices: Map<String, String> = emptyMap(),
    recoveringConversationId: String? = null,
    onRecoverFutureAutomaticWakes: (String) -> Unit = {},
    onHumanMasterChange: (Boolean) -> Unit,
) {
    var recordLimit by rememberSaveable { mutableIntStateOf(10) }
    var receiptLimit by rememberSaveable { mutableIntStateOf(10) }
    val sortedRecords = remember(records) { records.sortedByDescending { it.updatedAtMs } }
    val sortedReceipts = remember(inbox.events) { inbox.events.sortedByDescending { it.receivedAt } }
    val latestByRule = remember(sortedRecords) { sortedRecords.groupBy { it.ruleId }.mapValues { it.value.first() } }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("哨兵与自我唤醒", style = MaterialTheme.typography.titleMedium)
            Text("AI 自由选择类别、时间和内容；人类控制总开关，也可处理投递暂停。以下均可展开查看，没有分项修改开关。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自动唤醒总开关", style = MaterialTheme.typography.titleSmall)
                    Text(if (state.enabled) "允许自动唤醒；实际执行仍取决于规则、权限与后台状态。"
                        else "自动唤醒已暂停；规则和历史保留，重新开启不补发暂停期间的事件。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StarSwitch(checked = state.enabled, enabled = !saving && recoveringConversationId == null,
                    onCheckedChange = onHumanMasterChange,
                    modifier = Modifier.testTag("sentinel-human-master").semantics {
                        contentDescription = "自动唤醒总开关，仅人类可修改"
                    })
            }
            if (saving) Text("正在保存总开关…", style = MaterialTheme.typography.bodySmall)
            if (notice != null) Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            (recoveryConversationIds + recoveryNotices.keys).forEach { conversationId ->
                Column(Modifier.fillMaxWidth().testTag("sentinel-recovery-$conversationId"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${conversationNames[conversationId] ?: "固定会话"} · 旧状态核对",
                        style = MaterialTheme.typography.titleSmall)
                    if (conversationId in recoveryConversationIds) {
                        Text("这里保留旧轮的保护记录，供你核对；新通知已独立投递，不需要先点恢复。旧积压提醒不补发，未知工具不重做，旧通话不重连。",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onRecoverFutureAutomaticWakes(conversationId) },
                            enabled = !saving && recoveringConversationId == null,
                            modifier = Modifier.testTag("sentinel-recover-future-$conversationId")) {
                            Text(if (recoveringConversationId == conversationId) "正在检查并保存…" else "恢复后续哨兵")
                        }
                    }
                    recoveryNotices[conversationId]?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("sentinel-recovery-result-$conversationId"))
                    }
                }
            }
            orbisSentinelCategories.forEach { category ->
                val matching = state.rules.filter { it.type in category.types }
                SentinelReadOnlyDisclosure("sentinel-category-${category.id}", category.title,
                    subtitle = "${matching.count { it.enabled }} 条已启用 · ${matching.size} 条规则") {
                    category.types.forEach { type ->
                        val guide = orbisSentinelGuide.first { it.type == type.name.lowercase() }
                        SentinelReadOnlyField(guide.title, guide.instructions)
                    }
                    if (matching.isEmpty()) Text("未设置此类哨兵；由 AI 自行决定是否设置。", style = MaterialTheme.typography.bodySmall)
                    matching.forEach { rule -> SentinelRuleReadOnlyCard(rule, state.enabled, latestByRule[rule.id], assistantNames, conversationNames, state.resumedAtMs) }
                }
            }
            SentinelReadOnlyDisclosure("sentinel-category-other", "一次性与其他自定义",
                subtitle = "${state.rules.count { rule -> orbisSentinelCategories.none { rule.type in it.types } }} 条规则") {
                Text("一次性预约收到一次即停用；间隔、用户消息静默、离开聊天和指定应用等旧规则仍可使用。由 AI 创建或修改，不需要每天重设长期规则。", style = MaterialTheme.typography.bodySmall)
                val other = state.rules.filter { rule -> orbisSentinelCategories.none { rule.type in it.types } }
                if (other.isEmpty()) Text("暂无一次性或其他自定义规则。", style = MaterialTheme.typography.bodySmall)
                other.forEach { rule -> SentinelRuleReadOnlyCard(rule, state.enabled, latestByRule[rule.id], assistantNames, conversationNames, state.resumedAtMs) }
            }
            SentinelReadOnlyDisclosure("sentinel-diagnostics", "运行状态与说明",
                subtitle = when { !state.enabled -> "总开关暂停"; runtimeRunning -> "本机调度运行中"; else -> "本机调度当前未运行" }) {
                Text(when {
                    !state.enabled -> "本地哨兵调度：已由总开关暂停"
                    runtimeRunning -> "本地哨兵调度：运行中"
                    else -> "本地哨兵调度：当前未运行"
                }, style = MaterialTheme.typography.bodySmall)
                if (runtimeError != null) Text("本机状态提示：$runtimeError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("总开关同时控制本机哨兵和旧来源的自动唤醒；关闭会停止仍在生成的哨兵回复，已执行的工具不会被撤回。", style = MaterialTheme.typography.bodySmall)
                Text("每次事件自动附带宿主的真实日期、时间和时区。规则长期保存；后台、省电、权限、网络和模型服务仍影响执行，不保证到点出声。打开此页不创建规则、不发送测试。", style = MaterialTheme.typography.bodySmall)
                Text(if (legacyOwned) "旧链路归属已核对。"
                    else "本机哨兵直接投递到固定对话。旧外部发送端是否停用尚未核实，这不代表本机哨兵未连接；请查看下方送达记录。",
                    modifier = Modifier.testTag("sentinel-legacy-status"), style = MaterialTheme.typography.bodySmall)
            }
            SentinelReadOnlyDisclosure("sentinel-executions", "本机执行记录 · ${records.size} 条") {
                Text("“已接收”仅表示进入固定收件箱，不等于 AI 已回复；详情可对照送达记录。", style = MaterialTheme.typography.bodySmall)
                if (records.isEmpty()) Text("暂无本机执行记录。", style = MaterialTheme.typography.bodySmall)
                sortedRecords.take(recordLimit).forEach { record ->
                SentinelReadOnlyDisclosure("sentinel-execution-${record.eventId}",
                    "${orbisSentinelExecutionStatusLabel(record.status)} · ${orbisEventReceivedTime(record.updatedAtMs)}") {
                    SentinelReadOnlyField("规则", state.rules.firstOrNull { it.id == record.ruleId }?.name?.ifBlank { record.ruleId } ?: record.ruleId)
                    SentinelTarget(record.assistantId, record.conversationId, assistantNames, conversationNames)
                    SentinelReadOnlyField("动作", orbisSentinelActionLabel(record.action))
                    SentinelReadOnlyField("事件标识", record.eventId)
                    record.detail?.let { SentinelReadOnlyField("执行说明", it) }
                }
                }
                if (recordLimit < records.size) TextButton(onClick = { recordLimit += 20 }) { Text("查看更多执行记录") }
            }
            SentinelReadOnlyDisclosure("sentinel-bindings", "外部来源绑定 · 只读") {
                if (inbox.bindings.isEmpty()) Text("暂无外部来源绑定。", style = MaterialTheme.typography.bodySmall)
                inbox.bindings.forEach { (source, binding) ->
                SentinelReadOnlyDisclosure("sentinel-source-$source", orbisEventSourceLabel(source)) {
                    SentinelReadOnlyField("来源标识", source)
                    SentinelTarget(binding.assistantId, binding.conversationId, assistantNames, conversationNames)
                    SentinelReadOnlyField("已保存的接收配置", if (binding.enabled) "允许接收，仍受总开关控制" else "此来源接收已关闭")
                }
                }
            }
            SentinelReadOnlyDisclosure("sentinel-receipts", "送达记录 · ${inbox.events.size} 条") {
                if (inbox.events.isEmpty()) Text("暂无送达记录。", style = MaterialTheme.typography.bodySmall)
                sortedReceipts.take(receiptLimit).forEach { receipt ->
                SentinelReadOnlyDisclosure("sentinel-receipt-${receipt.id}",
                    "${orbisEventSourceLabel(receipt.source)} · ${orbisSentinelReceiptStateLabel(receipt.state)} · ${orbisEventReceivedTime(receipt.receivedAt)}") {
                    SentinelTarget(receipt.assistantId, receipt.conversationId, assistantNames, conversationNames)
                    SentinelReadOnlyField("事件标识", receipt.eventId)
                    if (receipt.independentDelivery && receipt.state !in setOf("suppressed", "target_invalid")) {
                        Text("通知已进入固定对话；AI 回复单独处理。本条失败不阻止后续通知。", style = MaterialTheme.typography.bodySmall)
                    }
                    receipt.error?.let { SentinelReadOnlyField("结果说明", orbisWakeReasonLabel(it)) }
                    if (receipt.state == "skipped") Text("本次未发送，原文留在这里，不会补发；之后的新事件单独检查。",
                        style = MaterialTheme.typography.bodySmall)
                    if (receipt.state == "unknown") Text("无法确认是否完成；请先查看固定会话，避免重复唤醒。",
                        style = MaterialTheme.typography.bodySmall)
                    SentinelReadOnlyField("收到的原文", receipt.text)
                }
                }
                if (receiptLimit < inbox.events.size) TextButton(onClick = { receiptLimit += 20 }) { Text("查看更多送达记录") }
            }
        }
    }
}

internal data class OrbisSentinelCategory(val id: String, val title: String, val types: Set<OrbisSentinelType>)
internal val orbisSentinelCategories = listOf(
    OrbisSentinelCategory("ritual", "仪式唤醒", setOf(OrbisSentinelType.RITUAL)),
    OrbisSentinelCategory("agreement", "约定唤醒", setOf(OrbisSentinelType.AGREEMENT)),
    OrbisSentinelCategory("screen_observation", "屏幕观察唤醒", setOf(OrbisSentinelType.SCREEN_OBSERVATION)),
    OrbisSentinelCategory("night_usage", "夜间使用手机唤醒", setOf(OrbisSentinelType.NIGHT_USAGE)),
    OrbisSentinelCategory("phone_context", "手机情景唤醒", setOf(OrbisSentinelType.LOW_BATTERY, OrbisSentinelType.SCREEN_ON)),
    OrbisSentinelCategory("geofence", "位置围栏唤醒", setOf(OrbisSentinelType.GEOFENCE)),
    OrbisSentinelCategory("touch", "Stackchan 触屏反馈", setOf(OrbisSentinelType.TOUCH)),
)

@Composable
private fun SentinelRuleReadOnlyCard(
    rule: OrbisSentinelRule,
    masterEnabled: Boolean,
    latest: OrbisSentinelExecution?,
    assistantNames: Map<String, String>,
    conversationNames: Map<String, String>,
    resumedAtMs: Long? = null,
) {
    SentinelReadOnlyDisclosure("sentinel-rule-${rule.id}", rule.name.ifBlank { "未命名规则" },
        subtitle = when {
            !masterEnabled -> "总开关已暂停 · ${if (rule.enabled) "AI 已启用此规则" else "AI 已停用此规则"}"
            rule.enabled -> "AI 已启用此规则"
            else -> "AI 已停用此规则"
        }) {
        SentinelTarget(rule.assistantId, rule.conversationId, assistantNames, conversationNames)
        SentinelReadOnlyField("触发条件", orbisSentinelConditionLabel(rule))
        SentinelReadOnlyField("执行动作", orbisSentinelActionLabel(rule.action))
        SentinelReadOnlyField("通知级别", if (rule.notificationLevel == OrbisSentinelNotificationLevel.STRONG) "强提醒" else "轻提醒")
        SentinelReadOnlyField("冷却时间", orbisSentinelDurationLabel(rule.cooldownMs))
        SentinelReadOnlyField("再次触发", if (rule.rearm == OrbisSentinelRearm.AFTER_RESET) "条件复位后再触发" else "冷却结束后可再次触发")
        SentinelReadOnlyField("内容来源", if (rule.type in SENTINEL_SYSTEM_TYPES) "系统真实观察，AI 不填写播报正文" else "AI 亲写原文，系统不改写")
        if (rule.type !in SENTINEL_SYSTEM_TYPES) SentinelReadOnlyField("规则原文", rule.prompt)
        SentinelReadOnlyField("时间标记", "每次事件由宿主自动加入真实日期、时间和时区")
        SentinelReadOnlyField("时区", rule.timezone ?: "跟随手机")
        if (rule.type == OrbisSentinelType.AGREEMENT) {
            SentinelReadOnlyField("静默时段", rule.quietStartLocal?.let { "$it–${rule.quietEndLocal}" } ?: "不设置静默")
            SentinelReadOnlyField("连续唤醒次数", "${rule.policy.consecutiveCount} 次；人类新消息后重置")
            rule.escalationAfter?.let { SentinelReadOnlyField("连续提示升级", "超过 $it 次后：${rule.escalationPrompt.orEmpty()}") }
        }
        SentinelReadOnlyField("下次计划", if (!masterEnabled || !rule.enabled) "已暂停，没有自动唤醒" else sentinelNextDueAtMs(rule, resumedAtMs)?.let { "${orbisEventReceivedTime(it)}（计划时间，非准时保证）" }
            ?: if (rule.type == OrbisSentinelType.RITUAL) "等待调度器确定下一日历时刻" else "等待条件满足或外部真实事件")
        rule.lastFiredAtMs?.let { SentinelReadOnlyField("最近进入收件箱", orbisEventReceivedTime(it)) }
        latest?.let { SentinelReadOnlyField("最近执行结果", "${orbisSentinelExecutionStatusLabel(it.status)} · ${orbisEventReceivedTime(it.updatedAtMs)}") }
        rule.lastError?.let { SentinelReadOnlyField("最近错误", it) }
        if (rule.pendingBlocked) Text("此前待发事件已暂停；不会因重新开启而自动补发。", style = MaterialTheme.typography.bodySmall)
        else if (rule.pendingEventId != null) Text("有待处理事件；这不表示 AI 已回复。", style = MaterialTheme.typography.bodySmall)
        SentinelReadOnlyField("规则标识", rule.id)
    }
}

@Composable
private fun SentinelReadOnlyDisclosure(
    tag: String,
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$title  ${if (expanded) "⌃" else "⌄"}", style = MaterialTheme.typography.bodyMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun SentinelTarget(assistantId: String, conversationId: String, assistantNames: Map<String, String>, conversationNames: Map<String, String>) {
    SentinelReadOnlyField("绑定 AI", assistantNames[assistantId]?.let { "$it · $assistantId" } ?: "名称未读取 · $assistantId")
    SentinelReadOnlyField("固定会话", conversationNames[conversationId]?.let { "$it · $conversationId" } ?: "名称未读取 · $conversationId")
}

@Composable
private fun SentinelReadOnlyField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Plain selectable text preserves authored prompt characters; no Markdown/HTML execution.
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun orbisSentinelConditionLabel(rule: OrbisSentinelRule): String = when (rule.type) {
    OrbisSentinelType.ONCE -> "定时一次 · ${rule.dueAtMs?.let(::orbisEventReceivedTime) ?: "时间未设置"}"
    OrbisSentinelType.INTERVAL -> "间隔触发 · 每 ${orbisSentinelDurationLabel(rule.intervalMs)}"
    OrbisSentinelType.CHAT_IDLE -> "固定会话未收到新用户消息达到 ${orbisSentinelDurationLabel(rule.thresholdMs)}"
    OrbisSentinelType.CHAT_LEFT -> "离开固定会话达到 ${orbisSentinelDurationLabel(rule.thresholdMs)}"
    OrbisSentinelType.APP_USAGE -> "应用 ${rule.appPackage ?: "未指定"} 使用达到 ${orbisSentinelDurationLabel(rule.thresholdMs)}"
    OrbisSentinelType.RITUAL -> "每天 ${rule.dailyAtLocal ?: "未设置"}${rule.dailyWindowEndLocal?.let { "–$it 内随机一次" } ?: " 定点一次"}"
    OrbisSentinelType.AGREEMENT -> "会话无新活动达到 ${orbisSentinelDurationLabel(rule.thresholdMs)}，按 ${rule.probabilityPercent}% 概率唤醒"
    OrbisSentinelType.SCREEN_OBSERVATION -> "每 ${orbisSentinelDurationLabel(rule.intervalMs)} 观察一次屏幕"
    OrbisSentinelType.NIGHT_USAGE -> "${rule.windowStartLocal}–${rule.windowEndLocal}，非聊天应用持续使用 ${orbisSentinelDurationLabel(rule.thresholdMs)}"
    OrbisSentinelType.SCREEN_ON -> "屏幕持续亮起 ${orbisSentinelDurationLabel(rule.thresholdMs)}"
    OrbisSentinelType.LOW_BATTERY -> "低电量情景，${rule.probabilityPercent}% 概率播报一次"
    OrbisSentinelType.GEOFENCE -> "按人类设置的围栏接收位置安全事件"
    OrbisSentinelType.TOUCH -> "收到已配置 Stackchan 链路的真实触摸事件"
}

internal fun orbisSentinelDurationLabel(milliseconds: Long?): String {
    if (milliseconds == null || milliseconds < 0L) return "未设置"
    if (milliseconds < 1_000L) return "$milliseconds 毫秒"
    val seconds = milliseconds / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return buildList {
        if (hours > 0L) add("$hours 小时")
        if (minutes > 0L) add("$minutes 分钟")
        if (remainder > 0L) add("$remainder 秒")
        if (milliseconds % 1_000L > 0L) add("${milliseconds % 1_000L} 毫秒")
    }.joinToString(" ")
}

internal fun orbisSentinelActionLabel(action: OrbisSentinelAction): String = when (action) {
    OrbisSentinelAction.WAKE -> "唤醒 AI"
    OrbisSentinelAction.DEVICE_CONTEXT -> "读取设备上下文后唤醒"
    OrbisSentinelAction.SCREENSHOT -> "截屏观察后唤醒"
}

internal fun orbisSentinelExecutionStatusLabel(status: OrbisSentinelExecutionStatus): String = when (status) {
    OrbisSentinelExecutionStatus.PENDING -> "待处理"
    OrbisSentinelExecutionStatus.STAGED -> "已准备，待送达"
    OrbisSentinelExecutionStatus.BLOCKED -> "已暂停或被阻止"
    OrbisSentinelExecutionStatus.ACCEPTED -> "收件箱已接收"
    OrbisSentinelExecutionStatus.CANCELLED -> "已取消"
}

internal fun orbisSentinelReceiptStateLabel(state: String): String = when (state) {
    "suppressed" -> "已暂停，未唤醒"
    else -> orbisReceiptStateLabel(state)
}

internal fun orbisWakeReasonLabel(reason: String): String = when (reason) {
    "wake_reply_in_progress" -> "触发时正在处理另一条回复或保存其结果。"
    "wake_save_in_progress" -> "触发时正在保存会话。"
    "wake_tool_pending" -> "当前有工具仍待审批或执行结束，没有替你批准或重做工具。"
    "wake_human_input_first" -> "触发时有新的用户消息待发送，优先处理用户消息。"
    "wake_gateway_unconfirmed" -> "旧连接尚未确认结束；这不是本条提醒在排队。可检查并恢复后续哨兵。"
    "wake_fresh_input_only" -> "此前中断后仅恢复了新的人类输入，自动投递还未恢复。"
    "wake_unknown_tool_result" -> "旧工具结果未知，自动投递需要确认；不会重新执行旧工具。"
    "wake_storage_unavailable" -> "保护记录暂时不可读，请检查手机存储，勿清除应用数据。"
    "wake_local_recovery_unconfirmed" -> "旧回复的本地恢复尚未确认，原记录保留。"
    "wake_receipt_unconfirmed" -> "先前的回复或执行回执尚未确认保存，请检查存储。"
    "wake_recovery_in_progress" -> "触发时正在核对连接或恢复本地记录。"
    "wake_recovery_commit_unconfirmed" -> "上次恢复操作尚未确认保存，可重新检查恢复。"
    "wake_owner_changed" -> "原连接的助手归属已变化，未把提醒送给其他助手。"
    "wake_restart_no_replay" -> "这是重启前未发送的旧提醒，已结束本次，不再补发。"
    "wake_preflight_failed" -> "本次发送准备未完成，尚未调用模型或工具。"
    "wake_admission_changed" -> "发送前状态发生变化，本次未发送。"
    else -> reason
}
