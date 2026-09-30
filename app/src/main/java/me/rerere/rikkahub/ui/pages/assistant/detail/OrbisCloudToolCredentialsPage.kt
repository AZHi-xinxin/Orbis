package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolCredentialStore
import me.rerere.rikkahub.data.orbis.cloudtools.validateCloudGatewayCredential
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar
import org.koin.compose.koinInject

internal fun canSaveCloudToolCredentials(baseUrl: String, token: String): Boolean = runCatching {
    validateCloudGatewayCredential(baseUrl.trim(), token.trim())
}.isSuccess

/** One shared, local-only credential editor. Existing tokens are never read back into UI state. */
@Composable
fun OrbisCloudToolCredentialsPage() {
    val store = koinInject<CloudToolCredentialStore>()
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Deliberately not rememberSaveable: do not put a token into saved instance state/backups.
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { store.reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本机授权状态暂未读取成功，请返回后重试。" }
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !initialized) { baseUrl = state.baseUrl; initialized = true }
    }
    OrbisSettingsScaffold(topBar = {
        OrbisSettingsTopBar(title = { Text("原生云端工具授权") }, navigationIcon = { BackButton() },
            subtitle = { Text("藏书阁共读 · Orbis工具 · 海龟汤共用") })
    }) { padding ->
        OrbisCloudToolCredentialForm(
            baseUrl = baseUrl, token = token, configured = state.configured,
            enabled = state.loaded && state.canEdit && !saving, saving = saving,
            canClear = state.loaded && !saving && (state.configured || state.error != null),
            error = error ?: state.error, status = status,
            onBaseUrlChange = { baseUrl = it; error = null; status = null },
            onTokenChange = { token = it; error = null; status = null },
            onSave = {
                val requestedUrl = baseUrl.trim()
                val requestedToken = token.trim()
                if (canSaveCloudToolCredentials(requestedUrl, requestedToken) && !saving) {
                    saving = true
                    scope.launch {
                        try {
                            store.save(requestedUrl, requestedToken)
                            token = ""
                            error = null
                            status = "配置已加密保存。请返回工具页刷新目录；保存配置不代表服务已连通。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "授权配置尚未确认保存成功，请检查 HTTPS 地址和设备令牌后重试。" }
                        finally { saving = false }
                    }
                }
            },
            onClear = { confirmClear = true }, modifier = Modifier.padding(padding),
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { if (!saving) confirmClear = false }, title = { Text("移除本机云端授权？") },
        text = { Text("三类工具共用此授权。这里只移除本机保存的凭证，不删除助手选用和云端数据，也不等于在服务器撤销令牌。") },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    store.clear()
                    baseUrl = ""; token = ""; error = null
                    status = "本机授权已移除。云端令牌如需吊销，请在服务端单独处理。"
                    confirmClear = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "尚未确认移除成功，请重试。"; confirmClear = false }
                finally { saving = false }
            }
        }) { Text("移除本机授权") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { confirmClear = false }) { Text("取消") } },
    )
}

@Composable
internal fun OrbisCloudToolCredentialForm(
    baseUrl: String, token: String, configured: Boolean, enabled: Boolean, saving: Boolean,
    error: String?, status: String?,
    onBaseUrlChange: (String) -> Unit, onTokenChange: (String) -> Unit,
    onSave: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier,
    canClear: Boolean = configured && enabled,
) {
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("这是可选的云端连接，仅供三类工具的云端选项共用。本机后花园、藏书阁和海龟汤不需要此授权；请在各自入口的“本机工具”中开启。云端 AI 选用仍由各自开关决定。")
        Text(if (configured) "已保存设备授权。现有令牌不会显示或回填；修改配置需重新输入令牌。" else "尚未配置。请在设备上输入部署方提供的专用工具网关地址和设备令牌。",
            style = MaterialTheme.typography.bodySmall)
        Text("不要使用数据库管理员密钥或把令牌发到聊天里。凭证使用 Android Keystore 加密并存于不参与系统备份的私有目录。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = baseUrl, onValueChange = onBaseUrlChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("cloud-credential-url"), label = { Text("工具网关 HTTPS 地址") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(value = token, onValueChange = onTokenChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("cloud-credential-token"), label = { Text("设备令牌（不含 Bearer 前缀）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        Text("仅支持已部署的 Tailnet HTTPS 工具网关（.ts.net，端口 18910）。地址中不要携带账号、密码、路径参数或令牌。保存只写本机配置，不执行云端工具。",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it, modifier = Modifier.testTag("cloud-credential-status")) }
        Button(onClick = onSave, enabled = enabled && canSaveCloudToolCredentials(baseUrl, token),
            modifier = Modifier.fillMaxWidth().testTag("cloud-credential-save")) {
            Text(if (saving) "正在保存…" else "加密保存配置")
        }
        if (configured || canClear) TextButton(onClick = onClear, enabled = canClear,
            modifier = Modifier.fillMaxWidth().testTag("cloud-credential-clear")) { Text("移除本机授权") }
    }
}
