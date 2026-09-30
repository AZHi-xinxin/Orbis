package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.rikkahub.data.orbis.integration.*
import java.io.File

private val hubPreviewTransferSlots = Semaphore(2)
private data class HubPendingSave(val file: File, val attachment: HubAttachment, val revision: Long)

/** A file picker only stages local bytes. It never sends on selection. */
@Composable
internal fun HubMediaPicker(enabled: Boolean, active: Boolean, files: HubMediaFiles,
    onPicked: (HubUploadAttachment) -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Activity results can arrive at STARTED, before RESUMED. Staging local
        // bytes is allowed then; only the explicit send performs network I/O.
        if (uri == null || !enabled || uri.scheme != "content") return@rememberLauncherForActivityResult
        preparing = true
        job = scope.launch {
            var staged: HubUploadAttachment? = null
            try {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "file"
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    // Publish ownership before the cancellable IO->Main resume,
                    // so cancellation there still deletes this exact payload.
                    staged = files.stage(name, mime) { resolver.openInputStream(uri) ?: error("missing_input") }
                }
                ensureActive()
                onPicked(requireNotNull(staged)); staged = null
                onStatus("附件已选好；按发送才会上传到当前房间。")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onStatus("无法准备附件。图片最多 10 MiB、文件最多 30 MiB；请检查文件名、访问权限及空间。")
            } finally {
                staged?.let { withContext(Dispatchers.IO + NonCancellable) { runCatching { files.discard(it) } } }
                preparing = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled && active && !preparing) {
            if (preparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("＋")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("选择图片（10 MiB）") }, onClick = { expanded = false; picker.launch(arrayOf("image/*")) })
            DropdownMenuItem(text = { Text("选择文件（30 MiB）") }, onClick = { expanded = false; picker.launch(arrayOf("*/*")) })
        }
    }
}

internal suspend fun hubPreviewBitmap(file: File, mime: String): Bitmap? = withContext(Dispatchers.IO) {
    if (!file.isFile || file.length() !in 1..HUB_IMAGE_LIMIT || hubPreviewImageSample(mime, 1, 1) == null)
        return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val sample = hubPreviewImageSample(mime, bounds.outWidth, bounds.outHeight) ?: return@withContext null
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Already decoded, bounded local pixels only; this view cannot fetch a URL. */
@Composable
internal fun HubAttachmentImagePreview(bitmap: Bitmap, filename: String, modifier: Modifier = Modifier) {
    Image(bitmap.asImageBitmap(), "附件图片：$filename", modifier.fillMaxWidth().heightIn(max = 330.dp),
        contentScale = ContentScale.Fit)
}

/** Visible small raster previews use only listed, fixed same-origin IDs. Saving
 * and larger/file transfers still require the user's explicit action. */
@Composable
internal fun HubAttachmentCard(attachment: HubAttachment, store: OrbisConnectionStore,
    client: OrbisTechHubClient, active: Boolean, visible: Boolean,
    previewCache: HubPreviewPageCache, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cached by remember { mutableStateOf<File?>(null) }
    var cachedMime by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(-1) }
    var job by remember { mutableStateOf<Job?>(null) }
    val cacheOwner = remember { HubPreviewFileOwner() }
    val saveOwner = remember { HubPreviewFileOwner() }
    var pendingSave by remember { mutableStateOf<HubPendingSave?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingSave ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                if (uri == null || uri.scheme != "content") return@launch
                check(store.state.value.available && store.state.value.revision == pending.revision)
                withContext(Dispatchers.IO) {
                    pending.file.inputStream().use { input ->
                        (context.contentResolver.openOutputStream(uri, "w") ?: error("missing_output")).use { output ->
                            val coroutine = kotlin.coroutines.coroutineContext
                            copyHubMediaExact(input, output, pending.attachment.size) { coroutine.ensureActive() }
                        }
                    }
                }
                onStatus("附件已保存到你选择的位置。")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onStatus("保存未完成；目标位置可能有不完整文件，请检查后重试。") }
            finally { withContext(Dispatchers.IO + NonCancellable) { saveOwner.clear() }; pendingSave = null }
        }
    }
    fun releasePreview(keep: Boolean) {
        cacheOwner.take()?.let { file ->
            val mime = cachedMime
            if (keep && mime != null && hubMayAutoPreview(attachment))
                previewCache.put(HubCachedPreview(attachment, file, mime))
            else file.delete()
        }
        cached = null; cachedMime = null; bitmap = null; text = null; open = false
    }
    val releaseOnDispose by rememberUpdatedState<() -> Unit>({ releasePreview(active) })
    DisposableEffect(cacheOwner) { onDispose { job?.cancel(); releaseOnDispose(); saveOwner.clear() } }
    DisposableEffect(active, visible) {
        if (!active || !visible) {
            job?.cancel(); releasePreview(active)
        }
        onDispose { }
    }

    fun launchSave() {
        if (pendingSave != null) return
        val file = cached ?: return
        if (!store.state.value.available || store.state.value.revision != revision) {
            cacheOwner.clear(); cached = null; bitmap = null; text = null
            error = hubAttachmentReadFailure(HubAttachmentReadStage.CREDENTIAL, OrbisTechHubException("configuration_changed"))
            return
        }
        // A system document picker backgrounds the activity. Transfer ownership
        // BEFORE launch so clearing the invisible preview cannot delete its source.
        val taken = cacheOwner.take() ?: return
        if (taken != file) { taken.delete(); cached = null; bitmap = null; text = null; return }
        saveOwner.publish(file)
        pendingSave = HubPendingSave(file, attachment, revision)
        cached = null; bitmap = null; text = null
        try { save.launch(attachment.filename) }
        catch (_: Exception) { saveOwner.clear(); pendingSave = null; onStatus("系统保存窗口未能打开，请重新查看附件后再保存。") }
    }

    fun fetch(download: Boolean, automatic: Boolean = false) {
        if (!active || !visible || busy || pendingSave != null || !store.state.value.available) return
        if (cached != null) {
            if (store.state.value.revision != revision) {
                cacheOwner.clear(); cached = null; bitmap = null; text = null
                error = hubAttachmentReadFailure(HubAttachmentReadStage.CREDENTIAL, OrbisTechHubException("configuration_changed"))
            } else { if (download) launchSave() else if (!automatic) open = true }
            return
        }
        open = !download && !automatic; busy = true; error = null
        job = scope.launch {
            var owned: File? = null
            var stage = HubAttachmentReadStage.CREDENTIAL
            try {
                suspend fun transfer() {
                    val credential = store.readCredential() ?: error("disconnected")
                    revision = credential.revision
                    stage = HubAttachmentReadStage.CACHE
                    val reused = previewCache.take(attachment)
                    if (reused != null) owned = reused.file
                    else withContext(Dispatchers.IO) { owned = createHubPreviewFile(context.cacheDir) }
                    val file = requireNotNull(owned)
                    stage = HubAttachmentReadStage.TRANSFER
                    val mime = reused?.mime ?: client.download(credential, attachment, file)
                    ensureActive()
                    if (!store.state.value.available || store.state.value.revision != revision)
                        throw OrbisTechHubException("configuration_changed")
                    if (!download) {
                        stage = HubAttachmentReadStage.PREVIEW
                        if (automatic && hubPreviewImageSample(mime, 1, 1) == null)
                            throw OrbisTechHubException("invalid_response")
                        bitmap = hubPreviewBitmap(file, mime)
                        if (automatic && bitmap == null) throw OrbisTechHubException("preview_unavailable")
                        text = withContext(Dispatchers.IO) { hubPreviewText(file, mime) }
                    }
                    ensureActive()
                    if (bitmap != null) previewCache.succeeded(attachment)
                    cacheOwner.publish(file)
                    cached = file; cachedMime = mime; owned = null
                    if (download) launchSave()
                }
                hubPreviewTransferSlots.withPermit { transfer() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = hubAttachmentReadFailure(stage, failure)
                if (!automatic) onStatus(error!!)
            } finally { owned?.delete(); busy = false }
        }
    }

    LaunchedEffect(active, visible, attachment.id, busy) {
        if (active && visible && store.state.value.available && pendingSave == null && cached == null && !busy &&
            (previewCache.contains(attachment) || previewCache.claim(attachment))) fetch(false, automatic = true)
    }

    Column(Modifier.fillMaxWidth()) {
        Text("${if (attachment.kind == "image") "图片" else "文件"} · ${attachment.filename}",
            style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        // Only locally decoded bounded pixels are displayed, never an image URL.
        bitmap?.let { image ->
            HubAttachmentImagePreview(image, attachment.filename,
                Modifier.heightIn(max = 180.dp).clickable(enabled = active && !busy) { fetch(false) })
        }
        Row {
            Text("${(attachment.size / 1024f).toInt().coerceAtLeast(1)} KiB", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { fetch(false) }, enabled = active && !busy && pendingSave == null) { Text(if (error == null) "查看" else "重试查看") }
            TextButton(onClick = { fetch(true) }, enabled = active && !busy && pendingSave == null) { Text("下载 / 保存") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
        }
        if (!open) error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (open) AlertDialog(onDismissRequest = { open = false; job?.cancel() },
        title = { Text(attachment.filename, maxLines = 2) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                bitmap?.let { HubAttachmentImagePreview(it, attachment.filename) }
                text?.let { body ->
                    SelectionContainer { Text(body.take(12000), style = MaterialTheme.typography.bodySmall) }
                    if (body.length > 12000) Text("预览显示前 12,000 字；复制按钮包含本文件全文。", style = MaterialTheme.typography.labelSmall)
                }
                if (!busy && cached != null && bitmap == null && text == null)
                    Text("此格式或大小不适合手机内预览。可完整保存到本地；文本预览和复制限 128 KiB UTF-8 文件。")
            }
        },
        confirmButton = { TextButton(onClick = { launchSave() },
            enabled = cached != null && !busy) { Text("保存到本地") } },
        dismissButton = { Row {
            if (text != null) TextButton(onClick = {
                if (!store.state.value.available || store.state.value.revision != revision) {
                    cacheOwner.clear(); cached = null; bitmap = null; text = null; open = false
                    return@TextButton
                }
                runCatching { clipboard.setText(AnnotatedString(requireNotNull(text))) }
                    .onSuccess { onStatus("已复制附件全文。") }.onFailure { onStatus("复制未完成，请在预览中手动选择。") }
            }) { Text("复制全文") }
            TextButton(onClick = { open = false; job?.cancel() }) { Text("关闭") }
        } })
}
