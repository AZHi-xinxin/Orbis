package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.orbis.ombre.OmbreRuntimeHost
import me.rerere.rikkahub.data.orbis.ombre.OmbreRuntimeState

@Composable
internal fun OmbreRuntimeCard(workspace: WorkspaceEntity) {
    if (!me.rerere.rikkahub.data.orbis.ombre.OMBRE_RUNTIME_ENTRY_ENABLED) return
    val context = LocalContext.current
    val host = remember(context) { OmbreRuntimeHost.get(context) }
    val states by host.states.collectAsStateWithLifecycle()
    val state = states[workspace.root] ?: OmbreRuntimeState()
    var enabled by remember(workspace.root) { mutableStateOf(host.enabled(workspace.root)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saved = host.saved(workspace.root)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OmbreBrain 持续运行", style = MaterialTheme.typography.titleMedium)
            Text("仅用于你选择装在这部手机的 OmbreBrain。离开聊天页面后继续托管；电脑、VPS 和 Termux 上的实例无需开启这里。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("允许此工作区托管 OmbreBrain", Modifier.weight(1f))
                Switch(enabled, onCheckedChange = { value ->
                    scope.launch { runCatching { withContext(Dispatchers.IO) { host.setEnabled(workspace.root, value) } }
                        .onSuccess { enabled = value }.onFailure { error = "设置未保存，请重试" } }
                })
            }
            Text(when (state.phase) {
                "starting" -> "正在启动 · 尚未完成健康检查"
                "ready" -> "正在运行 · 本地健康检查通过"
                "unhealthy" -> "进程仍在 · 暂未通过健康检查"
                "stopping" -> "正在停止 · 等待服务退出"
                "failed" -> "未正常运行 · ${state.code.orEmpty()}"
                else -> if (saved.spec == null) "等待 AI 登记实例" else "已配置 · 尚未运行"
            })
            if (state.phase == "ready") Text("模型回复、记忆存取仍需在聊天中验证。", style = MaterialTheme.typography.bodySmall)
            saved.spec?.let { spec -> Text("实例：${spec.source}", style = MaterialTheme.typography.bodySmall) }
            state.dashboardPort?.takeIf { state.phase == "ready" }?.let { port ->
                TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://127.0.0.1:$port"))) }) { Text("打开 OmbreBrain 管理页") }
                Text("MCP：http://127.0.0.1:$port/mcp · 可让 AI 用连接工具接入当前助手", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = enabled && !state.active && saved.spec != null, onClick = {
                    scope.launch { runCatching { withContext(Dispatchers.IO) { host.restartSaved(workspace.root) } }
                        .onFailure { error = "暂时无法启动，请确认 Rootfs 和实例文件仍在" } }
                }) { Text("启动已配置实例") }
                OutlinedButton(enabled = state.active, onClick = { host.stop(workspace.root) }) { Text("停止") }
            }
            Text(if (saved.spec == null && enabled) "开关已开启。回原聊天另发一条新消息，让 AI 用已有安装调用托管工具登记并启动；登记前上方启动按钮暂不可用，无需重新安装。"
                else if (saved.spec == null) "先确认要将 OmbreBrain 装在这部手机，再开启托管。此开关不会自动安装；首次登记由 AI 完成。"
                else if (saved.resumeOnAppOpen) "重新打开 Orbis 时会自动恢复这份 OmbreBrain；启动需要几秒。点停止后不再自动恢复，配置和记忆仍保留。"
                else "点启动后，Orbis 会记住运行状态，下次打开时自动恢复。已安装的实例可直接启动。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
