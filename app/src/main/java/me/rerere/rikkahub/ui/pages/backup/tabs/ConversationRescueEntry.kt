package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.recovery.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import java.io.File

private const val RESCUE_WINDOW_PAGE_SIZE = 50

@Composable
internal fun ConversationRescueEntry(enabled: Boolean) {
    var opened by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会话自助恢复", style = MaterialTheme.typography.titleMedium)
            Text("窗口打不开也能检查。先核对本机承受范围，再保存原始副本；只有完整记录严格匹配时才允许单条修复，不回退整库、不重做工具。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled, onClick = { opened = true }) { Text("检查会话与保存诊断副本") }
        }
    }
    if (opened) ConversationRescueDialog { opened = false }
}

@Composable
private fun ConversationRescueDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val db: AppDatabase = koinInject()
    val repository: ConversationRepository = koinInject()
    val chat: ChatService = koinInject()
    val rescue = remember { OrbisConversationRescue(context.applicationContext, db, repository) }
    val scope = rememberCoroutineScope()
    var windows by remember { mutableStateOf<List<RescueWindow>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var windowOffset by remember { mutableIntStateOf(0) }
    var hasMoreWindows by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<RescueInspection?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmRepair by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = exportFile
        if (uri != null && file != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                status = "诊断副本已保存到你选择的位置。包含私密聊天，请勿公开上传。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "导出未完成；本机原始副本仍保留，可选择其他位置重试。"
            } finally { busy = false }
        }
    }
    LaunchedEffect(query, windowOffset) {
        busy = true
        try {
            val page = rescue.windows(query, windowOffset, RESCUE_WINDOW_PAGE_SIZE + 1)
            windows = page.take(RESCUE_WINDOW_PAGE_SIZE)
            hasMoreWindows = page.size > RESCUE_WINDOW_PAGE_SIZE
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            windows = emptyList(); hasMoreWindows = false
            status = "无法读取窗口目录，未改动记录。请保留应用数据并寻求协助。"
        }
        finally { busy = false }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("会话自助恢复", style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !busy, onClick = onClose) { Text("关闭") }
                }
                Text("请先停止生成和通话。检查不需要打开会话；只在本机处理，不发送给 AI。超出安全范围会停止，不截断记录；成功保存的副本会保留。",
                    style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                inspection?.let { result ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${result.nodeCount} 个消息节点 · ${result.damagedCount} 个读取异常")
                            Text(result.message, style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { exportFile = result.copy; confirmExport = true }) { Text("导出私密诊断副本") }
                            if (result.repairAvailable) Button(enabled = !busy, onClick = { confirmRepair = true }) { Text("查看并确认单条修复") }
                        }
                    }
                }
                OutlinedTextField(query, onValueChange = { query = it.take(200); windowOffset = 0 }, enabled = !busy,
                    label = { Text("按窗口名称查找") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(windows, key = { it.id.toString() }) { window ->
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                busy = true; status = null; inspection = null
                                try { inspection = chat.withEmergencyRecoveryLock(window.id) { rescue.inspect(window.id) } }
                                catch (e: CancellationException) { throw e }
                                catch (e: RescueCapacityException) { status = e.publicMessage }
                                catch (_: Exception) { status = "检查未完成，聊天未修改。请结束生成/通话、确认本机空间充足后重试；仍失败请勿卸载。" }
                                finally { busy = false }
                            }
                        }) { Text(window.title.ifBlank { "未命名窗口" }, maxLines = 2) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy && windowOffset > 0,
                        onClick = { windowOffset = (windowOffset - RESCUE_WINDOW_PAGE_SIZE).coerceAtLeast(0) }) { Text("上一页") }
                    Text("第 ${windowOffset / RESCUE_WINDOW_PAGE_SIZE + 1} 页", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && hasMoreWindows,
                        onClick = { windowOffset += RESCUE_WINDOW_PAGE_SIZE }) { Text("下一页") }
                }
            }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("副本含私密聊天") },
        text = { Text("包含所选窗口正文、思考、工具记录和恢复记录，工具历史也可能含敏感信息。这不是普通公开错误日志。请只保存到自己的设备，不要发到公开群或 GitHub。") },
        confirmButton = { TextButton(onClick = { confirmExport = false; save.launch("Orbis-private-rescue-${System.currentTimeMillis()}.zip") }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } })
    if (confirmRepair) AlertDialog(onDismissRequest = { confirmRepair = false }, title = { Text("只修复这一条编码损坏的消息？") },
        text = { Text("已找到同一窗口、同一分支、同一阶段的完整副本。将再次核对全部记录，仅替换异常消息的内容字段；原始副本保留。其他消息、收藏和设置不动，不调用模型、不重做工具。若记录已变化则停止；写入或回读失败则整次事务回滚。完成后返回并重新打开原窗口。") },
        confirmButton = { TextButton(onClick = {
            confirmRepair = false
            inspection?.let { result -> scope.launch {
                busy = true
                try {
                    chat.withEmergencyRecoveryLock(result.conversationId, prepareWrite = true) { rescue.repair(result) }
                    inspection = null
                    status = "单条修复已完成并完整回读，前后副本已保存。请返回重新打开原窗口。队列没有自动继续。"
                } catch (e: CancellationException) { throw e }
                catch (e: RescueCapacityException) { inspection = null; status = e.publicMessage }
                catch (_: Exception) { status = "未能确认修复完成，原副本仍保留。可能是记录已变化、正在生成或空间不足。请重新检查，不要清除数据。" }
                finally { busy = false }
            } }
        }) { Text("确认单条修复") } }, dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text("取消") } })
}
