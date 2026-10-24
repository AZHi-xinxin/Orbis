package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.integration.*
import org.koin.compose.koinInject

@Composable
internal fun OrbisConnectionProposals(owner: String, onClose: () -> Unit, store: SettingsStore = koinInject(),
    initialRequest: ConnectionProposal? = null) {
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selected by remember(owner) { mutableStateOf<ConnectionProposal?>(null) }
    var credential by remember(owner) { mutableStateOf("") } // never saved in UI state/clipboard/chat
    var rememberChoice by remember { mutableStateOf(true) }
    LaunchedEffect(owner, initialRequest) {
        val target = initialRequest?.takeIf { it.owner == owner }?.let { requested ->
            settings.connectionProposals.singleOrNull { it.owner == owner && it.id == requested.id && it.draft == requested.draft }
        }
        if (target?.status == "pending") {
            selected = target; credential = ""; rememberChoice = target.alwaysAllow
        }
    }
    fun apply(transform: (me.rerere.rikkahub.data.datastore.Settings) -> me.rerere.rikkahub.data.datastore.Settings) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { store.update(transform) }
                if (ok) { credential = ""; selected = null; notice = "设置已保存。AI 可以继续选择工具型号、音色并检查连接。未自动发起付费试听。" }
                else notice = "本次没有保存，原配置保留。聊天不受影响。"
            } catch(c: CancellationException) { throw c }
            catch(_: Exception) { notice = "未保存：请确认密钥已填写、建议仍是当前版本，且原生配置未被另外修改。原配置保留，聊天继续。" }
            finally { busy = false }
        }
    }
    CompanionWindow(onClose) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("返回") }
            Text("AI 连接建议", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("connection-proposals"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("AI 填配置，你只补必要的密钥", style = MaterialTheme.typography.titleLarge)
            Text("模型、语音和 MCP 均可由 AI 填好。不需要密钥的服务会直接保存；MCP 只需在这里开启。密钥只进入原生设置，不回传给 AI。", style = MaterialTheme.typography.bodyMedium)
            val entries = settings.connectionProposals.filter { it.owner == owner }.asReversed()
                .sortedBy { if (it.id == initialRequest?.id) 0 else 1 }
            if (entries.isEmpty()) Text("还没有连接建议。可以在聊天中请 AI 填好语音或 MCP 的连接配置，再到这里确认。")
            entries.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.draft.name, style = MaterialTheme.typography.titleMedium)
                    Text(when(p.draft.kind) {
                        "tts_openai" -> "OpenAI 兼容语音服务"
                        "model_openai" -> "OpenAI 兼容模型 / ST 聊天 API"
                        "mcp_sse" -> "MCP · SSE"
                        else -> "MCP · Streamable HTTP"
                    }, style = MaterialTheme.typography.labelLarge)
                    Text(p.draft.endpoint, style = MaterialTheme.typography.bodySmall)
                    if(p.draft.kind == "tts_openai") Text("模型 ${p.draft.model} · 音色 ${p.draft.voice}", style = MaterialTheme.typography.bodySmall)
                    if(p.draft.kind == "model_openai") Text("模型 ${p.draft.model}", style = MaterialTheme.typography.bodySmall)
                    Text(when(p.status) { "saved" -> "已保存 · 未在此页验证连接"; "rejected" -> "已拒绝 · 聊天继续"; "changed_in_settings" -> "原生配置已变更，未自动覆盖"; else -> "待确认 · 不阻塞聊天" })
                    if(p.alwaysAllow) Text("已允许此助手以后调整同地址、同用途的配置", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.draft.kind.startsWith("mcp_") && p.status == "saved" &&
                            settings.mcpServers.any { it.id.toString() == p.id && !it.commonOptions.enable }) {
                            Button(enabled = !busy, onClick = { apply { latest ->
                                ConnectionProposalRules.enableMcp(latest, owner, p.id)
                            } }) { Text("开启这个 MCP") }
                        }
                        OutlinedButton(enabled = !busy && !settings.init && p.status != "changed_in_settings", onClick = {
                            selected = p; credential = ""; rememberChoice = p.alwaysAllow || p.status == "pending"
                        }) { Text(if(p.status == "saved") "查看授权 / 更新密钥" else "查看并填写") }
                        TextButton(enabled = !busy, onClick = { apply { latest ->
                            val current = latest.connectionProposals.single { it.id == p.id }
                            ConnectionProposalRules.replace(latest, current.copy(alwaysAllow = false, status = if(current.installed == null) "rejected" else current.status))
                        } }) { Text(if(p.installed == null) "拒绝" else "取消以后允许") }
                    }
                    if(p.status == "changed_in_settings") Text("原生设置中的修改优先。如需重建，请在原生设置中处理这条连接；这里不会覆盖你的改动。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        selected?.let { p ->
            AlertDialog(onDismissRequest = { if(!busy) { selected = null; credential = "" } }, title = { Text("确认连接范围") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p.draft.name)
                    Text(p.draft.endpoint)
                    Text(when (p.draft.kind) {
                        "tts_openai" -> "保存语音服务配置；AI 可继续选择为朗读音色，或发起试听检查。"
                        "model_openai" -> "保存模型服务配置；AI 可继续选择为工具模型或自己的聊天模型。"
                        else -> "允许此助手连接这个 MCP 服务并使用其工具。"
                    })
                    if(p.draft.endpoint.startsWith("http:")) Text("这是未加密的 HTTP 地址。只建议用于你信任的本机、局域网或私有网络。")
                    if(p.draft.auth == "bearer") OutlinedTextField(credential, { credential = it.take(8192) },
                        label = { Text(if(p.installed == null) "API Key / 令牌" else "新密钥（留空保留原值）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        supportingText = { Text("只存原生设置，不回传给 AI。不需要另设恢复密码。") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberChoice, { rememberChoice = it }); Text("以后允许此助手调整这个地址、用途和授权方式", Modifier.weight(1f))
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(enabled = !busy && (p.draft.auth == "none" || p.installed != null || credential.isNotBlank()), onClick = {
                val key = credential.takeIf(String::isNotBlank); val remembered = rememberChoice
                apply { latest ->
                    check(latest.connectionProposals.single { it.id == p.id }.draft == p.draft)
                    ConnectionProposalRules.approve(latest, p.id, key, remembered)
                }
            }) { Text(if(busy) "保存中…" else if(rememberChoice) "保存并以后允许" else "仅保存这一次") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { selected = null; credential = "" }) { Text("稍后填写") } })
        }
    }
}
