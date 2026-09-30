package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.service.OrbisIncomingCallRuntime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun OrbisIncomingCallHistoryButton() {
    val context = LocalContext.current
    val runtime = remember { OrbisIncomingCallRuntime.get(context) }
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<IncomingCallAttempt>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val next = runtime.ledger.list(offset = if (reset) 0 else rows.size, limit = 30)
                rows = if (reset) next else rows + next
                hasMore = next.size == 30; error = null
            } catch (_: Exception) { error = "来电日志暂时无法读取；未删除或修改记录。" }
            finally { loading = false }
        }
    }
    Column {
        TextButton(onClick = { show = true; load(true) }) { Text("查看全部主动来电日志") }
        if (Build.VERSION.SDK_INT >= 34) TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}"))) }
        }) { Text("系统来电全屏提醒权限") }
        Text("后台能否弹出全屏由系统决定；接听前不开麦。拒接或无人接后冷却，不自动回拨。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("主动来电日志") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { item { Text(it) } }
                if (rows.isEmpty() && !loading) item { Text("还没有来电尝试。") }
                items(rows, key = { it.id }) { row ->
                    Column {
                        Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(row.startedAtMs)), style = MaterialTheme.typography.labelMedium)
                        Text(row.reason)
                        Text("结果：${when (row.outcome.name) {
                            "CONNECTED" -> "已接通"; "REJECTED" -> "已拒接"; "NO_RESPONSE" -> "无人接听"
                            "RINGING" -> "响铃中"; "CONNECTING" -> "连接中"; else -> "失败"
                        }}${if (row.mutedAnswer) " · 静音接听" else ""}")
                        Text("降级通知：${if (row.fallbackPosted) "已提交系统" else if (row.fallbackAttempted) "尝试未成功/结果未知" else "未尝试"}" +
                            (row.fallbackSpeech?.let { " · 朗读：$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        row.failureCode?.let { Text("原因：$it", style = MaterialTheme.typography.bodySmall) }
                        Text("记录 ${row.id}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (hasMore) item { TextButton(enabled = !loading, onClick = { load(false) }) { Text(if (loading) "读取中…" else "加载更早记录") } }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } })
}
