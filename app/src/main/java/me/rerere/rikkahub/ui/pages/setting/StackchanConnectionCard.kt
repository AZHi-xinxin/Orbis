package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.WebServerState
import org.koin.compose.koinInject

/** Credentials are created/copied only by a human click, never rendered or sent to a model. */
@Composable
internal fun StackchanConnectionCard(server: WebServerState) {
    val context = LocalContext.current
    val chat = koinInject<ChatService>()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在读取本机接收状态…") }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    suspend fun refresh() {
        status = withContext(Dispatchers.IO) {
            runCatching {
                val (enabled, count) = chat.orbisTouchStatus()
                val receipt = chat.latestOrbisTouchReceipt()
                val last = receipt?.let {
                    val outcome = when (it.status) {
                        "accepted" -> "已交给 ${it.delivered} 条规则处理（不代表 AI 已回复）"
                        "no_rules" -> "已收到，但没有启用的触摸规则"
                        "suppressed" -> "已收到，本次未唤醒；检查总开关、冷却和规则条件"
                        "rejected" -> "已收到，但事件时间不合适"
                        "processing" -> "正在处理，勿重复发送"
                        else -> "结果尚未确认，请核对原会话，勿重复发送"
                    }
                    "最近接收：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.receivedAtMs))}，$outcome"
                } ?: "还没有收到通过鉴权的真实触摸。"
                "自动唤醒：${if (enabled) "开启" else "关闭"} · 已启用触摸规则：$count 条\n$last"
            }.getOrDefault("状态读取失败，原规则和凭据未改动。")
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val host = if (server.localhostOnly) "127.0.0.1" else server.address ?: server.hostname
    val base = host?.let { "http://${if (':' in it && !it.startsWith('[')) "[$it]" else it}:${server.port}" }
    fun copyConfiguration() {
        if (busy || !server.isRunning || base == null) return
        busy = true
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    val token = chat.orbisEvents.touchCredentialForHuman()
                    buildJsonObject {
                        put("touch_url", "$base/api/orbis/sentinels/touch")
                        put("status_url", "$base/api/orbis/sentinels/touch/status")
                        put("receipt_url", "$base/api/orbis/sentinels/touch/receipt")
                        put("method", "POST")
                        put("headers", buildJsonObject { put("Authorization", "Bearer $token"); put("Content-Type", "application/json") })
                        put("body_fields", buildJsonObject { put("event_id", "每次真实触摸的唯一字符串ID；重试同一次事件保持不变")
                            put("occurred_at", "触摸发生时间，Unix毫秒整数；不是秒") })
                        put("instructions", "先GET status_url验证鉴权；只在真实触摸时POST。不要发送prompt或目标助手。超时先用receipt_url?event_id=原ID查回执，不换ID重放。")
                    }.toString()
                }
                val clip = ClipData.newPlainText("StackChan 设备接入配置", config)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                notice = "设备配置已复制（含专用密钥），请粘贴到你信任的触摸转发程序配置中；不要发到普通聊天或群。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "复制未完成，请重试；没有发送触摸事件。" }
            finally { busy = false }
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("stackchan-connection"),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("StackChan 设备接入", style = MaterialTheme.typography.titleMedium)
            Text("让机器人或转发程序报告真实触摸。AI 自己设置触摸哨兵；人类在这里配置设备连接。", style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(if (!server.isRunning) "先启动本页的 Web 服务，再复制设备配置。"
                else if (server.localhostOnly) "当前仅本机访问，外部机器人不能直接连接；如需局域网连接，请停止服务后修改访问范围。"
                else base?.let { "接收地址：$it/api/orbis/sentinels/touch" } ?: "正在获取可连接地址，请稍后刷新。",
                style = MaterialTheme.typography.bodySmall)
            Text("先让转发程序读取状态接口验证连接，成功后再摸一次设备，并刷新这里。401 表示设备密钥不匹配；Web 登录密码或登录令牌不能代替它。局域网直连需在可信网络内；远程使用私有网络或 HTTPS 转发。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = ::copyConfiguration, enabled = !busy && server.isRunning && base != null,
                modifier = Modifier.testTag("stackchan-copy-config")) { Text("复制设备配置（含密钥）") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { refresh() } }) { Text("刷新接收状态") }
                TextButton(onClick = { confirmRotate = true }, enabled = !busy) { Text("重新配对") }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmRotate) AlertDialog(onDismissRequest = { confirmRotate = false }, title = { Text("重新配对设备？") },
        text = { Text("通过本入口复制的旧设备密钥会失效。请随后复制新配置并更新转发程序；已有哨兵规则和聊天记录保留。") },
        confirmButton = { TextButton(onClick = {
            confirmRotate = false; busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { chat.orbisEvents.rotateTouchCredentialForHuman(); Unit }
                    notice = "旧设备密钥已撤销，请复制新配置。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "配对更新未能确认，请重新复制配置后核验。" }
                finally { busy = false }
            }
        }) { Text("重新配对") } }, dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("取消") } })
}
