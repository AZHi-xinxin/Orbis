package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.soup.*
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** A local settings editor. Opening, selecting and saving never calls a model. */
@Composable
internal fun OrbisSoupDmSettingsDialog(onDismiss: () -> Unit, repository: SoupRepository? = null) {
    val context = LocalContext.current.applicationContext
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val dmStore = remember(context) { LocalSoupDmSettings.open(context) }
    val dm by dmStore.state.collectAsStateWithLifecycle()
    var loadedRepository by remember(repository) { mutableStateOf(repository) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var independentConfirmed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        if (repository == null) {
            try { loadedRepository = withContext(Dispatchers.IO) { LocalSoup.open(context) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "本机对局设置暂时无法读取，原文件未覆盖。" }
        }
    }
    LaunchedEffect(dm.selectionId, dm.loaded) { baseUrl = dm.baseUrl; modelId = dm.modelId }
    val repo = loadedRepository
    val gameState = repo?.state?.collectAsStateWithLifecycle()?.value
    val canEdit = repo != null && dm.canEdit && !saving && gameState?.active?.pending?.state != SoupAttemptState.RUNNING
    val providers = remember(settings) {
        settings.providers.map { provider -> provider.copyProvider(models = provider.models.filter { model ->
            runCatching { soupModelSelection(settings, model.id.toString()) }.isSuccess
        }) }.filter { it.enabled && it.models.isNotEmpty() }
    }
    fun update(action: suspend () -> Unit) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            try { withContext(NonCancellable + Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = soupErrorText(failure) }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("独立 DM API · 本机设置") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("你和自己的 AI 共同推理，DM 只主持本局。可复用已有提供商账号，也可单独填接口；不会更改主聊天模型、角色或私人记忆设置。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !custom, onClick = { custom = false }, label = { Text("复用已有模型") })
                FilterChip(selected = custom, onClick = { custom = true }, label = { Text("单独填写 API") })
            }
            val selected = gameState?.hostModelId
            Text(when {
                selected == null -> "当前尚未选择主持。"
                selected == dm.selectionId -> "当前主持：独立 API · ${dm.modelId}\n${dm.baseUrl}"
                else -> providers.flatMap { it.models }.firstOrNull { it.id.toString() == selected }
                    ?.let { "当前主持：${it.displayName.ifBlank { it.modelId }}" } ?: "原主持当前不可用，请重新选择。"
            }, style = MaterialTheme.typography.bodySmall)
            if (!custom) {
                Text("只显示已启用、已填密钥的官方直连模型；不会自动沿用主聊天地址或记忆网关。选择不发起请求。", style = MaterialTheme.typography.bodySmall)
                if (providers.isNotEmpty() && canEdit) ModelSelector(
                    modelId = selected?.takeUnless { it == dm.selectionId }?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                    providers = providers, type = ModelType.CHAT,
                    onSelect = { model -> update { requireNotNull(repo).selectHost(model.id.toString()) } },
                ) else Text(if (settings.init || !dm.loaded) "正在读取本机设置…" else "没有可选直连模型时，可先配置提供商，或在右侧单独填写 API。")
            } else {
                Text("支持标准 OpenAI-compatible Chat Completions。填写 HTTPS 基础地址（例如 https://api.deepseek.com/v1），不附加 /chat/completions。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = baseUrl, onValueChange = { if (it.length <= 240) { baseUrl = it; independentConfirmed = false } }, label = { Text("HTTPS 基础地址") }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = modelId, onValueChange = { if (it.length <= 200) modelId = it }, label = { Text("模型 ID") }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { if (it.length <= 4096) apiKey = it }, label = { Text("API Key（修改时重新填写）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = canEdit, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = independentConfirmed, onCheckedChange = { independentConfirmed = it }, enabled = canEdit)
                    Text("我确认这是独立主持接口，不是注入私人记忆的聊天网关。应用只发送本局内容，但无法验证远端是否另加上下文。", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = canEdit && independentConfirmed && baseUrl.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank(), onClick = {
                    val endpoint = baseUrl; val model = modelId; val secret = apiKey
                    apiKey = ""
                    update {
                        val identity = dmStore.save(endpoint, model, secret, independentEndpointConfirmed = true)
                        requireNotNull(repo).selectHost(identity)
                    }
                }) { Text("保存并选为 DM（不调用）") }
                if (dm.selectionId != null) {
                    Text("密钥已加密保存在此设备，不显示、不回填，不随对局备份导出。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = canEdit, onClick = { update { requireNotNull(repo).selectHost(requireNotNull(dm.selectionId)) } }) { Text("使用已保存的独立 API") }
                    TextButton(enabled = !saving, onClick = { confirmClear = true }) { Text("移除此设备的独立 API") }
                }
            }
            Text("每次判断或评分仍须在游戏页面逐次确认，可能计费；不开启自动重试。仅发送本局汤底、事实、公开问答与本次内容，不发送私聊、伙伴设定、记忆或工具。", style = MaterialTheme.typography.bodySmall)
            if (saving) Text("正在保存本机设置…")
            (error ?: dm.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!dm.canEdit && dm.loaded) {
                TextButton(enabled = !saving, onClick = { update { dmStore.reload() } }) { Text("重新读取 DM 设置") }
                TextButton(enabled = !saving, onClick = { confirmClear = true }) { Text("移除无法读取的 DM 设置") }
            }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("完成") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("移除独立 DM API？") },
        text = { Text("仅移除此设备单独保存的 DM 密钥与地址，不删除对局，不改提供商、主聊天或云端数据。如仍选着此 API，之后需重新选主持。") },
        confirmButton = { TextButton(enabled = !saving, onClick = { confirmClear = false; update { dmStore.clear() } }) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
