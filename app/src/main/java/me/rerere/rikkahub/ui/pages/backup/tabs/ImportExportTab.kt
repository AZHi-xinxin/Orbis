package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.importer.RikkaChatArchive
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import me.rerere.rikkahub.data.sync.importer.RikkaPartialImportException
import me.rerere.rikkahub.data.sync.importer.ChatboxPartialImportException
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(vm: BackupVM, onShowRestartDialog: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val items by vm.localBackupItems.collectAsStateWithLifecycle()
    val deepSeekState by vm.deepSeekImport.state.collectAsStateWithLifecycle()
    val operitState by vm.operitImport.state.collectAsStateWithLifecycle()
    val kelivoState by vm.kelivoImport.state.collectAsStateWithLifecycle()
    val polarisState by vm.polarisImport.state.collectAsStateWithLifecycle()
    val openPolaris = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.polarisImport.preview(uri)
    }
    val openKelivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.kelivoImport.preview(uri)
    }
    val openOperit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.operitImport.preview(uri)
    }
    val openDeepSeek = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.deepSeekImport.preview(uri)
    }
    var busy by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf("rikka") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val summary = withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("orbis-import-", ".zip", context.cacheDir)
                    try {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            val coroutine = currentCoroutineContext()
                            temp.outputStream().use { output -> RikkaChatArchive.copyLimited(input, output,
                                RikkaChatArchive.MAX_ARCHIVE_BYTES, { coroutine.ensureActive() },
                                { count -> ArchiveCapacity.requireSpace(context.cacheDir.usableSpace, count.toLong()) }) }
                        }
                        when (action) {
                            "rikka" -> vm.importRikkaChats(temp).let {
                                "已导入 ${it.imported} 个聊天窗口，跳过 ${it.skipped} 个已有窗口。\n恢复 ${it.attachments} 个附件，${it.missingAttachments} 处附件不在原备份中。\n没有导入模型、密钥、提示词设置、技能或工具授权。"
                            }
                            "chatbox" -> vm.restoreFromChatBox(temp).let { "已导入 ${it.importedConversations} 个 ChatBox 聊天；跳过 ${it.skippedExistingConversations} 个已有窗口。" }
                            else -> { vm.restoreFromLocalFile(temp); "本地恢复已就绪，重启后生效。" }
                        }
                    } finally { temp.delete() }
                }
                if (action == "local") onShowRestartDialog() else result = summary
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { result = if (e is RikkaPartialImportException || e is ChatboxPartialImportException) e.message
                else "导入未完成：${ArchiveCapacity.publicError(e)}" }
            finally { busy = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val file = vm.exportToFile()
                    try {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                    } finally { file.delete() }
                }
                result = "本地备份已保存。备份含模型连接配置，请私密保管，不要公开分享。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { result = "导出未完成：${e.message ?: "文件无法写入"}" }
            finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("把对话带进来，把这个家留好", color = OrbisTheme.colors.ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("导入聊天是追加；恢复 Orbis 备份是替换。两件事分开做。", color = OrbisTheme.colors.mutedInk, fontSize = 12.sp)
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…") }
        item { ConversationRescueEntry(!busy && !deepSeekState.busy && !operitState.busy && !kelivoState.busy && !polarisState.busy) }
        item { BackupCard("Operit 聊天记录", "支持 Operit v2 JSON 导出。先预览再选择会话，只追加当前选中的回答，内部摘要会跳过并提示；不导入模型设置、人格、权限、工作区或附件实体。", !busy && !operitState.busy, "选择 Operit JSON") {
            openOperit.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        } }
        item { BackupCard("Kelivo 聊天记录", "支持 Kelivo 安卓 v2 ZIP 备份。先预览再追加当前选中的回答，重复导入会跳过。只导聊天，附件保留引用说明；不导入配置、密钥、人格或技能。原备份可能包含密钥，请私密保管。", !busy && !kelivoState.busy, "选择 Kelivo 备份") {
            openKelivo.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } }
        item { BackupCard("北极星聊天记录", "支持北极星导出 ZIP。先预览并选择窗口，再按原顺序追加聊天；思考过程单独保留。图片只显示历史引用说明，不导入附件或配置、密钥、人格与工具授权。重复导入自动跳过。", !busy && !polarisState.busy, "选择北极星备份") {
            openPolaris.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } }
        item { BackupCard("RikkaHub 聊天记录", "在 RikkaHub 导出含“聊天记录”的本地 ZIP，建议同时勾选附件。合并到当前身份下，不覆盖现有窗口，不导入账号或模型配置。重复导入自动跳过。", !busy, "选择 RikkaHub 备份") { confirm = "rikka" } }
        item { BackupCard("DeepSeek 官方聊天记录", "选择官方导出 ZIP，先预览窗口和分支路径。支持长记录逐会话导入；保留正文、思考和时间，不执行历史工具或下载附件。不会改变现有聊天或连接设置。", !busy && !deepSeekState.busy, "选择 DeepSeek 导出包") {
            openDeepSeek.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } }
        item { BackupCard("ChatBox 兼容导入", "保留原有 ChatBox Backup v2 导入能力；此格式可能包含模型映射和会话系统提示词。", !busy, "选择 ChatBox 备份") { confirm = "chatbox" } }
        item {
            LocalCard {
                Text("导出 Orbis 本地备份", fontWeight = FontWeight.Bold)
                Text("连接设置始终包含在备份中。陪伴模块凭据、设备授权、观察日记与围栏配置不在此备份内。", fontSize = 12.sp)
                WebDavConfig.BackupItem.entries.forEach { item ->
                    Row {
                        Checkbox(checked = item in items, enabled = !busy, onCheckedChange = { checked ->
                            vm.updateLocalBackupItems(if (checked) items + item else items - item)
                        })
                        Text(if (item == WebDavConfig.BackupItem.DATABASE) "聊天记录" else "附件、字体、技能、课表与颜文字", modifier = Modifier.padding(top = 14.dp))
                    }
                }
                TextButton(enabled = !busy, onClick = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    save.launch("orbis_backup_$stamp.zip")
                }) { Text("保存到本地文件") }
            }
        }
        item { BackupCard("恢复 Orbis 备份", "将替换连接设置和上方勾选的数据，恢复前请先导出当前备份。课表和颜文字按编号合并：相同跳过、冲突停止本次恢复；旧备份没有这两项则不动本机两库。不是 RikkaHub 聊天合并入口。", !busy, "选择要恢复的 Orbis 备份") { confirm = "local" } }
    }
    confirm?.let { kind ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (kind == "local") "确认恢复并替换" else "确认导入聊天") },
            text = { Text(when (kind) {
                "rikka" -> "只追加聊天到当前身份。不运行导入的工具、不启用导入的提示词、不更改模型或密钥。不支持的 RikkaHub 备份版本会停止，而不是覆盖你的数据。"
                "chatbox" -> "使用既有 ChatBox 导入规则，可能更新模型映射与会话系统提示词。请仅选自己的备份。"
                else -> "这会替换连接设置及勾选的数据。课表和颜文字按编号合并，同编号不同内容会停止恢复，旧包无这两项则不动本机两库。请确保已有备份；要合并 RikkaHub 聊天请取消并使用第一个入口。"
            }) }, confirmButton = { TextButton(onClick = { action = kind; confirm = null; open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("选择文件") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
    DeepSeekImportDialogs(vm.deepSeekImport, deepSeekState)
    DeepSeekImportDialogs(vm.operitImport, operitState)
    DeepSeekImportDialogs(vm.kelivoImport, kelivoState)
    DeepSeekImportDialogs(vm.polarisImport, polarisState)
    result?.let { message -> AlertDialog(onDismissRequest = { result = null }, title = { Text("处理结果") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { result = null }) { Text("知道了") } }) }
}

@Composable
private fun LocalCard(content: @Composable () -> Unit) {
    val colors = OrbisTheme.colors
    Surface(color = colors.panel, contentColor = colors.ink, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun BackupCard(title: String, body: String, enabled: Boolean, button: String, onClick: () -> Unit) {
    LocalCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(body, fontSize = 12.sp, lineHeight = 19.sp)
        TextButton(enabled = enabled, onClick = onClick) { Text(button) }
    }
}
