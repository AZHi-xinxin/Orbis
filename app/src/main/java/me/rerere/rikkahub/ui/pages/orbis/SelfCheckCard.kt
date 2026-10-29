package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.diagnostics.*
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

@Composable internal fun SelfCheckCard(owner: String, enabled: Boolean, onEnable: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settings = koinInject<SettingsStore>()
    val mcp = koinInject<McpManager>()
    val workspace = koinInject<WorkspaceRepository>()
    val host = remember(context, settings, mcp, workspace) { SelfCheckHost(context, settings, mcp, workspace) }
    SelfCheckCardContent(owner, enabled, onEnable, check = { host.check(owner, it) })
}

@Composable internal fun SelfCheckCardContent(owner: String, enabled: Boolean, onEnable: (Boolean) -> Unit,
    check: suspend (String) -> SelfCheckReport) {
    var open by remember(owner) { mutableStateOf(false) }
    var focus by remember(owner) { mutableStateOf("overview") }
    var report by remember(owner) { mutableStateOf<SelfCheckReport?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val labels = listOf("概览", "工具", "模型", "主题", "MCP", "工作区", "表情包", "系统权限")
    fun runCheck() {
        busy = true; report = null; error = null
        scope.launch {
            try { report = check(focus) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "本次检查未完成，请稍后重试；当前不能判断配置是否正常。" }
            finally { busy = false }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("AI自检", style = MaterialTheme.typography.titleMedium)
                    Text("按需查看当前工具、配置和故障线索", style = MaterialTheme.typography.bodySmall) }
                Switch(enabled, onEnable, modifier = Modifier.testTag("self-check-enable"))
            }
            Text("你随时可以直接检查；开启后AI也能调用。详细结果仅在查询时返回。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { open = true; runCheck() }, modifier = Modifier.testTag("self-check-open")) { Text("检查当前助手") }
        }
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("当前助手自检") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只读本机，不调用模型。检查结果可复制给AI或反馈给开发者。")
                selfCheckFocus.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { item -> FilterChip(selected = focus == item, onClick = { focus = item; runCheck() }, enabled = !busy,
                        label = { Text(labels[selfCheckFocus.indexOf(item)]) }, modifier = Modifier.weight(1f).testTag("self-check-focus-$item")) }
                } }
                if (busy) Text("正在检查…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                report?.let { result ->
                    Text("${result.version} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(result.checkedAt))}", style = MaterialTheme.typography.labelSmall)
                    result.items.forEach { item ->
                        Text(item.title + when (item.status) { "blocked" -> " · 需要处理"; "unknown" -> " · 待核实"; "ok" -> " · 已检查"; else -> "" }, style = MaterialTheme.typography.titleSmall)
                        Text(item.evidence, style = MaterialTheme.typography.bodySmall)
                        Text(item.next, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                    Text("最近故障（最多五条）", style = MaterialTheme.typography.titleSmall)
                    if (result.recent.isEmpty()) Text("没有可用故障摘要。此功能安装前的故障不会补录。")
                    result.recent.reversed().forEach { event ->
                        Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.at))} · ${event.code}", style = MaterialTheme.typography.labelSmall)
                        Text(diagnosticCodes.getValue(event.code), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = report != null && !busy, onClick = {
            report?.let { clipboard.setText(AnnotatedString(Json { prettyPrint = true }.encodeToString(it))) }
        }) { Text("复制报告") } }, dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("返回") } })
}
