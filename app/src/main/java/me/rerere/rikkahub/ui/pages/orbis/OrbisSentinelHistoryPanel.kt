package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.OrbisCombinedHistoryPreview
import me.rerere.rikkahub.data.orbis.OrbisHistoryCleanupReceipt
import me.rerere.rikkahub.data.orbis.OrbisInboxState
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelState
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels
import me.rerere.rikkahub.service.ChatService

/** Explicit human controls. No history is removed just by viewing this panel unless opted in. */
@Composable
internal fun OrbisSentinelHistoryPanel(state: OrbisSentinelState, inbox: OrbisInboxState,
    sentinels: OrbisSentinels, chat: ChatService) {
    val scope = rememberCoroutineScope()
    var days by remember(state.historyRetention.keepDays) { mutableIntStateOf(state.historyRetention.keepDays) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<OrbisCombinedHistoryPreview?>(null) }
    var enableConfirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "操作结果尚未确认，请重开此页核对；没有重放旧提醒。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(state.historyRetention.automatic) {
        if (state.historyRetention.automatic) runCatching { chat.cleanOrbisHistoryIfDue() }
    }
    OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("历史记录管理", style = MaterialTheme.typography.titleMedium)
            Text("管理全部助手的已完成旧执行与送达正文，不删除规则、聊天或图片。待处理、未知结果及恢复保护记录继续保留。", style = MaterialTheme.typography.bodySmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3, 5, 7, 30).forEachIndexed { index, value ->
                    SegmentedButton(selected = days == value, enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(index, 4), onClick = { days = value }) { Text("$value 天") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("自动清理", style = MaterialTheme.typography.titleSmall)
                    Text(if (state.historyRetention.automatic) "保留 ${state.historyRetention.keepDays} 天；本机调度或打开此页时每天至多一次。" else "默认关闭，开启前会再次确认。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.historyRetention.automatic, enabled = !busy, onCheckedChange = { enabled ->
                    if (enabled) enableConfirm = true else run { withContext(Dispatchers.IO) { sentinels.rules.setHistoryRetention(false, days) } }
                })
            }
            if (state.historyRetention.automatic && days != state.historyRetention.keepDays) TextButton(enabled = !busy, onClick = { enableConfirm = true }) { Text("将自动保留改为 $days 天") }
            Button(enabled = !busy, onClick = { run { preview = chat.previewOrbisHistory(days) } }) { Text(if (busy) "正在核对…" else "预览可清理记录") }
            Text("已保留去重凭据：送达 ${inbox.historyReceipts.size} 条 · 执行 ${state.historyReceipts.size} 条。凭据不含提醒正文，会缓慢增加；不会为了省空间丢失防重复保护。", style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val delivery = inbox.lastCleanup
            val execution = state.lastCleanup
            if (delivery != null || execution != null) {
                Text("最近清理回执", style = MaterialTheme.typography.labelLarge)
                delivery?.let { Text(historyReceiptText("送达", it), style = MaterialTheme.typography.bodySmall) }
                execution?.let { Text(historyReceiptText("执行", it), style = MaterialTheme.typography.bodySmall) }
                if (delivery?.operationId != execution?.operationId) Text("两类回执不属于同一次操作，可能只完成了一部分；再次清理会重新核对。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    preview?.let { p -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("清理 $days 天以前的已完成记录？") },
        text = { Text("可清理送达 ${p.deliveries.candidates} 条、执行 ${p.executions.candidates} 条。其余 ${p.deliveries.protected + p.executions.protected} 条为较新或受保护记录。\n当前两份记录文件约 ${(p.deliveries.bytes + p.executions.bytes) / 1024} KB；去重凭据仍会占少量空间。清理正文不可撤销，聊天和附件不受影响。确认时会重新核对状态。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { preview = null; run {
            val result = chat.cleanOrbisHistory(days)
            notice = if (result.error == null) "已核实：送达清理 ${result.deliveries?.removed ?: 0} 条，执行清理 ${result.executions?.removed ?: 0} 条。"
                else "未能确认全部完成，请核对下方两份回执；已完成部分保留，不会重新唤醒旧事件。"
        } }) { Text("确认清理") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } }) }
    if (enableConfirm) AlertDialog(onDismissRequest = { enableConfirm = false }, title = { Text("启用自动清理？") },
        text = { Text("只清理 $days 天以前已完成且安全的历史记录。已清理正文不能撤销；原聊天、附件、规则及防重复凭据保留。关闭后不会补回清掉的正文。") },
        confirmButton = { TextButton(onClick = { enableConfirm = false; run {
            withContext(Dispatchers.IO) { sentinels.rules.setHistoryRetention(true, days) }
            chat.cleanOrbisHistoryIfDue()
        } }) { Text("开启") } }, dismissButton = { TextButton(onClick = { enableConfirm = false }) { Text("取消") } })
}

private fun historyReceiptText(label: String, receipt: OrbisHistoryCleanupReceipt): String =
    "$label：清理 ${receipt.removed} 条，剩余 ${receipt.remaining} 条；记录文件 ${receipt.bytesBefore} → ${receipt.bytesAfter} 字节。"
