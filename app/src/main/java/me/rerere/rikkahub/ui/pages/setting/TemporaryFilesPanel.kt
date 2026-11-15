package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.TemporaryFileCleanup
import me.rerere.rikkahub.data.files.TemporaryFileKind
import me.rerere.rikkahub.data.files.TemporaryFilePreview
import me.rerere.rikkahub.utils.fileSizeToString

@Composable
internal fun TemporaryFilesPanel(cleanup: TemporaryFileCleanup, onCleaningChanged: (Boolean) -> Unit = {}) {
    var preview by remember(cleanup) { mutableStateOf<TemporaryFilePreview?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var cleaning by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(TemporaryFileKind.entries.toSet()) }
    var pending by remember { mutableStateOf<Pair<TemporaryFilePreview, Set<TemporaryFileKind>>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cleanup, revision) {
        scanning = true
        preview = null
        try {
            preview = withContext(Dispatchers.IO) { cleanup.scan { coroutineContext.ensureActive() } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "未能完成扫描，尚未删除文件；可以重新扫描。" }
        finally { scanning = false }
    }
    Column(Modifier.fillMaxWidth().testTag("temporary-files-panel").padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("临时文件清理", style = MaterialTheme.typography.titleLarge)
        Text("检查超过 24 小时的备份、导入和上传临时文件。重复旧备份会核对内容，每组至少保留一份。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("当前聊天、头像壁纸、正式自动存档和 AI 工作区不会在这里删除。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在核对文件与重复备份…", Modifier.testTag("temporary-files-scanning"))
        }
        preview?.let { result ->
            for (kind in TemporaryFileKind.entries) {
                val kinds = setOf(kind)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = kind in selected, enabled = !cleaning && !scanning && result.count(kinds) > 0,
                        modifier = Modifier.testTag("temporary-files-${kind.name}"),
                        onCheckedChange = { checked -> selected = if (checked) selected + kind else selected - kind })
                    Column(Modifier.weight(1f)) {
                        Text(when (kind) {
                            TemporaryFileKind.BACKUP_TRANSFER -> "备份与导入临时文件"
                            TemporaryFileKind.DUPLICATE_REPAIR -> "重复的旧修复备份"
                        })
                        Text("${result.count(kinds)} 个 · ${result.bytes(kinds).fileSizeToString()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (result.candidates.isEmpty()) Text("没有符合条件的临时文件。", Modifier.testTag("temporary-files-empty"))
            else Text("选中 ${result.count(selected)} 个，约 ${result.bytes(selected).fileSizeToString()}",
                modifier = Modifier.testTag("temporary-files-total"), style = MaterialTheme.typography.titleMedium)
            Button(enabled = !cleaning && !scanning && result.count(selected) > 0,
                modifier = Modifier.fillMaxWidth().testTag("temporary-files-clean"),
                onClick = { pending = result to selected.toSet() }) {
                Text(if (cleaning) "正在清理…" else "清理选中项目")
            }
        }
        notice?.let { Text(it, Modifier.testTag("temporary-files-notice"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(enabled = !scanning && !cleaning, onClick = { notice = null; revision++ }) { Text("重新扫描") }
    }
    pending?.let { (snapshot, kinds) ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("清理这 ${snapshot.count(kinds)} 个临时文件？") },
            text = { Text("约 ${snapshot.bytes(kinds).fileSizeToString()}。这些临时副本将被删除；重复旧备份每组保留一份。正式自动存档和当前聊天保持原样。") },
            dismissButton = { TextButton(onClick = { pending = null }, modifier = Modifier.testTag("temporary-files-cancel")) { Text("取消") } },
            confirmButton = { TextButton(modifier = Modifier.testTag("temporary-files-confirm"), onClick = {
                if (!cleaning) {
                    pending = null; cleaning = true; onCleaningChanged(true)
                    scope.launch {
                        try {
                            val receipt = withContext(Dispatchers.IO) { cleanup.clean(snapshot, kinds) { coroutineContext.ensureActive() } }
                            notice = "已清理 ${receipt.deleted} 个，约 ${receipt.bytes.fileSizeToString()}。" +
                                if (receipt.backupBusy) "正在备份，剩余文件保留，请完成后重试。"
                                else if (receipt.skipped > 0) "${receipt.skipped} 个文件已变化或未能删除，已跳过。" else "其余文件保持原样。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { notice = "清理已停止，剩余文件保留；重新扫描可查看当前占用。" }
                        finally { cleaning = false; onCleaningChanged(false); revision++ }
                    }
                }
            }) { Text("确认清理") } })
    }
}
