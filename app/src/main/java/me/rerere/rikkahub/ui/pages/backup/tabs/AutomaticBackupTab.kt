package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.*
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
fun AutomaticBackupTab(onRestart: () -> Unit, onConnection: (String) -> Unit, store: SettingsStore = koinInject()) {
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val status by AutoBackupRuntime.status.collectAsStateWithLifecycle()
    val ledger by ContinuousBackup.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val config = settings.autoBackup
    var notice by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    var portable by remember { mutableStateOf(false) }
    var versionSource by remember { mutableStateOf<me.rerere.rikkahub.data.datastore.Settings?>(null) }
    var versions by remember { mutableStateOf<List<BackupRestorePoint>>(emptyList()) }
    var restore by remember { mutableStateOf<BackupRestorePoint?>(null) }
    var busyConfig by remember { mutableStateOf(false) }
    fun change(next: AutoBackupConfig) {
        if(busyConfig) return
        busyConfig = true
        scope.launch {
            try { check(store.update { it.copy(autoBackup = next) }); notice = "已保存；存档不占聊天队列" }
            catch(c: CancellationException) { throw c }
            catch(_: Exception) { notice = "设置未保存，原设置保留" }
            finally { busyConfig = false }
        }
    }
    fun load(useRemote: Boolean, usePortable: Boolean = false) {
        if(loading) return
        loading = true; remote = useRemote; portable = usePortable; versions = emptyList(); versionSource = settings
        val selected = settings
        scope.launch {
            try { versions = AutoBackupRuntime.list(context, useRemote, selected, usePortable); notice = if(versions.isEmpty()) "这里还没有校验完成的存档" else "显示已提交的存档；恢复前会再次完整校验内容" }
            catch(c: CancellationException) { throw c }
            catch(_: Exception) { notice = "暂不能读取存档，请检查连接配置和授权；本机聊天未更改" }
            finally { loading = false }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                change(config.copy(archiveDirectoryUri = uri.toString()))
            } catch(_: Exception) { notice = "没有取得文件夹读写授权，原存档保留" }
        }
    }
    fun time(value: Long) = if(value == 0L) "暂无" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("让记录有去处", style = MaterialTheme.typography.headlineSmall)
        Text("聊天先在手机实时保存；资料变化后自动合并存到已选位置，联网后补传。不必反复点备份，没有额外恢复密码。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("本机自动存档"); Text("默认开启，保留最近 3 个完成版本", style = MaterialTheme.typography.bodySmall) }
            Switch(config.localEnabled, enabled = !busyConfig, onCheckedChange = { change(config.copy(localEnabled = it)) })
        }
        Text("应用私有存档防故障，不防卸载或清除数据。可以另外选一个手机文件夹，后续自动留副本；卸载后文件仍由你保管，重新安装需再次选择同一文件夹，不用安全码。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busyConfig, onClick = { folderPicker.launch(null) }) { Text(if(config.archiveDirectoryUri == null) "选择保留存档的文件夹" else "更换存档文件夹") }
            if(config.archiveDirectoryUri != null) TextButton(onClick = { change(config.copy(archiveDirectoryUri = null)); versions = emptyList() }) { Text("解绑") }
        }
        if(config.archiveDirectoryUri != null) Text("已绑定自选文件夹；解绑不会删除里面的存档。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("你的服务器", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("webdav" to "WebDAV", "s3" to "S3").forEach { (key,label) ->
                FilterChip(config.target == key, onClick = { if(!busyConfig) { versions = emptyList(); change(config.copy(target = key, enabled = false)) } }, label = { Text(label) })
            }
            TextButton(onClick = { onConnection(config.target) }) { Text("填写或检查连接") }
        }
        Text("可以是 VPS、电脑或手机上的存储服务。上传的是可恢复存档，不是公开分享；请使用受保护的目录。HTTP 是明文传输，建议 HTTPS 或可信专网。", style = MaterialTheme.typography.bodySmall)
        if (!BackupRecoveryConnection.ready(settings))
            Text("还没有可用的服务器连接：本机与自选文件夹仍可保存，但尚未拥有云端副本。请先填写连接，或读取恢复连接文件。", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("允许自动上传至已填服务器", Modifier.weight(1f))
            Switch(config.enabled, enabled = !busyConfig, onCheckedChange = { change(config.copy(enabled = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("允许使用流量网络", Modifier.weight(1f))
            Switch(config.allowMetered, enabled = !busyConfig, onCheckedChange = { change(config.copy(allowMetered = it)) })
        }
        Text("新变化在后台合并保存；正在回复或通话时先留在本机，结束后自动补存。系统休眠、断网可能延后对外副本，不会阻断聊天。", style = MaterialTheme.typography.bodySmall)
        if (config.target == "webdav") {
            Text("WebDAV：只保留最新一份完整存档", style = MaterialTheme.typography.titleMedium)
            Text("需要服务器支持安全覆盖。上传时先暂存，完整校验后再替换原存档；上传中断不破坏上一份。不会不断新增备份再靠删除清理。", style = MaterialTheme.typography.bodySmall)
        }
        Text(if (config.target == "s3") "自选文件夹和 S3 各保留 ${config.keepVersions} 个完成版本"
            else "自选文件夹保留 ${config.keepVersions} 个完成版本（不影响 WebDAV）")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3,7,14,30).forEach { count -> FilterChip(config.keepVersions == count,
                onClick = { change(config.copy(keepVersions = count)) }, label = { Text("$count 个") }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("对外副本同时保存连接密钥"); Text("服务器和自选文件夹默认不带密钥；开启后务必保管好存档", style = MaterialTheme.typography.bodySmall) }
            Switch(config.includeConnectionSecrets, enabled = !busyConfig, onCheckedChange = { change(config.copy(includeConnectionSecrets = it)) })
        }
        Text("应用内私有副本保留连接配置，便于本机故障找回。对外副本不带密钥时，恢复后可能需重新填写服务授权，这不是恢复安全码。聊天和附件原样备份；私人咨询排除规则继续生效。", style = MaterialTheme.typography.bodySmall)
        Text(status.message)
        ContinuousBackup.selected(settings).forEach { (kind, key) ->
            val progress = ledger.targets[key] ?: BackupTargetProgress()
            val name = when(kind) { "local" -> "应用内副本"; "folder" -> "自选文件夹"; "webdav" -> "当前 WebDAV"; else -> "当前 S3" }
            Text("$name：${time(progress.completedAt)}\n${progress.message}${if(ledger.pending(key) && progress.completedAt > 0) " · 有新变化待补存" else ""}",
                style = MaterialTheme.typography.bodySmall)
            if(progress.needsReview) {
                TextButton(onClick = { load(kind != "local" && kind != "folder", kind == "folder") }) { Text("查看这里原有的存档") }
                TextButton(onClick = { scope.launch { ContinuousBackup.allowExisting(context, key) } }) { Text("不恢复，以当前资料继续自动存档") }
            }
        }
        Button(enabled = !status.busy && (config.enabled || config.localEnabled), onClick = { AutoBackupRuntime.now(context); notice = "已交给独立后台任务，可以返回聊天" }) { Text(if(status.busy) "存档进行中" else "现在存一份") }
        val controller = config.controllerAssistant
        val workspace = config.controllerWorkspace
        if (controller != null && workspace != null && AiBackupBinding.owns(settings, controller, workspace)) {
            val key = ContinuousBackup.key(config.target, settings)
            val operation by remember(context, controller, key) {
                AiBackupOperations.observe(context, controller, key)
            }.collectAsStateWithLifecycle(initialValue = "not_checked")
            Text("AI 管理的备份连接", style = MaterialTheme.typography.titleMedium)
            Text(when(operation) {
                "connected_not_a_backup_receipt" -> "连接检查通过；这不是上传成功回执，完成时间请看上方。"
                "checking" -> "正在后台检查连接。"
                "waiting_for_call_or_reply" -> "已收到恢复请求，等本轮回复或通话结束后处理。"
                "downloading_and_verifying" -> "正在下载并校验恢复版本，尚未替换当前资料。"
                "restore_staged_restart_required" -> "恢复版本已校验并暂存；重启应用后生效。"
                "timeout_originals_preserved" -> "本次操作超时，原有资料保留；可让 AI 再检查连接。"
                "cancelled_binding_changed", "cancelled_originals_preserved" -> "本次操作已取消，原有资料保留。"
                "not_completed_check_connection_or_complete_version" -> "本次操作未完成，请检查连接或完整存档。"
                else -> "配置由当前助手绑定；尚无连接检查完成回执。"
            }, style = MaterialTheme.typography.bodySmall)
            if(operation == "restore_staged_restart_required") {
                OutlinedButton(onClick = {
                    if(me.rerere.rikkahub.service.OrbisVoiceCallRuntime.get(context).callState.value.isActive ||
                        org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>().hasActiveGeneration())
                        notice = "正在回复或通话，先不重启；结束后可回来完成恢复。"
                    else onRestart()
                }) { Text("重启以完成已暂存的恢复") }
            }
        }
        HorizontalDivider()
        BackupRecoveryActions(settings, store, onNotice = { notice = it }, onRestart = onRestart)
        HorizontalDivider()
        Text("找回存档", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !loading, onClick = { load(false) }) { Text("本机存档") }
            if(config.archiveDirectoryUri != null) OutlinedButton(enabled = !loading, onClick = { load(false, true) }) { Text("自选文件夹") }
            OutlinedButton(enabled = !loading, onClick = { load(true) }) { Text("服务器存档") }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        versions.forEach { point ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(time(point.createdAt)); Text("${point.bytes / 1024 / 1024} MB · ${if(point.includesConnectionSecrets) "包含连接密钥" else "不含连接密钥"}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !loading, onClick = { restore = point }) { Text("恢复") }
            }
        }
    }
    restore?.let { point -> AlertDialog(onDismissRequest = { restore = null }, title = { Text("恢复这个完整版本？") },
        text = { Text("下载后会再次核对内容，再暂存恢复。重启时将聊天、设置及备份内的学习/宠物等记录恢复到所选时间，不需要安全码。现有被替换文件和设置会另外保留在本机恢复前目录；未包含在旧备份内的资料保留。请先结束通话与回复。") },
        confirmButton = { TextButton(onClick = {
            restore = null; loading = true
            scope.launch {
                try {
                    AutoBackupRuntime.stage(context, point, remote, versionSource, portable)
                    if(me.rerere.rikkahub.service.OrbisVoiceCallRuntime.get(context).callState.value.isActive ||
                        org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>().hasActiveGeneration())
                        notice = "恢复已校验并暂存；当前回复或通话不打断，结束后重新打开应用即可恢复。"
                    else onRestart()
                }
                catch(c: CancellationException) { throw c }
                catch(_: Exception) { notice = "恢复未准备完成，现有数据未覆盖。请检查连接、空间或本机档案冲突。" }
                finally { loading = false }
            }
        }) { Text("校验并准备恢复") } }, dismissButton = { TextButton(onClick = { restore = null }) { Text("取消") } }) }
}
