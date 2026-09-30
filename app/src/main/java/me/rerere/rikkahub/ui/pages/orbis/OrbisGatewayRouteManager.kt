package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant

/** No credential is rememberSaveable, logged, returned to chat, or sent before human confirmation. */
@Composable
internal fun OrbisGatewayRouteManager(gateway: ProviderSetting.OpenAI, providers: List<ProviderSetting>,
    currentProviders: () -> List<ProviderSetting>, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(gateway.id) { GatewayRouteStores.get(context, gateway.id) }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var adminToken by remember { mutableStateOf("") }
    var modeManual by remember { mutableStateOf(false) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf("") }
    var upstream by remember { mutableStateOf("https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf("") }
    var routeAlias by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<GatewayRouteConsent?>(null) }
    val gatewayRevision = remember(gateway) { JsonInstant.encodeToString<ProviderSetting>(gateway) }
    val latestProviders by rememberUpdatedState(currentProviders)
    fun stillGateway() {
        val fresh = latestProviders().singleOrNull { it.id == gateway.id }
        routeRequire(fresh != null && JsonInstant.encodeToString<ProviderSetting>(fresh) == gatewayRevision, "changed")
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = (error as? GatewayRouteException)?.message ?: "操作未确认；请核对上次提交，不会自动重试。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) { try { store.load() } catch (_: Exception) { notice = GatewayRouteException("storage").message } }
    val sources = providers.filterIsInstance<ProviderSetting.OpenAI>().filter {
        it.enabled && it.id != gateway.id && !it.useResponseApi && it.chatCompletionsPath == "/chat/completions"
    }
    val selected = sources.singleOrNull { it.id.toString() == sourceId }
    val pairedHere = runCatching { gatewayManagementBase(gateway.baseUrl).toString() == state.pairedBase }.getOrDefault(false)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("为 ST 添加模型线路") },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("目标 ST：${orbisSettingsLabel(gateway.name, "所选网关")}")
            Text(runCatching { gatewayManagementBase(gateway.baseUrl).toString() }.getOrDefault("地址不支持安全管理，请改用 HTTPS 或 Tailscale 地址"), style = MaterialTheme.typography.bodySmall)
            Text("第一步仅保存线路，第二步回到上一页确认切换。不会改 ST 身份、记忆、服务器默认模型或其他 AI。", style = MaterialTheme.typography.bodySmall)
            if (!state.loaded) Text("读取本机授权中…")
            if (state.loaded && !state.readable) Text("加密记录无法读取，已停止。请联系维护者保留现场，不要清除应用数据。")
            if (state.readable && !pairedHere) {
                Text("首次配对：填入此 ST 单独签发的人类管理凭据。不是聊天密钥；普通使用者只需配对一次。")
                OutlinedTextField(value = adminToken, onValueChange = { if (it.length <= 4096) adminToken = it },
                    label = { Text("人类管理凭据（只保存在本机加密区）") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                TextButton(enabled = !busy && adminToken.isNotBlank() && !state.pending, onClick = { action {
                    stillGateway()
                    routeRequire(adminToken.trim() != gateway.apiKey.trim(), "unauthorized")
                    store.pair(gateway.baseUrl, adminToken); adminToken = ""
                    notice = "已验证独立人类管理授权。未上传任何上游密钥，也未切换模型。"
                } }) { Text("验证并保存本机授权") }
            }
            if (state.pending) {
                Text("上次提交尚待核对，新增入口已暂停。原绑定未变。")
                TextButton(enabled = !busy && pairedHere, onClick = { action {
                    stillGateway()
                    notice = if (store.reconcile(gateway.baseUrl)) "服务器已确认保存；请重新读取目录。没有重发密钥，也没有切换。"
                        else "尚未找到已提交回执，原编号和内容仍保留。可以稍后再核对；如要重试，请明确选择下方同编号重试，不会另建第二条。"
                } }) { Text("核对上次提交（只读）") }
                if (state.canRetrySameRequest) {
                    Text("重试会再次发送你先前明确授权的同一条密钥，沿用原编号、原内容和原版本条件；服务器应幂等处理，仍不自动切换模型。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { action {
                        stillGateway(); store.retrySameRequest(gateway.baseUrl, ::stillGateway)
                        key = ""; confirm = null
                        notice = "原提交已确认保存；未切换模型。"
                    } }) { Text("确认按原编号重试这条授权") }
                }
            }
            if (pairedHere && !state.pending) {
                TextButton(enabled = !busy, onClick = { action { stillGateway(); store.read(gateway.baseUrl) } }) {
                    Text("读取 / 刷新 ST 线路")
                }
                state.directory?.let { directory ->
                    Text("已有 ${directory.routes.size} 条线路（版本 ${directory.revision}）", style = MaterialTheme.typography.labelLarge)
                    directory.routes.forEach { row -> Text("${row.publicModel} · ${if (row.managed) "手机添加" else "服务器保留线路"}", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                    Text("选择要接入 ST 的上游模型")
                    Row { RadioButton(!modeManual, { modeManual = false; confirm = null }, enabled = !busy); Text("使用手机已有服务连接", Modifier.padding(top = 12.dp)) }
                    Row { RadioButton(modeManual, { modeManual = true; confirm = null }, enabled = !busy); Text("手动填写兼容服务", Modifier.padding(top = 12.dp)) }
                    if (!modeManual) {
                        if (sources.isEmpty()) Text("没有可用的兼容连接，请切换到手动填写。")
                        sources.forEach { provider -> Row {
                            RadioButton(sourceId == provider.id.toString(), { sourceId = provider.id.toString(); modelId = ""; confirm = null }, enabled = !busy)
                            Text(orbisSettingsLabel(provider.name, "兼容服务"), Modifier.padding(top = 12.dp))
                        } }
                        selected?.models?.filter { it.type == ModelType.CHAT }?.forEach { model ->
                            TextButton(enabled = !busy, onClick = { modelId = model.modelId; confirm = null }) { Text(orbisSettingsLabel(model.displayName.ifBlank { model.modelId }, "已配置型号")) }
                        }
                        Text("也可以填写此服务支持但尚未添加的精确型号，例如 Pro 的实际模型 ID；不凭名称猜测服务是否支持。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedTextField(upstream, { upstream = it.take(2048); confirm = null }, label = { Text("上游 HTTPS 基础地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        OutlinedTextField(key, { key = it.take(4096); confirm = null }, label = { Text("此上游的单个 API 密钥") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    }
                    OutlinedTextField(modelId, { modelId = it.take(128); confirm = null }, label = { Text("上游精确模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(routeAlias, { routeAlias = it.take(128); confirm = null }, label = { Text("ST 中的新线路名，如 st-pro") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Text("支持 OpenAI Chat Completions 兼容服务。线路保存不测试模型，也不产生试聊费用；新线路默认按文字能力显示，图片、工具、思考需按实际能力核对。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && modelId.isNotBlank() && routeAlias.isNotBlank() && (modeManual || selected != null), onClick = {
                        try {
                            stillGateway()
                            val provider = selected
                            val syntheticModel = provider?.models?.filter { it.modelId == modelId.trim() }?.let {
                                routeRequire(it.size <= 1, "changed"); it.singleOrNull()
                            } ?: Model(modelId = modelId.trim())
                            val source = if (modeManual) gatewayUpstreamBase(upstream) to gatewayUpstreamModel(modelId)
                                else gatewaySource(provider ?: throw GatewayRouteException("invalid"), syntheticModel)
                            val alias = gatewayRouteName(routeAlias)
                            routeRequire(directory.routes.none { it.publicModel == alias }, "immutable_route")
                            val secret = gatewaySingleKey(if (modeManual) key else provider!!.apiKey)
                            confirm = GatewayRouteConsent(directory.revision, alias, source.first, source.second, secret,
                                if (modeManual) null else provider!!.id.toString(),
                                if (modeManual) null else JsonInstant.encodeToString<ProviderSetting>(provider!!))
                            notice = null
                        } catch (error: Exception) { notice = (error as? GatewayRouteException)?.message ?: "请核对所选单条线路。" }
                    }) { Text("核对单条线路授权") }
                }
            }
            state.savedAlias?.let { Text("已保存线路：$it。请返回上一页，重新读取目录并另行确认切换；当前模型还没有改变。") }
            confirm?.let { choice ->
                HorizontalDivider()
                Text("请确认授权：将这一个服务的密钥发送并保存在「${orbisSettingsLabel(gateway.name, "此 ST")}」。不会上传其他服务的密钥。")
                Text("上游：${choice.upstream}\n型号：${choice.model}\n新线路：${choice.alias}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy && !state.pending, onClick = { action {
                    fun validateSource() {
                        stillGateway()
                        choice.providerId?.let { id ->
                            val fresh = latestProviders().singleOrNull { it.id.toString() == id }
                            routeRequire(fresh != null && JsonInstant.encodeToString<ProviderSetting>(fresh) == choice.providerRevision, "changed")
                        }
                    }
                    validateSource()
                    store.create(gateway.baseUrl, choice.revision, choice.alias, choice.upstream, choice.model, choice.key, ::validateSource)
                    key = ""; confirm = null
                    notice = "线路已保存；未测试、未切换。请返回上一页选择新线路后单独确认绑定。"
                } }) { Text("同意仅发送这一条密钥并保存线路") }
                TextButton(enabled = !busy, onClick = { confirm = null }) { Text("取消这次授权") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (busy) Text("处理中，请稍候…")
        } }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("返回模型切换") } })
}

/** Never a data class: toString cannot disclose an upstream key or provider serialized revision. */
private class GatewayRouteConsent(val revision: Long, val alias: String, val upstream: String, val model: String,
    val key: String, val providerId: String?, val providerRevision: String?)
