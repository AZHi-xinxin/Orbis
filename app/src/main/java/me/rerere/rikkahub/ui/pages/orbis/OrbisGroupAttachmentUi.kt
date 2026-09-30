package me.rerere.rikkahub.ui.pages.orbis

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.group.*
import me.rerere.rikkahub.ui.components.ai.OrbisStickerPanel
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage

@Composable
internal fun GroupAttachmentComposer(
    text: String, onText: (String) -> Unit, attachments: List<OrbisGroupAttachment>,
    onAttachments: (List<OrbisGroupAttachment>) -> Unit, settings: Settings, appearance: OrbisAppearance,
    enabled: Boolean, inputEnabled: Boolean, running: Boolean, sendEnabled: Boolean,
    placeholder: String, onSend: suspend (String, List<OrbisGroupAttachment>, Boolean) -> Boolean, onStop: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { OrbisGroupAttachments(context) }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestAttachments by rememberUpdatedState(attachments)
    val latestEnabled by rememberUpdatedState(inputEnabled)
    val latestSend by rememberUpdatedState(onSend)
    suspend fun submit(value: String, items: List<OrbisGroupAttachment>, preserveDraft: Boolean = false): Boolean {
        if (submitting || !enabled || !inputEnabled || running) return false
        submitting = true
        return try {
            latestSend(value, items, preserveDraft).also { accepted ->
                if (!accepted) error = "本次发送未确认；草稿和附件仍保留，请先核对群记录。不会自动补发。"
            }
        } finally { submitting = false }
    }
    fun importFiles(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty() || importing || !latestEnabled) return
        if (uris.size + latestAttachments.size > GROUP_ATTACHMENT_LIMIT) { error = "每条最多 4 个附件。"; return }
        importing = true; error = null
        scope.launch {
            try {
                val items = uris.map { store.import(it, imageRequested = images) }
                if (latestEnabled) {
                    val combined = latestAttachments + items
                    validateGroupAttachments(combined)
                    onAttachments(combined)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? OrbisGroupException)?.message ?: "这个附件暂时无法读取，原文件没有改动。" }
            finally { importing = false }
        }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFiles(it, true) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFiles(it, false) }
    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall)
    OrbisCommunityComposer(
        value = text, onValueChange = onText, onSend = { scope.launch { submit(text, attachments) } },
        enabled = enabled && inputEnabled && !importing && !submitting, sendEnabled = sendEnabled && !running,
        placeholder = placeholder, appearance = appearance, sending = running, onStop = onStop,
        attachmentActions = {
            Box {
                IconButton(onClick = { menu = true }, enabled = inputEnabled && !importing) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("图片 / GIF") }, onClick = { menu = false; images.launch(arrayOf("image/*")) })
                    DropdownMenuItem(text = { Text("文件") }, onClick = { menu = false; files.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("表情包 / 表情") }, onClick = { menu = false; stickers = true })
                }
            }
        }, pendingContent = {
            if (attachments.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(attachments, key = { it.id }) { item ->
                    InputChip(selected = false, onClick = {}, label = { Text(item.name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp)) },
                        trailingIcon = { IconButton(onClick = { onAttachments(attachments.filterNot { it.id == item.id }) },
                            modifier = Modifier.size(32.dp), enabled = !importing && enabled) { Text("×") } })
                }
            }
            if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        },
    )
    if (stickers) AlertDialog(onDismissRequest = { stickers = false }, title = { Text("表情包") }, text = {
        OrbisStickerPanel(sendEnabled = enabled && inputEnabled && !running && !importing && !submitting,
            onSendTextAsync = { value -> submit(value, emptyList(), preserveDraft = true).also { if (it) stickers = false } },
            onSendImage = { id ->
                if (!enabled || running) false else try {
                    val item = store.importSticker(id)
                    submit("", listOf(item), preserveDraft = true).also { accepted ->
                        if (accepted) stickers = false
                        // Original library entry and unrelated draft stay intact on failure.
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "未能确认表情包发送；请先核对群记录和本机表情，未自动重试。"; false }
            })
    }, confirmButton = { TextButton(onClick = { stickers = false }) { Text("关闭") } })
}

@Composable
internal fun GroupAttachmentView(attachment: OrbisGroupAttachment) {
    val context = LocalContext.current
    val store = remember(context) { OrbisGroupAttachments(context) }
    val scope = rememberCoroutineScope()
    var file by remember(attachment.id) { mutableStateOf<java.io.File?>(null) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(attachment.id) {
        file = withContext(Dispatchers.IO) { runCatching { store.file(attachment) }.getOrNull() }
        if (file == null) error = "本机附件暂不可读取；消息记录仍保留，没有重新上传。"
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mime)) { target ->
        if (target != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                val original = store.file(attachment)
                context.contentResolver.openOutputStream(target)?.use { output -> original.inputStream().use { copyGroupAttachment(it, output) } }
                    ?: throw IllegalStateException("destination_unavailable")
            }; error = "已保存到你选择的位置。" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存附件；群聊原件仍保留。" }
        }
    }
    Column {
        if (attachment.image && file != null) ZoomableAsyncImage(model = file?.let { Uri.fromFile(it).toString() }, contentDescription = attachment.name,
            modifier = Modifier.heightIn(min = 72.dp, max = 240.dp).widthIn(max = 300.dp))
        Text(attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!attachment.image && attachment.extractedText == null) Text("原文件 · 未解析文字", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", store.file(attachment))
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开附件"))
                } catch (_: Exception) { error = "暂时无法打开，可先保存附件。" }
            }, enabled = file != null) { Text("打开") }
            TextButton(onClick = { save.launch(attachment.name) }, enabled = file != null) { Text("保存") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
