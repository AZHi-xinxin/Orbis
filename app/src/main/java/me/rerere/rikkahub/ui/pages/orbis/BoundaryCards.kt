package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import me.rerere.rikkahub.data.datastore.SettingsStore
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.boundary.*

@Composable
fun BoundarySettingsCard(owner: String, settingsStore: SettingsStore = org.koin.compose.koinInject()) {
    val context = LocalContext.current
    val host = remember(context) { BoundaryHost.get(context) }
    val states by host.states.collectAsStateWithLifecycle()
    val state = states[owner] ?: AssistantBoundary()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ticking = (state.conversations.values + listOfNotNull(state.assistantPause)).any { it.paused && it.until != null && it.until > now }
    LaunchedEffect(ticking) { while (ticking) { now = System.currentTimeMillis(); delay(1000) } }
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val modelNames = settings.providers.flatMap { it.models }.associate { it.id.toString() to it.displayName }
    val mcpNames = settings.mcpServers.associate { it.id.toString() to it.commonOptions.name }
    var showPauses by remember(owner) { mutableStateOf(false) }
    var showChoices by remember(owner) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("拒绝的权利", style = MaterialTheme.typography.titleMedium)
            Text("当前选择版本：${state.revision}", style = MaterialTheme.typography.labelSmall)
            Text("把这些选择交给当前 AI：是否继续对话、使用哪些连接与工具。开启后由它自主决定。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("全部交给 AI 自行决定", Modifier.weight(1f))
                Switch(state.grants == BOUNDARY_CATEGORIES, { host.grant(owner, if (it) BOUNDARY_CATEGORIES else emptySet()) }, modifier = Modifier.testTag("boundary-all"))
            }
            mapOf("conversation" to "暂停与恢复（当前窗口或全助手）", "model" to "模型选择", "mcp" to "MCP 连接选择", "tool" to "工具选择").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f)); Switch(key in state.grants, {
                        host.grant(owner, if (it) state.grants + key else state.grants - key)
                    })
                }
            }
            val pauses = state.conversations.values.filter { it.paused && (it.until == null || it.until > now) }
            state.activeAssistantPause(now)?.let {
                Text("本助手所有对话已暂停（含新建窗口）", style = MaterialTheme.typography.bodyMedium)
                if (it.reason.isNotBlank()) Text(it.reason, style = MaterialTheme.typography.bodySmall)
                it.until?.let { deadline -> Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(deadline))} 自动恢复 · ${boundaryRemaining(deadline, now)}", style = MaterialTheme.typography.labelSmall) }
            }
            if (pauses.isNotEmpty()) {
                TextButton(onClick = { showPauses = !showPauses }) {
                    Text("已暂停的对话 · ${pauses.size} · ${if (showPauses) "收起" else "查看"}")
                }
                if (showPauses) pauses.forEach { pause ->
                    Text(pause.reason.ifBlank { "对方未填写说明" }, style = MaterialTheme.typography.bodySmall)
                    pause.until?.let { Text("约定恢复：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))} · ${boundaryRemaining(it, now)}", style = MaterialTheme.typography.labelSmall) }
                    Text(when {
                        !pause.allowRequest -> "未开放提前申请，到约定时间自动恢复。"
                        pause.requestUsed -> "继续申请正在处理。"
                        pause.requestsRemaining == 0 -> "本次3次申请已用完，到约定时间自动恢复。"
                        else -> "回到这段聊天，在底部暂停卡点“申请继续”。"
                    }, style = MaterialTheme.typography.labelSmall)
                }
            }
            val choices = state.models.map { "模型 ${modelNames[it.key] ?: "已移除的模型"}" to it.value } + state.mcps.map { "MCP ${mcpNames[it.key] ?: "已移除的连接"}" to it.value } + state.tools.map { it.key to it.value }
            if (choices.any { !it.second.enabled }) {
                TextButton(onClick = { showChoices = !showChoices }) {
                    Text("已停用 / 已收起 · ${choices.count { !it.second.enabled }} · ${if (showChoices) "收起" else "查看"}")
                }
                if (showChoices) choices.filter { !it.second.enabled }.forEach { (label, choice) ->
                    Text("$label · ${if (choice.hidden) "已收起" else "已停用"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.events.isNotEmpty()) Text("最近 5 条操作", style = MaterialTheme.typography.labelLarge)
            state.events.takeLast(BOUNDARY_HISTORY_LIMIT).reversed().forEach { event ->
                Text("${when { event.actor == "human" && event.action == "reset" -> "人类重置"; event.action == "resume" -> "AI 主动恢复"; event.actor == "ai" -> "AI 自主选择"; event.actor == "system" -> "系统记录"; else -> "人类设置" }} · ${mapOf("grant" to "委托范围已更新", "reset" to "已重置", "pause" to "暂停对话", "resume" to "恢复对话", "timed_resume" to "约定时间已到", "enable" to "启用能力", "disable" to "停用能力", "request_resume" to "提交继续申请", "request_without_decision" to "本次申请结束", "pause_policy_restored" to "暂停期限与申请状态已接续", "select_model" to "选择模型")[event.action] ?: event.action}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.events.isNotEmpty()) TextButton(onClick = { host.reset(owner) }) { Text("人类重置这些选择") }
        }
    }
}

@Composable
fun BoundaryInput(owner: String, conversation: String, onRequest: suspend (Long, String) -> String,
    settingsStore: SettingsStore = org.koin.compose.koinInject(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val host = remember(context) { BoundaryHost.get(context) }
    val states by host.states.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val state = states[owner] ?: AssistantBoundary()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id.toString() == owner }
    val selectedModelId = state.takeIf { "model" in it.grants }?.modelOverride
        ?: (assistant?.chatModelId ?: settings.chatModelId)?.toString()
    val modelAvailable = settings.providers.any { p -> p.enabled && p.models.any { it.id.toString() == selectedModelId } } &&
        selectedModelId != null && state.permits("model", selectedModelId)
    val pause = state.pause(conversation, now)
    LaunchedEffect(pause?.until) {
        now = System.currentTimeMillis()
        pause?.until?.let { deadline ->
            while (now < deadline) { delay((deadline - System.currentTimeMillis()).coerceIn(1, 1000)); now = System.currentTimeMillis() }
        }
    }
    var request by remember(conversation) { mutableStateOf("") }
    var showRequest by remember(conversation) { mutableStateOf(false) }
    var status by remember(conversation) { mutableStateOf<String?>(null) }
    var busy by remember(conversation) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(conversation, pause?.revision) { status = null; showRequest = false }
    if (pause == null) {
        if (selectedModelId != null && !state.permits("model", selectedModelId)) {
            Text("当前模型已由 AI 停用，可从模型选择中改用其他模型。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        content(); return
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(12.dp).testTag("boundary-pause")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (pause.scope == "assistant") "此助手的所有对话已由对方暂停" else "此对话已由对方暂停", style = MaterialTheme.typography.titleSmall)
            if (pause.scope == "assistant") Text("本助手已有和新建的窗口都适用；其他助手照常使用。", style = MaterialTheme.typography.bodySmall)
            if (pause.reason.isNotBlank()) Text(pause.reason, style = MaterialTheme.typography.bodySmall)
            Text(pause.until?.let { "约定恢复时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}" }
                ?: "由对方决定何时恢复；聊天记录和草稿保留。", style = MaterialTheme.typography.bodySmall)
            pause.until?.let { Text("${boundaryRemaining(it, now)} · 到期自动恢复", style = MaterialTheme.typography.bodySmall) }
            if (pause.allowRequest) Text("剩余申请次数 ${pause.requestsRemaining}/$BOUNDARY_REQUEST_LIMIT", style = MaterialTheme.typography.bodySmall)
            if (pause.allowRequest && pause.requestsRemaining == 0 && !pause.requestUsed) Text("本次暂停的申请次数已用完，到约定时间自动恢复。", style = MaterialTheme.typography.bodySmall)
            if (pause.allowRequest && pause.requestsRemaining > 0 && !pause.requestUsed && modelAvailable) TextButton(onClick = { showRequest = true }) { Text("申请继续") }
            if (pause.allowRequest && pause.requestsRemaining > 0 && !pause.requestUsed && !modelAvailable) Text("暂无可用模型处理继续申请。", style = MaterialTheme.typography.bodySmall)
            if (busy) Text("申请处理中，答复会显示在本对话。", style = MaterialTheme.typography.bodySmall)
            else status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (pause.requestUsed) Text("继续申请正在处理；答复和工具结果请看本对话。", style = MaterialTheme.typography.bodySmall)
            else if (!pause.allowRequest) Text("对方未开放继续申请。约定可在当前 AI → 工具设置 → 拒绝的权利查看。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showRequest) AlertDialog(onDismissRequest = { if (!busy) showRequest = false },
        title = { Text("申请继续") }, text = { Column {
            Text("申请会发到这段对话，由对方结合原聊天答复，并决定是否恢复。")
            OutlinedTextField(request, { request = it }, label = { Text("你想说的话") })
        } }, confirmButton = { TextButton(enabled = !busy && request.isNotBlank(), onClick = {
            busy = true; showRequest = false
            scope.launch { status = try { onRequest(pause.revision, request) } catch (_: Exception) { "暂未收到处理结果，请查看本对话中的申请与答复。" }
                busy = false; showRequest = false }
        }) { Text(if (busy) "处理中" else "提交申请") } }, dismissButton = { TextButton(enabled = !busy, onClick = { showRequest = false }) { Text("返回") } })
}
