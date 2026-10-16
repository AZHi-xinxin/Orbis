package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.sentinel.*
import org.koin.compose.koinInject

@Composable
internal fun OrbisProactiveDecisionEntry() {
    val context = LocalContext.current
    val sentinels = remember(context.applicationContext) { OrbisSentinels.open(context) }
    val settings by koinInject<SettingsStore>().settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(ProactiveDecisionState()) }
    var rules by remember { mutableStateOf(emptyList<OrbisSentinelRule>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEnable by remember { mutableStateOf(false) }
    fun refresh() {
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { sentinels.proactive.snapshot() to sentinels.rules.refresh().rules }.let { state = it.first; rules = it.second }; error = null }
            catch (_: Exception) { error = "设置未能读取；不会按空配置覆盖原记录。" }
            finally { busy = false }
        }
    }
    fun save(next: ProactiveDecisionSettings) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sentinels.proactive.configure(next.enabled, next.ruleIds, next.maxPerDay, next.cooldownMinutes) }
                state = withContext(Dispatchers.IO) { sentinels.proactive.snapshot() }
                error = null
            } catch (_: Exception) { error = "设置未保存，请重试。" }
            finally { busy = false }
        }
    }
    OrbisSettingsLink("主动联系前先想一想", "默认关闭 · 模型可选择保持安静 · 人类控制",
        { open = true; refresh() })
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("主动联系判断") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("为选中的闲置/定时提醒增加一次只读判断。模型选择 NO_ACTION 时不产生聊天气泡或通知；决定联系才走原助手、原对话的正常回复和工具权限。")
            Text("判断本身也消耗你所配置模型的用量。最多每 24 小时 3 次，至少间隔 30 分钟；失败、取消、沉默都计入次数，不自动重试。", style = MaterialTheme.typography.bodySmall)
            Text("判断仅使用现有文字、角色、会话提示和可用本地记忆；不调用工具、不新读屏、不生图、不发起 OCR，也不另存服务端聊天副本。", style = MaterialTheme.typography.bodySmall)
            Text("离开对话后仍可只读判断。有人正在提交、队列暂停、等待工具确认或恢复状态无法确认时会跳过，不会偷偷新开聊天。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("启用只读判断")
                Switch(state.settings.enabled, enabled = !busy && error == null, onCheckedChange = {
                    if (it) confirmEnable = true else save(state.settings.copy(enabled = false))
                })
            }
            Text("关闭后，所选提醒恢复原来的直接唤醒方式；要完全暂停自动消息，请关闭哨兵总开关。", style = MaterialTheme.typography.bodySmall)
            Text("每 24 小时最多 ${state.settings.maxPerDay} 次")
            Row { (1..3).forEach { count -> FilterChip(selected = state.settings.maxPerDay == count, enabled = !busy,
                onClick = { save(state.settings.copy(maxPerDay = count)) }, label = { Text("$count 次") }, modifier = Modifier.padding(end = 8.dp)) } }
            Text("判断间隔 ${state.settings.cooldownMinutes} 分钟")
            Row { listOf(30, 60, 120).forEach { interval -> FilterChip(selected = state.settings.cooldownMinutes == interval, enabled = !busy,
                onClick = { save(state.settings.copy(cooldownMinutes = interval)) }, label = { Text("$interval 分") }, modifier = Modifier.padding(end = 8.dp)) } }
            val choices = rules.filter(ProactiveDecisionStore::supports)
            Text("适用提醒", style = MaterialTheme.typography.titleSmall)
            if (choices.isEmpty()) Text("先在哨兵设置中创建「聊天闲置」或「定时间隔」的唤醒提醒，再在这里选中。")
            choices.forEach { rule -> Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = rule.id in state.settings.ruleIds, enabled = !busy, onCheckedChange = { selected ->
                    save(state.settings.copy(ruleIds = if (selected) state.settings.ruleIds + rule.id else state.settings.ruleIds - rule.id))
                })
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(rule.name.ifBlank { if (rule.type == OrbisSentinelType.CHAT_IDLE) "聊天闲置提醒" else "定时间隔提醒" })
                    Text((settings.assistants.firstOrNull { it.id.toString() == rule.assistantId }?.name ?: "原助手") +
                        if (rule.enabled) " · 提醒已启用" else " · 提醒当前关闭", style = MaterialTheme.typography.bodySmall)
                }
            } }
            val recent = state.receipts.count { System.currentTimeMillis() - it.claimedAt < ProactiveDecisionStore.DAY_MS }
            Text("最近 24 小时已判断 $recent 次。开关和清理聊天不会重置次数。", style = MaterialTheme.typography.bodySmall)
            state.receipts.takeLast(5).reversed().forEach { receipt -> Text(when (receipt.state) {
                "no_action" -> "选择安静 · 未投递聊天"; "speak" -> "选择联系 · 交给原对话"; "claimed" -> "正在判断"
                "stale" -> "对话或设置已变化 · 结果丢弃"; "unknown" -> "上次结果未知 · 不补调"; else -> "判断未完成 · 未重试"
            }, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("完成") } },
        dismissButton = { TextButton(enabled = !busy, onClick = ::refresh) { Text("刷新记录") } })
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("允许后台只读判断？") },
        text = { Text("到所选提醒触发时，会调用该助手原来配置的模型进行判断，即使决定不发消息也会产生模型用量。关闭哨兵总开关可暂停。") },
        confirmButton = { TextButton(onClick = { confirmEnable = false; save(state.settings.copy(enabled = true)) }) { Text("开启") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
}
