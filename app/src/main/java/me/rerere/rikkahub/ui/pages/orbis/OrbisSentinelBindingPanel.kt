package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.ORBIS_EVENT_SOURCES
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisInboxState
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.orbisEventReceivedTime
import me.rerere.rikkahub.ui.components.message.orbisEventSourceLabel
import org.koin.compose.koinInject

/** A single host binding entry. Opening it never enables senders, sends a test or selects latest. */
@Composable
internal fun OrbisSentinelBindingPanel() {
    val settingsStore = koinInject<SettingsStore>()
    val repository = koinInject<ConversationRepository>()
    val chatService = koinInject<ChatService>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val inbox by chatService.orbisEvents.inbox.state.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    var conversations by remember(assistant?.id) { mutableStateOf(emptyList<Conversation>()) }
    var loaded by remember(assistant?.id) { mutableStateOf(false) }
    var loadingError by remember(assistant?.id) { mutableStateOf<String?>(null) }
    var selectedSource by remember(assistant?.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember(assistant?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(assistant?.id, settings.init) {
        val identity = assistant?.id ?: return@LaunchedEffect
        if (settings.init) return@LaunchedEffect
        try {
            repository.getConversationsOfAssistant(identity).flowOn(Dispatchers.IO).collect {
                conversations = it
                loaded = true
                loadingError = null
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            loaded = false
            loadingError = "会话列表读取失败；没有改变任何绑定，请重新打开此页。"
        }
    }

    fun saveBinding(source: String, binding: OrbisEventBinding, message: String) {
        if (saving) return
        saving = true
        notice = null
        scope.launch {
            try {
                // This is an explicit local settings action, not a test delivery.
                withContext(NonCancellable + Dispatchers.IO) {
                    chatService.bindOrbisEvents(setOf(source), binding)
                }
                notice = message
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                notice = "绑定未确认保存成功。请核对下方实际状态；没有发送测试或自动重投事件。"
            } finally { saving = false }
        }
    }

    OrbisSentinelBindingCard(
        assistantId = assistant?.id?.toString(),
        assistantName = assistant?.name?.ifBlank { "未命名 AI" } ?: "未选择 AI",
        assistantNames = settings.assistants.associate { it.id.toString() to it.name.ifBlank { "未命名 AI" } },
        conversations = conversations,
        inbox = inbox,
        canEdit = !settings.init && assistant != null && loaded && !saving,
        loading = assistant != null && !loaded && loadingError == null,
        notice = loadingError ?: notice,
        onChooseTarget = { selectedSource = it },
        onToggle = { source, enabled ->
            val identity = assistant?.id?.toString()
            val bound = inbox.bindings[source]
            if (bound != null && bound.assistantId == identity &&
                conversations.any { it.id.toString() == bound.conversationId }) {
                saveBinding(source, bound.copy(enabled = enabled),
                    if (enabled) "此来源已允许送达固定会话；发送端仍需另行接通。" else "已停用此来源接收；历史事件保留。")
            }
        },
    )

    val choosing = selectedSource
    if (choosing != null && assistant != null) {
        AlertDialog(onDismissRequest = { selectedSource = null },
            title = { Text("${orbisEventSourceLabel(choosing)} · 固定会话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请选择 ${assistant.name.ifBlank { "当前 AI" }} 的已有会话。保存目标后保持关闭，需要你再点亮来源才接收。")
                    if (conversations.isEmpty()) Text("还没有已有会话。请先与此 AI 建立对话，再回来选择。")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(conversations, key = { it.id.toString() }) { conversation ->
                            OutlinedButton(enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                                selectedSource = null
                                saveBinding(choosing, OrbisEventBinding(assistant.id.toString(), conversation.id.toString(), enabled = false),
                                    "固定目标已保存，来源保持关闭。确认目标后可点亮来源。")
                            }) {
                                Text("${conversation.title.ifBlank { "未命名会话" }}\n${conversation.id.toString().take(8)}")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedSource = null }) { Text("取消") } },
        )
    }
}

/** Synthetic-test seam: no repository, service, network or credential access. */
@Composable
internal fun OrbisSentinelBindingCard(
    assistantId: String?,
    assistantName: String,
    assistantNames: Map<String, String>,
    conversations: List<Conversation>,
    inbox: OrbisInboxState,
    canEdit: Boolean,
    loading: Boolean,
    notice: String?,
    onChooseTarget: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("哨兵与提醒 · 固定收件箱", style = MaterialTheme.typography.titleSmall)
            Text("当前 AI：$assistantName", style = MaterialTheme.typography.bodyMedium)
            Text("这里仅设置 Orbis 接收目标，不会开启旧 VPS 哨兵，也不代表迁移完成。不开新窗口、不跟随最近聊天；每个来源只送到你明确选择的会话。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) Text("正在读取已有会话…", style = MaterialTheme.typography.bodySmall)
            ORBIS_EVENT_SOURCES.forEach { source ->
                val binding = inbox.bindings[source]
                val isCurrentAi = binding != null && binding.assistantId == assistantId
                val target = if (isCurrentAi) conversations.firstOrNull { it.id.toString() == binding?.conversationId } else null
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(orbisEventSourceLabel(source), style = MaterialTheme.typography.bodyMedium)
                        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when {
                            binding == null -> "未绑定 · 默认关闭"
                            !isCurrentAi -> "现绑定其他 AI：${assistantNames[binding.assistantId] ?: "已移除 AI"}（${if (binding.enabled) "接收中" else "已关闭"}）"
                            target != null -> "固定会话：${target.title.ifBlank { "未命名会话" }} · ${target.id.toString().take(8)}"
                            loading -> "已保存目标，正在核对会话"
                            else -> "已保存的会话不可用，请重新选择；不会自动换窗"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (source == "legacy_sentinel") Text("此旧来源归属尚未核实，请确认后再启用。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = canEdit, onClick = { onChooseTarget(source) },
                                modifier = Modifier.weight(1f).testTag("sentinel-target-$source")) {
                                Text(if (isCurrentAi && target != null) "更换固定会话" else "选择固定会话")
                            }
                            StarSwitch(checked = isCurrentAi && binding?.enabled == true,
                                enabled = canEdit && isCurrentAi && target != null,
                                onCheckedChange = { onToggle(source, it) },
                                modifier = Modifier.testTag("sentinel-enabled-$source").semantics {
                                    contentDescription = "${orbisEventSourceLabel(source)}接收开关"
                                })
                        }
                    }
                }
            }
            if (notice != null) Text(notice, style = MaterialTheme.typography.bodySmall)
            val receipts = inbox.events.filter { it.assistantId == assistantId }.sortedByDescending { it.receivedAt }
            Text("收件状态 · ${receipts.size} 条", style = MaterialTheme.typography.titleSmall)
            if (receipts.isEmpty()) Text("还没有此 AI 的收件记录；仅保存绑定不会产生测试消息。", style = MaterialTheme.typography.bodySmall)
            receipts.take(6).forEach { receipt ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${orbisEventSourceLabel(receipt.source)} · ${orbisReceiptStateLabel(receipt.state)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (receipt.state in setOf("unknown", "target_invalid", "failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("${orbisEventReceivedTime(receipt.receivedAt)} · 固定会话 ${receipt.conversationId.take(8)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (receipt.state == "unknown") Text("无法确认是否完成，不会自动重试。请先查看固定会话，避免重复唤醒。",
                        style = MaterialTheme.typography.bodySmall)
                    if (receipt.state == "target_invalid") Text("原固定目标已失效或绑定已改变，不会改投其他会话。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (receipts.size > 6) Text("这里只显示最近 6 条，收件记录仍保留；已存入会话的原文可在对应会话查看。", style = MaterialTheme.typography.bodySmall)
            Text("已接收不等于已回复；状态未知不等于失败。原文作为外部事件卡保存，不会伪装成你的发言。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun orbisReceiptStateLabel(state: String): String = when (state) {
    "accepted" -> "已接收"
    "queued" -> "排队中"
    "skipped" -> "本次已跳过，不再排队"
    "displayed" -> "已存入会话"
    "generating" -> "正在唤醒"
    "replied" -> "已回复"
    "pending_tool" -> "等待工具处理"
    "unknown" -> "状态未知"
    "target_invalid" -> "固定目标不可用"
    "failed" -> "未完成"
    else -> "未知状态"
}
