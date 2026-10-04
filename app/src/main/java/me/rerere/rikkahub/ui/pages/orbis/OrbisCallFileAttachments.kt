package me.rerere.rikkahub.ui.pages.orbis

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.chat.OrbisCallFile
import org.koin.compose.koinInject

/** File links are outside the collapsed body. No I/O happens until a human clicks an action. */
@Composable
internal fun OrbisCallFileAttachments(files: List<OrbisCallFile>) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<OrbisCallFile?>(null) }
    // Save only identity, never the private file body in an Activity Bundle. Revalidate on return.
    var exportPending by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (exportStarted) {
            notice = "上次文件导出可能因页面重建而中断，请检查目标文件；没有确认成功时请重新导出。"
            exportStarted = false
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val pendingId = exportPending.also { exportPending = null }
        val file = files.filterIsInstance<OrbisCallFile.Workspace>().firstOrNull { it.exportIdentity == pendingId }
        if (uri != null && file == null) {
            notice = "导出未完成：原文件入口已经变化或未能恢复。所选位置可能只有空文件，请重新导出。"
        }
        if (uri != null && file != null) scope.launch {
            busy = true
            exportStarted = true
            var terminalNoticeShown = false
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        exportCallWorkspaceFile(repository, file, output)
                    } ?: error("output_unavailable")
                }
                notice = "文件已导出。"
                terminalNoticeShown = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "导出未完成，请检查保存位置；若出现不完整文件，请勿当作备份。原文件未修改。"
                terminalNoticeShown = true
            }
            finally { busy = false; if (terminalNoticeShown) exportStarted = false }
        }
    }

    OrbisCallFileAttachmentsContent(files) { file ->
        if (!busy && exportPending == null) {
            selected = file
            preview = null
            notice = null
        }
    }
    if (selected == null) notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    selected?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(file.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (file is OrbisCallFile.Workspace) {
                        Text(file.path, style = MaterialTheme.typography.bodySmall)
                        Text(if (file.workspaceId != null) "打开的是这次工具绑定工作区中的当前文件，内容可能在通话后更新。"
                        else if (file.hasWriteSnapshot) "旧记录未保存工作区绑定；这里提供工具成功写入时的正文副本，不冒充当前文件。"
                        else "旧记录未保存工作区绑定，无法安全定位文件。请按路径到工作区核对；不会自动执行工具或改写文件。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) Text("正在读取或导出…")
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    preview?.let { text -> SelectionContainer { Text(text) } }
                }
            },
            confirmButton = {
                Column {
                    val readable = file !is OrbisCallFile.Workspace || file.workspaceId != null || file.hasWriteSnapshot
                    TextButton(enabled = !busy && readable, onClick = {
                        scope.launch {
                            busy = true
                            notice = null
                            try {
                                when (file) {
                                    is OrbisCallFile.Workspace -> {
                                        preview = withContext(Dispatchers.IO) {
                                            val out = ByteArrayOutputStream()
                                            exportCallWorkspaceFile(repository, file, CallPreviewOutput(out))
                                            out.toString(Charsets.UTF_8.name())
                                        }
                                    }
                                    is OrbisCallFile.Document -> {
                                        val source = file.part.url.toUri()
                                        val uri = if (source.scheme == "file") {
                                            val local = withContext(Dispatchers.IO) {
                                                resolveCallUploadFile(file.part.url, File(context.filesDir, "upload"))
                                            }
                                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
                                        } else {
                                            // Never forward a model/import-supplied content:// URI with our
                                            // app's permissions (including its broad internal FileProvider).
                                            require(source.scheme in setOf("https", "http"))
                                            source
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            if (uri.scheme in setOf("https", "http")) data = uri
                                            else {
                                                setDataAndType(uri, file.part.mime)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                        }
                                        context.startActivity(Intent.createChooser(intent, "打开通话文件"))
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                notice = "暂时无法打开：文件可能已移动、来源不可用，或正文超过 2 MiB 预览限制。可以尝试导出；不会重新生成或覆盖原文件。"
                            } finally { busy = false }
                        }
                    }) { Text("查看文件") }
                    if (file is OrbisCallFile.Workspace && readable) TextButton(
                        enabled = !busy && exportPending == null,
                        onClick = {
                            try {
                                exportPending = file.exportIdentity
                                export.launch(file.name)
                            } catch (_: Exception) {
                                exportPending = null
                                notice = "无法打开文件保存窗口，请稍后重试；未修改原文件。"
                            }
                        },
                    ) { Text("导出文件") }
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("关闭") } },
        )
    }
}

private val OrbisCallFile.Workspace.exportIdentity: String
    get() = "$key:$sourceMessageId:$toolCallId"

/** Stateless entry for isolated UI tests; no repository, file, model or microphone access. */
@Composable
internal fun OrbisCallFileAttachmentsContent(files: List<OrbisCallFile>, onOpen: (OrbisCallFile) -> Unit) {
    if (files.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("orbis-call-files"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("本次通话的文件", style = MaterialTheme.typography.labelMedium)
        files.forEach { file -> key(file.key) {
            Surface(onClick = { onOpen(file) }, shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("orbis-call-file:${file.key}")) {
                Text("文件 · ${file.name}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } }
    }
}

private suspend fun exportCallWorkspaceFile(repository: WorkspaceRepository, file: OrbisCallFile.Workspace, output: OutputStream) {
    if (file.workspaceId != null) {
        // Historical binding, not the assistant's current workspace. Repository confines rootfs paths.
        repository.exportRootfsFile(file.workspaceId, file.path, output)
    } else {
        val written = file.writtenTextOrNull() ?: error("historical_file_source_unavailable")
        output.write(written.toByteArray(Charsets.UTF_8))
    }
}

/** Bound even a file that grows while being read, rather than trusting stale receipt size. */
private class CallPreviewOutput(private val target: OutputStream) : OutputStream() {
    private var count = 0
    override fun write(value: Int) {
        require(count < 2 * 1024 * 1024) { "preview_too_large" }
        target.write(value)
        count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= 2 * 1024 * 1024 - count) { "preview_too_large" }
        target.write(bytes, offset, length)
        count += length
    }
}
