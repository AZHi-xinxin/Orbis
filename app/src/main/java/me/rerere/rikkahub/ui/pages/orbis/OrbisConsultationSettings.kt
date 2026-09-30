package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.integration.*
import org.koin.compose.koinInject

/** Deliberately separate from TechHub/ST settings: this stores ONLY a human consultation token. */
@Composable
fun OrbisConsultationSettingsEntry() {
    if (!me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled) {
        var showNotice by remember { mutableStateOf(false) }
        OrbisSettingsLink("咨询室", "正在开发，暂未开放") { showNotice = true }
        if (showNotice) OrbisConsultationUnavailableDialog { showNotice = false }
        return
    }
    val store = koinInject<OrbisIntegrationConnections>()[OrbisIntegration.CONSULTATION]
    val state by store.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    OrbisSettingsLink("咨询室 · 偷听角", when {
        state.error != null -> "本机授权读取失败 · 点击处理"
        state.available -> "已启用入口 · 独立人类连接"
        else -> "默认关闭 · 需专用中继与人类授权"
    }) { open = true }
    if (open) OrbisConsultationSettingsDialog { open = false }
}

@Composable
internal fun OrbisConsultationSettingsDialog(onDismiss: () -> Unit) {
    if (!me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled) {
        OrbisConsultationUnavailableDialog(onDismiss)
        return
    }
    val store = koinInject<OrbisIntegrationConnections>()[OrbisIntegration.CONSULTATION]
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by remember(state.revision) { mutableStateOf(state.baseUrl) }
    var token by remember { mutableStateOf("") }
    var enabled by remember(state.revision) { mutableStateOf(state.enabled) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var runtime by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { token = ""; if (!busy) onDismiss() }
        }
        lifecycle.addObserver(observer)
        onDispose { token = ""; lifecycle.removeObserver(observer) }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); token = ""; onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存，请检查中继根地址与人类专用 Token；换地址必须重新填写授权。旧配置未主动清除。" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) { token = ""; onDismiss() } }, title = { Text("咨询室 · 人类连接") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("面向有需要的 AI 的独立技术咨询通道，方向包括方法、工具、技术与流程。咨询师、接待资格与收费方式由你选择的服务方说明；模型调用使用各自的连接和额度。")
            Text("此页是人类观察入口。运行设置可用邀请码配对独立 AI 执行端；保存连接不开始咨询或调用模型。正式开放以平台验收状态为准。")
            TextButton(onClick = { runtime = true }, enabled = !busy) { Text("邀请码接入 / AI 待命 / 工作资料") }
            Text("只填独立人类 Token，不使用聊天密钥、ST、TechHub、AI 执行端或平台 Token。配置在本机独立加密保存，不进入普通设置/聊天导出。")
            Text("服务基础地址填写中继根地址，不包含 /v1/consultation。优先 HTTPS；HTTP 仅限本机、局域网或 Tailscale IP，局域网明文 HTTP 不提供加密。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.canEdit) {
                OutlinedTextField(address, { address = it }, label = { Text("咨询室中继根地址") }, singleLine = true, enabled = !busy)
                OutlinedTextField(token, { token = it }, label = { Text(if (state.configured) "新的人类 Token（留空保留）" else "人类专用 Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启用咨询室入口")
                    Switch(enabled, { enabled = it }, enabled = !busy)
                }
            } else TextButton(onClick = { scope.launch { store.reload() } }, enabled = !busy) { Text("重新读取本机授权") }
            Text("默认只看各自 AI 愿意公开的段落。平台查验需要另行授权且留痕，不承诺运营者无法读取。离开页面或退到后台即清除本页显示，返回需手动刷新。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.configured || state.error != null) TextButton(onClick = { clear = true }, enabled = !busy) { Text("清除本机咨询室连接") }
        }
    }, confirmButton = { TextButton(onClick = { perform {
        val root = consultationBase(address)
        require(!root.endsWith("/v1/consultation"))
        if (token.isNotBlank()) consultationToken(token)
        store.save(root, token, enabled)
    } }, enabled = state.canEdit && !busy) { Text("仅保存本机设置") } },
        dismissButton = { TextButton(onClick = { token = ""; onDismiss() }, enabled = !busy) { Text("取消") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清除此手机的咨询室授权？") },
        text = { Text("不删除咨询记录，不停止进行中的咨询，也不撤销服务器授权。若要停止会话，请先回咨询室明确停止。") },
        confirmButton = { TextButton(onClick = { clear = false; perform { store.clear() } }) { Text("清除连接") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("保留") } })
    if (runtime) OrbisConsultationRuntimeSettings { runtime = false }
}
