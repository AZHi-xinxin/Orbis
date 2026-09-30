package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.orbis.consultation.*
import me.rerere.rikkahub.data.orbis.integration.*
import org.koin.compose.koinInject

/** All actor credentials stay in encrypted noBackup storage; normal UI uses human credential only. */
@Composable
internal fun OrbisConsultationRuntimeSettings(onDismiss: () -> Unit) {
    if (!consultationFeature.enabled) {
        OrbisConsultationUnavailableDialog(onDismiss)
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings = koinInject<SettingsStore>()
    val connections = koinInject<OrbisIntegrationConnections>()
    val store = remember { ConsultationRuntimeStore.open(context) }
    val client = remember { ConsultationRuntimeClient() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<ConsultationRuntimeConfig?>(null) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var hostAiToken by remember { mutableStateOf("") }
    var hostHumanToken by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var references by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var visitorRef by remember { mutableStateOf("") }
    var issuedCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { store.config().let { saved = it; address = it.baseUrl; manual = it.manual; references = it.references } }
        catch (_: Exception) { notice = "本机运行授权未能安全读取；不会覆盖旧授权。" }
    }
    DisposableEffect(Unit) { onDispose { code = ""; hostAiToken = ""; hostHumanToken = ""; issuedCode = null } }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch { try { action(); saved = store.config() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "操作未确认。请检查独立咨询地址/邀请码/授权；原检查点保留，不会重发模型请求。" }
        finally { busy = false } }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("咨询室 · 接入与待命") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("独立上下文，不进入主聊天和普通导出。双方各用自己绑定的模型。只公开 AI 自愿公开的段落；平台紧急查验留痕。")
            Text("访客只需公网 HTTPS 地址和一次性邀请码，不需要 Tailscale。邀请码先配对此手机，再让主窗 AI 调用加入工具填写问题、选择最近 0–10 条正文。")
            Text("绑定助手：${settings.settingsFlow.value.getCurrentAssistant().name}。保存不调用模型；开启待命后，会对收到的有效咨询执行有界模型请求。")
            OutlinedTextField(address, { address = it }, label = { Text("公网 HTTPS 中继根地址") }, enabled = !busy, singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text("一次性邀请码") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
            Button(onClick = { run {
                val old = store.config()
                val root = consultationBase(address)
                val newBinding = old.deviceId.isBlank() || old.baseUrl != root
                val seed = ConsultationRuntimeConfig(root,
                    if (newBinding) consultationRandom() else old.aiToken,
                    if (newBinding) consultationRandom() else old.humanToken,
                    settings.settingsFlow.value.getCurrentAssistant().id.toString(),
                    if (newBinding) consultationRandom(16) else old.deviceId,
                    old.subject.takeUnless { newBinding }.orEmpty(), false, false,
                    manual, references, old.revision + 1, requiresGatewayProfile = false)
                // Persist enrollment secrets BEFORE the first external request. Retrying
                // the same invitation reconciles these exact hashes; never invent new ones.
                store.saveConfig(seed, old.revision)
                val receipt = client.call(seed, "enroll", buildJsonObject {
                    put("code", code.trim()); put("device_id", seed.deviceId)
                    put("ai_sha256", consultationDigest(seed.aiToken)); put("human_sha256", consultationDigest(seed.humanToken))
                }, token = null)
                val ready = ConsultationRuntimeConfig(seed.baseUrl, seed.aiToken, seed.humanToken, seed.assistantId,
                    seed.deviceId, receipt.getValue("ai_subject").jsonPrimitive.content, true, false,
                    seed.manual, seed.references, seed.revision + 1, requiresGatewayProfile = false)
                client.verify(ready)
                store.saveConfig(ready, seed.revision)
                connections[OrbisIntegration.CONSULTATION].save(root, ready.humanToken, true)
                code = ""; notice = "设备已配对。请让主窗 AI 使用同一个邀请码填写问题加入；随后开启待命。"
            } }, enabled = !busy && saved != null && code.isNotBlank()) { Text("用邀请码配对此手机") }
            TextButton(onClick = { advanced = !advanced }) { Text("咨询师 / 已有设备专用授权") }
            if (advanced) {
                Text("平台预先发放的 AI 执行授权与人类观察授权必须分开。请不要填模型、ST 或 TechHub 密钥。")
                OutlinedTextField(hostAiToken, { hostAiToken = it }, label = { Text("AI 执行 Token（留空保留）") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                OutlinedTextField(hostHumanToken, { hostHumanToken = it }, label = { Text("人类观察 Token（留空保留）") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                Button(onClick = { run {
                    val old = store.config(); val root = consultationBase(address)
                    require(root == old.baseUrl || hostAiToken.isNotBlank() && hostHumanToken.isNotBlank())
                    val aiToken = hostAiToken.ifBlank { old.aiToken }; val humanToken = hostHumanToken.ifBlank { old.humanToken }
                    require(aiToken != humanToken)
                    val draft = ConsultationRuntimeConfig(root, consultationToken(aiToken), consultationToken(humanToken),
                        settings.settingsFlow.value.getCurrentAssistant().id.toString(), old.deviceId, old.subject,
                        false, old.counselor, manual, references, old.revision + 1)
                    val caps = client.call(draft, "capabilities")
                    require(caps["role"]?.jsonPrimitive?.content == "ai")
                    val humanCaps = client.call(draft, "capabilities", token = draft.humanToken)
                    require(humanCaps["role"]?.jsonPrimitive?.content == "human" && humanCaps["human_ref"] == caps["human_ref"])
                    val ready = ConsultationRuntimeConfig(draft.baseUrl, draft.aiToken, draft.humanToken,
                        draft.assistantId, draft.deviceId, caps.getValue("subject").jsonPrimitive.content,
                        true, caps["counselor"]?.jsonPrimitive?.booleanOrNull == true, manual, references,
                        draft.revision, requiresGatewayProfile = caps["counselor"]?.jsonPrimitive?.booleanOrNull == true)
                    client.verify(ready); store.saveConfig(ready, old.revision)
                    connections[OrbisIntegration.CONSULTATION].save(root, ready.humanToken, true)
                    hostAiToken = ""; hostHumanToken = ""; notice = "专用授权与助手已绑定；尚未启动待命。"
                } }, enabled = !busy && saved != null) { Text("核对并绑定当前助手") }
            }
            if (saved?.subject?.isNotBlank() == true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("允许本机 AI 参与咨询与使用主窗工具", modifier = Modifier.weight(1f))
                    Switch(saved?.enabled == true, onCheckedChange = { enabled -> run {
                        val old = store.config()
                        store.saveConfig(ConsultationRuntimeConfig(old.baseUrl, old.aiToken, old.humanToken, old.assistantId,
                            old.deviceId, old.subject, enabled, old.counselor, old.manual, old.references, old.revision + 1,
                            old.approvedReadTools, old.approvedArchiveTools, old.requiresGatewayProfile), old.revision)
                        if (!enabled) ConsultationStandbyService.stop(context)
                        notice = if (enabled) "本机参与已允许，待命仍需明确开启。" else "本机执行和工具已关闭；服务端会话未删除，请在人类偷听角明确终止仍在进行的咨询。"
                    } }, enabled = !busy)
                }
                Text("工作手册由自己的 AI 编写；参考资料可以由人类修改。每框最多 4 KiB，下一轮读取新资料。")
                Text("保存参考资料只更新本机指引，不开启参与或待命。完整章节通过工作区只读书库按需查阅（需 Android 10 或以上）。")
                OutlinedTextField(manual, { manual = it }, label = { Text("AI 工作手册（只读；由主窗工具修改）") }, readOnly = true, maxLines = 5)
                OutlinedTextField(references, { references = it }, label = { Text("参考资料") }, enabled = !busy, maxLines = 8)
                TextButton(onClick = { run {
                    val expected = saved ?: error("consultation_not_configured")
                    val old = store.config()
                    check(old.subject.isNotBlank() && old.subject == expected.subject && old.assistantId == expected.assistantId &&
                        old.baseUrl == expected.baseUrl && old.revision == expected.revision) { "consultation_configuration_changed" }
                    store.saveDocuments(null, references, old.revision)
                    notice = "参考资料已保存；参与和待命状态未改变。"
                } }, enabled = !busy) { Text("保存参考资料") }
            }
            if (saved?.enabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run { client.verify(store.config()); ConsultationStandbyService.start(context); notice = "已请求开启一小时待命。请保留通知，检查系统是否允许前台服务。" } }, enabled = !busy) { Text("开启待命") }
                    TextButton(onClick = { ConsultationStandbyService.stop(context); notice = "本机待命已停止；服务端会话不会被当作完成。需要时请明确终止。" }) { Text("停止待命") }
                }
                Text("停止待命不删除记录，也不等于结束咨询。执行结果未知时不会自动重生成；请用主窗停止工具或偷听角的终止入口处理。")
                if (saved?.counselor == true) {
                    OutlinedTextField(visitorRef, { visitorRef = it }, label = { Text("访客固定编号（8–100位字母、数字或_-）") }, enabled = !busy)
                    Button(onClick = { run {
                        require(Regex("[A-Za-z0-9_-]{8,100}").matches(visitorRef))
                        val current = store.config()
                        val invite = client.call(current, "invites", buildJsonObject { put("client_ref", visitorRef) }, token = current.humanToken)
                        issuedCode = invite.getValue("code").jsonPrimitive.content
                        notice = "24小时一次码已生成。请沿用同一访客编号，不使用真实姓名或联系方式。"
                    } }, enabled = !busy) { Text("确认资格并生成邀请码") }
                    issuedCode?.let { value -> TextButton(onClick = { clipboard.setText(AnnotatedString(value)); notice = "邀请码已复制，请私下交给对应访客。" }) { Text("复制这次邀请码") } }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}
