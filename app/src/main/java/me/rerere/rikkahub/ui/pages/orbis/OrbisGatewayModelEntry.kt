package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Open is local-only; only Read requests /models, only Confirm writes the named AI binding. */
@Composable
internal fun OrbisGatewayModelEntry() {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val client = koinInject<OkHttpClient>()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Uuid?>(null) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var binding by remember { mutableStateOf<OrbisGatewayBinding?>(null) }
    var alias by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showOtherConnections by remember { mutableStateOf(false) }
    var gatewayProvider by remember { mutableStateOf<ProviderSetting.OpenAI?>(null) }
    var manageRoutes by remember { mutableStateOf(false) }
    val choices = remember(settings.providers) { orbisStModelEntryChoices(settings.providers) }
    val current = settings.assistants.singleOrNull { it.id == settings.assistantId }
    OrbisSettingsLink("ST 网关 · 添加线路与切换模型", "在手机授权添加兼容模型（如 Pro），再单独确认切换；身份与记忆保留",
        if (!settings.init && current != null && !busy) ({
            target = current.id
            selectedProvider = settings.providers.singleOrNull { provider -> provider.models.any { it.id == current.chatModelId } }?.id?.toString()
            showOtherConnections = false; gatewayProvider = null; manageRoutes = false; binding = null; alias = null
            notice = null; confirming = false; open = true
        }) else null)
    if (open && !manageRoutes) AlertDialog(onDismissRequest = { if (!busy) open = false },
        title = { Text("ST 网关模型") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("绑定对象：" + (settings.assistants.singleOrNull { it.id == target }?.let { orbisSettingsLabel(it.name, "当前 AI") } ?: "原 AI 已不可用"))
            Text("只影响此 AI 配置之后的新请求（同配置私聊共用）；不打断正在生成的回复。不改其他 AI、群成员快照、ST 身份、记忆或服务器默认。", style = MaterialTheme.typography.bodySmall)
            if (binding == null) {
                Text("1. 检测当前 AI 使用的连接")
                Text("这里选的是承载记忆的 ST，不是 DeepSeek、硅基等上游供应商。上游模型在检测通过后添加。", style = MaterialTheme.typography.bodySmall)
                if (choices.isEmpty()) Text("先在「自定义连接与模型」添加自己的 ST 网关连接。")
                choices.filter { showOtherConnections || it.providerId == selectedProvider }.forEach { choice -> Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = selectedProvider == choice.providerId, enabled = !busy,
                        onClick = { selectedProvider = choice.providerId; notice = null })
                    Text(choice.name + " · 待检测连接", Modifier.weight(1f).padding(top = 12.dp))
                } }
                TextButton(enabled = !busy, onClick = { showOtherConnections = !showOtherConnections }) {
                    Text(if (showOtherConnections) "收起其他连接" else "选择其他已有连接（尚未确认是 ST）")
                }
                TextButton(enabled = !busy && selectedProvider != null, onClick = {
                    val id = target ?: return@TextButton
                    val providerId = selectedProvider?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return@TextButton
                    busy = true; notice = null
                    scope.launch {
                        try {
                            val (assistant, provider) = orbisGatewayRevision(store.settingsFlow.value, id, providerId)
                            val aliases = readOrbisGatewayModels(client, provider)
                            val candidate = OrbisGatewayBinding(assistant, provider, aliases)
                            candidate.checkCurrent(store.settingsFlow.value)
                            binding = candidate; gatewayProvider = provider; alias = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { notice = (error as? OrbisGatewayModelException)?.message ?: "目录未读取成功，原绑定未变。" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在读取…" else "读取这个网关的模型目录") }
            } else {
                val value = binding!!
                Text("网关：${value.providerName}")
                Text("检测结果：目录声明支持 ST 主线路。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { manageRoutes = true }) { Text("添加模型线路 / 人类管理授权") }
                Text("2. 选择已公布的主模型线路；辅助无记忆线路不在这里显示。", style = MaterialTheme.typography.bodySmall)
                value.aliases.forEach { item -> Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = alias == item, enabled = !busy, onClick = { alias = item; confirming = false })
                    Text(item, Modifier.weight(1f).padding(top = 12.dp))
                } }
                Text("未在手机添加的型号会仅在本地新增，默认按文字模型配置；图片、工具与思考能力可到模型设置按服务实际支持调整。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { binding = null; alias = null; gatewayProvider = null; confirming = false }) { Text("重新读取 / 选择网关") }
            }
            Text("添加线路需要首次人类管理授权；配置保存不等于模型已试聊通过，也不会自动切换。确认切换只更改手机当前 AI 的绑定。", style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (confirming) Text("请确认：将「${binding?.assistantName}」之后的新请求绑定到「$alias」。其他身份与记录保持原样。")
        } },
        confirmButton = { TextButton(enabled = !busy && binding != null && alias != null, onClick = {
            if (!confirming) { confirming = true; return@TextButton }
            val choice = binding ?: return@TextButton
            val modelAlias = alias ?: return@TextButton
            busy = true; notice = null
            scope.launch {
                try {
                    store.updateGatewayModelBinding(choice, modelAlias)
                    notice = "已保存「${choice.assistantName}」的模型绑定；从之后的新请求生效。"
                    binding = null; alias = null; selectedProvider = null; confirming = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notice = (error as? OrbisGatewayModelException)?.message ?: "保存未确认，请重新进入核对；不会自动重试。"; confirming = false }
                finally { busy = false }
            }
        }) { Text(if (busy) "处理中…" else if (confirming) "确认仅修改这位 AI" else "核对绑定") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") } },
    )
    if (open && manageRoutes && gatewayProvider != null) OrbisGatewayRouteManager(
        gateway = gatewayProvider!!,
        providers = settings.providers,
        currentProviders = { store.settingsFlow.value.providers },
        onDismiss = { manageRoutes = false; binding = null; alias = null; confirming = false
            notice = "返回后请重新读取 ST 模型目录，再单独确认是否切换。" },
    )
}
