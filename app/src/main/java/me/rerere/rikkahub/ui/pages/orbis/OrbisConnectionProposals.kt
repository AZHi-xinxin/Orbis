package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.integration.*
import org.koin.compose.koinInject

@Composable
internal fun OrbisConnectionProposals(owner: String, onClose: () -> Unit, store: SettingsStore = koinInject(),
    initialRequest: ConnectionProposal? = null, initialGuide: Boolean = false) {
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showGuide by remember(owner) { mutableStateOf(initialGuide) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedId by remember(owner) { mutableStateOf(initialRequest?.id) }
    val entries = settings.connectionProposals.filter { it.owner == owner }.asReversed()
    val selected = entries.firstOrNull { it.id == selectedId } ?: entries.firstOrNull { it.status == "pending" } ?: entries.firstOrNull()
    val dismissals by connectionSetupQueue.dismissals.collectAsStateWithLifecycle()
    var seenDismissal by remember(owner) { mutableLongStateOf(connectionSetupQueue.dismissals.value[owner]?.sequence ?: 0L) }
    LaunchedEffect(dismissals[owner]) {
        dismissals[owner]?.let { event ->
            if (event.sequence > seenDismissal) {
                seenDismissal = event.sequence
                if (event.id == null || event.id == selected?.id) onClose()
            }
        }
    }
    fun apply(afterSave: () -> Unit = {}, transform: (Settings) -> Settings) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { store.update(transform) }
                notice = if (ok) "已保存。连接状态可由 AI 继续检查；聊天随时可以继续。" else "这次没有保存，原配置保留。"
                if (ok) afterSave()
            } catch(c: CancellationException) { throw c }
            catch(_: Exception) { notice = "未保存：请检查密钥或建议版本。原有设置和聊天仍保留。" }
            finally { busy = false }
        }
    }
    if (showGuide) {
        val credentialFile = selected?.draft?.credentialFile?.takeIf(String::isNotBlank)
        ConnectionCredentialGuide(owner, entries.filter { credentialFile != null && it.draft.credentialFile == credentialFile && it.status != "rejected" },
            onClose = onClose, onContinue = { showGuide = false })
        return
    }
    ConnectionProposalSheet(entries, selected, busy || settings.init, notice, onClose,
        onSelect = { selectedId = it.id; notice = null },
        enabled = selected?.let { p -> settings.mcpServers.any { it.id.toString() == p.id && it.commonOptions.enable } } == true,
        onSave = { p, key, allow, enable -> apply { latest ->
            check(latest.connectionProposals.single { it.owner == owner && it.id == p.id }.draft == p.draft)
            val next = ConnectionProposalRules.approve(latest, p.id, key, allow, enableMcp = enable)
            if (p.draft.kind.startsWith("mcp_")) ConnectionProposalRules.setMcpEnabled(next, owner, p.id, enable) else next
        } },
        onToggle = { p, enabled -> apply { ConnectionProposalRules.setMcpEnabled(it, owner, p.id, enabled) } },
        onGuide = { showGuide = true },
        onDismissSetup = { apply(afterSave = { connectionSetupQueue.closeSetup(owner); onClose() }) {
            ConnectionProposalRules.dismissSetup(it, owner)
        } },
        onRevoke = { p -> apply { latest ->
            val current = latest.connectionProposals.single { it.owner == owner && it.id == p.id }
            ConnectionProposalRules.replace(latest, current.copy(alwaysAllow = false,
                status = if (current.installed == null) "rejected" else current.status))
        } })
}

/** A compact, chat-owned sheet. Credentials never enter saved state, clipboard, or AI output. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionProposalSheet(entries: List<ConnectionProposal>, selected: ConnectionProposal?,
    busy: Boolean, notice: String?, onClose: () -> Unit, onSelect: (ConnectionProposal) -> Unit,
    enabled: Boolean, onSave: (ConnectionProposal, String?, Boolean, Boolean) -> Unit,
    onToggle: (ConnectionProposal, Boolean) -> Unit, onRevoke: (ConnectionProposal) -> Unit,
    onGuide: () -> Unit = {}, onDismissSetup: () -> Unit = {}) {
    val colors = OrbisTheme.colors
    val height = LocalConfiguration.current.screenHeightDp.dp * .74f
    var key by remember(selected?.id, selected?.draft, selected?.status, notice) { mutableStateOf("") }
    var showKey by remember(selected?.id) { mutableStateOf(false) }
    var advanced by remember(selected?.id) { mutableStateOf(false) }
    var rememberChoice by remember(selected?.id, selected?.alwaysAllow) { mutableStateOf(selected?.alwaysAllow ?: true) }
    var enableChoice by remember(selected?.id, enabled) { mutableStateOf(if(selected?.installed == null) true else enabled) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet,
        containerColor = colors.page, contentColor = colors.ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).imePadding().padding(horizontal = 20.dp)
            .testTag("connection-proposals")) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("完成连接", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("AI 已填好配置，只需最后一步。", color = colors.mutedInk, modifier = Modifier.padding(top = 5.dp))
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("connection-minimize")) { Text("收起") }
                IconButton(onClick = onClose, modifier = Modifier.testTag("connection-close")) { Text("×", style = MaterialTheme.typography.headlineMedium) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (entries.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { p -> FilterChip(p.id == selected?.id, onClick = { onSelect(p) }, label = { Text(p.draft.name, maxLines = 1) }) }
                }
                if (selected == null) Text("还没有连接建议。回到聊天，让 AI 帮你填好模型、语音或 MCP 的配置。", Modifier.padding(16.dp))
                TextButton(onClick = onGuide) { Text("密钥在哪里？查看保存指引") }
                selected?.let { p ->
                    val mcp = p.draft.kind.startsWith("mcp_")
                    val editable = p.status != "changed_in_settings"
                    Surface(shape = RoundedCornerShape(22.dp), color = colors.panel, border = BorderStroke(1.dp, colors.border.copy(alpha = .55f))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(48.dp).background(colors.accent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(if(mcp) "✦" else "◉", color = colors.accent, style = MaterialTheme.typography.headlineSmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(p.draft.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(when { !editable -> "原配置已在别处修改"; p.status == "pending" -> "● 等待完成设置"
                                        mcp && !enabled -> "● 已保存 · 尚未开启"; else -> "● 配置已保存" }, color = colors.mutedInk, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            HorizontalDivider(color = colors.border.copy(alpha = .45f))
                            if (p.draft.auth == "bearer") {
                                OutlinedTextField(key, { key = it.take(8192) }, label = { Text("API Key") }, singleLine = true,
                                    placeholder = { Text(if(p.installed == null) "填写访问密钥" else "留空保留原密钥") },
                                    visualTransformation = if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if(showKey) "隐藏" else "显示") } },
                                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("connection-key"), enabled = editable && !busy)
                            } else Text("这个服务不需要密钥。", color = colors.mutedInk)
                            Button(enabled = editable && !busy && (p.draft.auth == "none" || p.installed != null || key.isNotBlank()),
                                onClick = { onSave(p, key.takeIf(String::isNotBlank), rememberChoice, if(mcp) enableChoice else false) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
                                Text(if(busy) "正在保存…" else if(mcp && enableChoice) "保存并开启连接" else "保存设置")
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = colors.panel, border = BorderStroke(1.dp, colors.border.copy(alpha = .5f))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                                Text("⚙  高级设置", Modifier.weight(1f), color = colors.ink); Text(if(advanced) "⌃" else "⌄")
                            }
                            if(advanced) {
                                OutlinedTextField(p.draft.endpoint, {}, readOnly = true, label = { Text("服务地址（URL）") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                                if(p.draft.model.isNotBlank()) Text("模型  ${p.draft.model}", style = MaterialTheme.typography.bodySmall)
                                if(p.draft.kind == "tts_openai") Text("音色  ${p.draft.voice}", style = MaterialTheme.typography.bodySmall)
                                if(mcp) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("启用连接"); Text("仅连接当前服务", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall) }
                                    Switch(enableChoice, onCheckedChange = { enableChoice = it; if(p.status == "saved") onToggle(p, it) }, enabled = !busy && editable)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("允许 AI 以后调整同地址、同用途的配置", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Switch(rememberChoice, { rememberChoice = it }, enabled = !busy && editable)
                                }
                                if(p.alwaysAllow) TextButton(enabled = !busy, onClick = { onRevoke(p) }) { Text("收回以后的调整授权") }
                            }
                        }
                    }
                }
                notice?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connection-notice")) }
            }
            TextButton(onClick = onDismissSetup, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("connection-dismiss-setup")) {
                Text("关闭本助手的连接提示")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("收起可继续填写；关闭提示后，可从工具娱乐 → AI 连接建议重开。连接会保留。", Modifier.weight(1f), color = colors.mutedInk, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(12.dp)) { Text("回到聊天") }
            }
        }
    }
}
