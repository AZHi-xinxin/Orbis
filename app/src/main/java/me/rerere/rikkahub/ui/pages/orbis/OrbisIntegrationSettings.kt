package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderSetting
import org.koin.compose.koinInject

@Composable
fun OrbisIntegrationSettingsEntry(kind: OrbisIntegration) {
    val connections = koinInject<OrbisIntegrationConnections>()
    val state by connections[kind].state.collectAsStateWithLifecycle()
    val bootstrap by connections.atlasBootstrap.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    OrbisSettingsLink(kind.title, when {
        kind == OrbisIntegration.ST_ATLAS && bootstrap.phase in setOf(OrbisAtlasBootstrapPhase.PENDING,
            OrbisAtlasBootstrapPhase.REVOKING) -> "授权结果待确认 · 点击处理"
        kind == OrbisIntegration.ST_ATLAS && bootstrap.phase == OrbisAtlasBootstrapPhase.UNREADABLE -> "授权读取失败 · 点击处理"
        kind == OrbisIntegration.ST_ATLAS && bootstrap.phase == OrbisAtlasBootstrapPhase.REVOKED -> "授权已撤销 · 点击管理"
        state.error != null -> "授权读取失败 · 点击处理"
        state.available -> "已启用 · 管理地址与授权"
        else -> "未启用 · 可配置独立连接"
    }) { open = true }
    if (open) OrbisIntegrationSettingsDialog(kind) { open = false }
}

/** Draft credentials never enter SaveableState, global Settings, logs, or exported configuration. */
@Composable
internal fun OrbisIntegrationSettingsDialog(kind: OrbisIntegration, onDismiss: () -> Unit) {
    if (kind == OrbisIntegration.ST_ATLAS) {
        OrbisAtlasBootstrapSettingsDialog(onDismiss)
        return
    }
    val store = koinInject<OrbisIntegrationConnections>()[kind]
    val state by store.state.collectAsStateWithLifecycle()
    var address by remember(state.revision) { mutableStateOf(state.baseUrl) }
    var token by remember { mutableStateOf("") }
    var enabled by remember(state.revision) { mutableStateOf(state.enabled) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); token = ""; onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存。请检查地址与专用 Token；更换地址需要重新填写 Token。旧配置仍保留。" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(kind.title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (kind == OrbisIntegration.ST_ATLAS)
                    "仅连接 ST 星盘专用只读服务：只取类型、时间和真实关联，不取记忆正文、不修改 ST。不是聊天网关或 MCP 地址。"
                else "TechHub 是协作消息服务，与多 AI 群聊独立。只有启用并填写专用 Token 后才显示入口；使用你自己的身份，不共用后台代理 Token。")
                Text("Token 在本机加密保存，不随设置导出。优先使用 HTTPS；HTTP 仅允许本机、局域网或 Tailscale IP。保存不自动测试连接。")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.canEdit) {
                    OutlinedTextField(address, { address = it }, label = { Text("服务基础地址") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(token, { token = it }, label = { Text(if (state.configured) "新 Token（留空保持）" else "专用 Token") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("启用连接")
                        Switch(enabled, { enabled = it }, enabled = !busy)
                    }
                } else TextButton(onClick = { scope.launch { store.reload() } }, enabled = !busy) { Text("重新读取配置") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.configured || state.error != null) TextButton(onClick = { clearConfirm = true }, enabled = !busy) { Text("清除本机连接") }
            }
        },
        confirmButton = { TextButton(onClick = { perform { store.save(address, token, enabled) } }, enabled = state.canEdit && !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除本机连接？") },
        text = { Text("只清除此手机的地址和授权，不删除 ST 记忆、TechHub 消息，也不撤销服务端 Token。") },
        confirmButton = { TextButton(onClick = { clearConfirm = false; perform { store.clear() } }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("保留") } })
}

/** Opening and selecting a row only reads local Settings; networking starts at explicit buttons. */
@Composable
private fun OrbisAtlasBootstrapSettingsDialog(onDismiss: () -> Unit) {
    val connections = koinInject<OrbisIntegrationConnections>()
    val bootstrap = connections.atlasBootstrap
    val managed by bootstrap.state.collectAsStateWithLifecycle()
    val connection by connections[OrbisIntegration.ST_ATLAS].state.collectAsStateWithLifecycle()
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var revokeConfirm by remember { mutableStateOf(false) }
    var forgetConfirm by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var address by remember(connection.revision) { mutableStateOf(connection.baseUrl) }
    var token by remember { mutableStateOf("") }
    var enabled by remember(connection.revision) { mutableStateOf(connection.enabled) }
    val providers = settings.providers.filterIsInstance<ProviderSetting.OpenAI>().filter { it.enabled }
    val selectedSource = providers.singleOrNull { it.id.toString() == selected }
        ?.let { runCatching { atlasBootstrapSource(it) }.getOrNull() }
    val idle = managed.loaded && !managed.busy
    fun launch(action: suspend () -> Unit) { if (idle) scope.launch { action() } }
    AlertDialog(onDismissRequest = { if (!managed.busy) onDismiss() }, title = { Text("ST 星图连接") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("只查看记忆类型、存入时间和真实关联，不读取正文、不修改记忆。不会更换聊天模型或 ST 身份。")
            Text("打开此页不会联网。授权加密保存在此手机，不随普通设置导出。优先 HTTPS；局域网明文 HTTP 不具备加密保护。")
            OrbisStModelSettingsEntry(settings.providers, selected ?: managed.providerId, idle, onDismiss)
            when (managed.phase) {
                OrbisAtlasBootstrapPhase.NONE -> {
                    Text("使用已有 ST 连接", style = MaterialTheme.typography.titleSmall)
                    Text("从已经设置的单密钥 OpenAI 兼容连接中选择；名称或图标不代表它支持 ST。")
                    if (providers.isEmpty()) Text("没有可选连接，请先在连接设置中添加你的 ST 网关。")
                    providers.forEach { provider ->
                        val source = runCatching { atlasBootstrapSource(provider) }.getOrNull()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected == provider.id.toString(), onClick = { selected = provider.id.toString() },
                                enabled = idle && source != null)
                            Column(Modifier.weight(1f)) {
                                Text(provider.name.take(100))
                                Text(source?.root ?: "暂不支持：需单密钥，根地址或 /v1 地址", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(onClick = { confirm = true }, enabled = idle && selectedSource != null && connection.canEdit) {
                        Text("连接星图")
                    }
                    TextButton(onClick = { advanced = !advanced }, enabled = idle) { Text("高级：手动填写专用只读连接") }
                    if (advanced) {
                        Text("此处不是聊天密钥。只填写星图只读服务根地址与独立 Token；保存不测试连接。")
                        OutlinedTextField(address, { address = it }, label = { Text("只读服务基础地址") }, singleLine = true, enabled = idle)
                        OutlinedTextField(token, { token = it }, label = { Text(if (connection.configured) "新 Token（留空保持）" else "专用只读 Token") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = idle)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("启用手填连接"); Switch(enabled, { enabled = it }, enabled = idle)
                        }
                        TextButton(onClick = { launch { bootstrap.saveManual(address, token, enabled); token = "" } },
                            enabled = idle && connection.canEdit) { Text("保存手填连接") }
                        if (connection.configured || connection.error != null)
                            TextButton(onClick = { clearConfirm = true }, enabled = idle) { Text("清除本机手填连接") }
                    }
                }
                OrbisAtlasBootstrapPhase.PENDING -> {
                    Text("连接结果待确认\n${managed.root}")
                    Text("已保存唯一待确认授权；连接结果待确认，原设置仍保留。不会重新签发或自动重试，星图读取暂时关闭。")
                    TextButton(onClick = { launch { bootstrap.retry { settingsStore.settingsFlow.value.providers } } }, enabled = idle) { Text("重试确认原请求") }
                }
                OrbisAtlasBootstrapPhase.ACTIVE -> {
                    Text("独立只读授权已确认\n${managed.root}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("启用星图读取")
                        Switch(connection.enabled, { value -> launch { bootstrap.setEnabled(value) } }, enabled = idle)
                    }
                    Text("授权持续有效直到撤销或服务器停用；停用读取不等于撤销授权。")
                }
                OrbisAtlasBootstrapPhase.REVOKING -> Text("撤销结果尚未确认；唯一凭据仍保留，星图读取已暂停。请手动重试撤销。")
                OrbisAtlasBootstrapPhase.REVOKED -> Text("服务器已确认撤销。这次请求不会复活；清理本机记录后可重新明确登记。")
                OrbisAtlasBootstrapPhase.UNREADABLE -> Text("本机授权文件无法安全读取；没有覆盖或自动联网。")
            }
            if (managed.phase in setOf(OrbisAtlasBootstrapPhase.PENDING, OrbisAtlasBootstrapPhase.ACTIVE,
                    OrbisAtlasBootstrapPhase.REVOKING))
                TextButton(onClick = { revokeConfirm = true }, enabled = idle) { Text(if (managed.phase == OrbisAtlasBootstrapPhase.REVOKING) "重试撤销" else "撤销本机这份授权") }
            if (managed.managed && managed.loaded)
                TextButton(onClick = { forgetConfirm = true }, enabled = idle) { Text(if (managed.phase == OrbisAtlasBootstrapPhase.REVOKED) "清理已撤销的本机记录" else "仅忘记本机记录…") }
            if (managed.phase == OrbisAtlasBootstrapPhase.UNREADABLE || connection.error != null)
                TextButton(onClick = { launch { connections[OrbisIntegration.ST_ATLAS].reload(); bootstrap.reload() } }, enabled = idle) { Text("重新读取本机配置") }
            managed.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            connection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (managed.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在确认，请稍候…") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !managed.busy) { Text("关闭") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("连接只读星图？") },
        text = { Text("向所选连接 ${selectedSource?.root.orEmpty()} 申请独立星图只读授权；只取类型、时间、关联。确认成功后替换本机原星图连接，不更改聊天设置。") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            selected?.let { id -> launch { bootstrap.connect(id) { settingsStore.settingsFlow.value.providers } } }
        }, enabled = idle && selectedSource != null) { Text("确认连接") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
    if (revokeConfirm) AlertDialog(onDismissRequest = { revokeConfirm = false }, title = { Text("撤销本机只读授权？") },
        text = { Text("只撤销此手机登记的星图授权，不删除 ST 记忆、不更改聊天连接。请求结果不明时会保留凭据。") },
        confirmButton = { TextButton(onClick = { revokeConfirm = false; launch { bootstrap.revoke() } }) { Text("撤销") } },
        dismissButton = { TextButton(onClick = { revokeConfirm = false }) { Text("保留") } })
    if (forgetConfirm) AlertDialog(onDismissRequest = { forgetConfirm = false }, title = { Text("仅忘记本机记录？") },
        text = { Text("这不是服务端撤销。若撤销未确认，服务器授权可能仍有效；忘记后将失去用该凭据自助撤销的能力。不会删除 ST 记忆。") },
        confirmButton = { TextButton(onClick = { forgetConfirm = false; launch { bootstrap.forgetLocal() } }) { Text("仅忘记本机") } },
        dismissButton = { TextButton(onClick = { forgetConfirm = false }) { Text("保留凭据") } })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除手填连接？") },
        text = { Text("只清除此手机的手填配置，不撤销服务器 Token，也不删除记忆。") },
        confirmButton = { TextButton(onClick = { clearConfirm = false; launch { bootstrap.clearManual() } }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("保留") } })
}
