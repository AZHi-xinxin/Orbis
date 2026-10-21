package me.rerere.rikkahub.ui.pages.backup.tabs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.*

/** Connection documents are deliberately user-selected, never scanned from arbitrary shared files. */
@Composable
internal fun BackupRecoveryActions(settings: Settings, store: SettingsStore,
                                    onNotice: (String) -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var exportConsent by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingConnection by remember { mutableStateOf<BackupRecoveryConnection?>(null) }
    var pendingRestore by remember { mutableStateOf<Settings?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null && bytes != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    VerifiedBackupExport.copy({ bytes.inputStream() },
                        { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) },
                        { requireNotNull(context.contentResolver.openInputStream(uri)) })
                }
                onNotice("恢复连接已保存并校验。请私密保管；它不是备份本身，还需确认服务器存档成功。")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { onNotice("恢复连接文件未确认写好，原连接设置未改动。") }
            finally { busy = false; bytes.fill(0) }
        } else bytes?.fill(0)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pendingConnection = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        BackupRecoveryConnection.decode(it.readBytesBounded(BackupRecoveryConnection.MAX_BYTES))
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { onNotice("这不是可用的 Orbis 恢复连接文件；没有访问服务器或修改聊天。") }
            finally { busy = false }
        }
    }
    Text("重装后找回", style = MaterialTheme.typography.titleMedium)
    Text("先把恢复连接文件存到应用外。重装后选择该文件即可重新绑定，再一键拉取最新完整存档；不用另记安全码，也不用重新抄服务器配置。",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && BackupRecoveryConnection.ready(settings),
            onClick = { exportConsent = true }) { Text("保存恢复连接") }
        OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/json", "application/octet-stream")) }) {
            Text("读取恢复连接")
        }
        Button(enabled = !busy && BackupRecoveryConnection.ready(settings),
            onClick = { pendingRestore = settings }) { Text("一键找回云端最新存档") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (exportConsent) AlertDialog(onDismissRequest = { exportConsent = false },
        title = { Text("把恢复连接留在应用外") },
        text = { Text("文件包含这台备份服务器的授权，拿到文件的人可能读取你的存档。请存到自己保管的文件夹，不要发群或公开分享。它不包含聊天正文，也不是另一套密码。") },
        confirmButton = { TextButton(onClick = {
            exportConsent = false
            runCatching { BackupRecoveryConnection.from(settings).encode() }.onSuccess {
                exportBytes = it; save.launch("Orbis-恢复连接.json")
            }.onFailure { onNotice("请先补全服务器连接。") }
        }) { Text("选择私密保存位置") } },
        dismissButton = { TextButton(onClick = { exportConsent = false }) { Text("取消") } })
    pendingConnection?.let { connection ->
        AlertDialog(onDismissRequest = { pendingConnection = null }, title = { Text("重新绑定这处存档") },
            text = { Text("${connection.displayDestination()}\n只读取你选择的连接，不会立即上传或覆盖聊天；之后可直接找回云端最新存档。") },
            confirmButton = { TextButton(onClick = {
                pendingConnection = null; busy = true
                scope.launch {
                    try {
                        check(store.update { connection.applyTo(it) })
                        onNotice("连接已读取，自动上传保持关闭。现在可以一键找回云端最新存档。")
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { onNotice("连接未确认保存；现有聊天未改动。") }
                    finally { busy = false }
                }
            }) { Text("使用这处连接") } },
            dismissButton = { TextButton(onClick = { pendingConnection = null }) { Text("取消") } })
    }
    pendingRestore?.let { source ->
        AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("找回云端最新完整存档？") },
            text = { Text("会从已绑定服务器下载最新完整版本，核对内容后准备恢复。重启才替换聊天和设置；被替换的本机资料另留恢复前副本。不打断正在进行的通话或回复，不需要安全码。") },
            confirmButton = { TextButton(onClick = {
                pendingRestore = null; busy = true
                scope.launch {
                    try {
                        val point = newestCompleteBackup(AutoBackupRuntime.list(context, true, source))
                        AutoBackupRuntime.stage(context, point, true, source)
                        onNotice("云端存档已下载、校验并准备恢复。")
                        // Restart dialog remains explicit and rechecks its existing process lease.
                        onRestart()
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { onNotice("这次没有完成恢复准备，现有聊天未替换。请确认服务器有完整存档，并等待通话或回复结束。也可在下方选择其他版本。") }
                    finally { busy = false }
                }
            }) { Text("拉取并校验") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } })
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit)
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
