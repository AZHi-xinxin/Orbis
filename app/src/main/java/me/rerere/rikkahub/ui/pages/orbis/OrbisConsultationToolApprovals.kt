package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.consultation.ConsultationPendingApproval
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject

/** Deliberately local-only. An operator token or relay response cannot approve a phone tool. */
@Composable
internal fun OrbisConsultationToolApprovals(sessionId: String, onDismiss: () -> Unit) {
    if (!me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled) {
        OrbisConsultationUnavailableDialog(onDismiss)
        return
    }
    val chats = koinInject<ChatService>()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<ConsultationPendingApproval>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在核对这台手机的待确认工具…") }
    suspend fun refresh() {
        pending = chats.consultationPendingApprovals(sessionId)
        status = if (pending.isEmpty()) "这台手机当前没有等待确认的工具。" else "只确认下列具体操作，不开启永久授权。"
    }
    LaunchedEffect(sessionId) {
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "本机绑定、场次或请求已变化，不能批准。请核对咨询状态；不会自动执行。" }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("待确认的工具操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这是本机助手请求的常规工具审批。只显示该工具及必要参数，不打开咨询全文或思考；对方 AI 和平台不能代你批准。确认后可能执行操作并继续产生模型费用。",
                    style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pending, key = { it.toolCallId }) { tool ->
                        var answer by remember(tool.toolCallId) { mutableStateOf("") }
                        val completePreview = tool.arguments.length <= 32768
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tool.name, style = MaterialTheme.typography.titleSmall)
                            Text(tool.arguments.take(32768), style = MaterialTheme.typography.bodySmall)
                            if (!completePreview) Text("参数过长，未完整显示，不能从这里批准。可以拒绝并让助手简化操作。")
                            if (tool.name == "ask_user") OutlinedTextField(answer, { answer = it },
                                label = { Text("你的回答") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            Row {
                                TextButton(enabled = !busy && completePreview && (tool.name != "ask_user" || answer.isNotBlank()),
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                chats.confirmConsultationTool(sessionId, tool, true,
                                                    answer.takeIf { tool.name == "ask_user" })
                                                refresh()
                                                status = "本次确认已保存；继续使用原咨询请求。请查看咨询状态，未开启永久授权。"
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { status = "没有取得完整执行确认。请刷新核对，勿重复点击或重试未知操作。" }
                                            finally { busy = false }
                                        }
                                    }) { Text(if (tool.name == "ask_user") "提交回答一次" else "允许这一次") }
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try { chats.confirmConsultationTool(sessionId, tool, false); refresh() }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { status = "请求状态已变化，请刷新核对；没有新增授权。" }
                                        finally { busy = false }
                                    }
                                }) { Text("拒绝") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "暂时无法核对本机请求；未批准或重新执行任何操作。" }
                finally { busy = false }
            }
        }, enabled = !busy) { Text("刷新") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}
