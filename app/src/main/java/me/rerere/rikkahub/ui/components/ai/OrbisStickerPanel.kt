package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.orbisQuickEmotions
import me.rerere.rikkahub.data.model.OrbisStickerSendNotice
import me.rerere.rikkahub.data.orbis.AndroidStickerRepository
import me.rerere.rikkahub.data.orbis.OrbisSticker
import me.rerere.rikkahub.data.orbis.OrbisStickerLimits
import me.rerere.rikkahub.data.orbis.OrbisStickers
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** In chat, an explicit tile tap sends one image. The management-only entry never sends. */
@Composable
internal fun OrbisStickerPanel(
    onSendImage: (suspend (String) -> Boolean)? = null,
    onSendText: ((String) -> Boolean)? = null,
    sendEnabled: Boolean = true,
    onSendTextAsync: (suspend (String) -> Boolean)? = null,
) {
    val context = LocalContext.current.applicationContext
    var repository by remember(context) { mutableStateOf<AndroidStickerRepository?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, reloadAttempt) {
        loadFailed = false
        try {
            repository = withContext(Dispatchers.IO) { OrbisStickers.open(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    repository?.let { StickerLibrary(it, onSendImage, onSendText, sendEnabled, onSendTextAsync) } ?: Column(
        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StickerNote(if (loadFailed) "暂时无法读取本机表情库。请重试；本次没有发送内容。" else "正在读取本机表情库…")
        if (loadFailed) TextButton(onClick = { reloadAttempt++ }) { Text("重新读取") }
    }
}

@Composable
private fun StickerLibrary(repository: AndroidStickerRepository,
    onSendImage: (suspend (String) -> Boolean)?, onSendText: ((String) -> Boolean)?, sendEnabled: Boolean,
    onSendTextAsync: (suspend (String) -> Boolean)?) {
    val snapshot by repository.state.collectAsStateWithLifecycle()
    val writeBlocked by repository.writeBlocked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var textMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }

    fun sendImage(id: String) {
        if (busy || !sendEnabled || repository.writeBlocked.value || onSendImage == null) return
        busy = true // Set before launching so a second tap cannot start another send.
        notice = null
        scope.launch {
            try {
                notice = if (onSendImage(id)) "图片已发送，原草稿保持不变。"
                    else "当前正在回复、编辑或等待工具处理，未发送。结束后请重新点选；不会自动补发。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: OrbisStickerSendNotice) { notice = error.userMessage }
            catch (_: Exception) { notice = "图片暂未发送，请检查本机图片和模型设置后重试。草稿仍在。" }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (uri.scheme == "content") {
                // Keep only the granted URI for preview. Import happens on the separate Save action.
                pendingUri = uri
                editingId = null
                selectedId = null
                operationError = null
            } else notice = "无法使用这张图片，请通过系统图片选择器重新选择。"
        }
    }
    val visible = remember(snapshot.stickers, query) {
        val keyword = query.trim()
        snapshot.stickers.filter { item ->
            keyword.isEmpty() || item.id.contains(keyword, ignoreCase = true) ||
                item.tags.any { it.contains(keyword, ignoreCase = true) }
        }
    }
    val selected = snapshot.stickers.firstOrNull { it.id == selectedId }
    val editing = snapshot.stickers.firstOrNull { it.id == editingId }

    // One bounded lazy scroll surface: 1000 local stickers do not compose 1000 thumbnails at once.
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbisComposerTab("图片表情", selected = !textMode) { textMode = false }
                OrbisComposerTab("文字表情", selected = textMode) { textMode = true }
            }
        }
        notice?.let { message ->
            item(key = "notice", span = { GridItemSpan(maxLineSpan) }) { StickerNote(message) }
        }
        if (textMode) {
            item(key = "text-note", span = { GridItemSpan(maxLineSpan) }) {
                StickerNote(if (onSendText == null && onSendTextAsync == null) "这是管理入口；回聊天中点表情可发送。" else "点选立即发送一条文字表情；输入框草稿保持不变。")
            }
            items(orbisQuickEmotions, key = { "text-${it.id}" }) { item ->
                StickerTile(enabled = (onSendText != null || onSendTextAsync != null) && sendEnabled && !busy, onClick = {
                    if (onSendTextAsync == null) {
                        notice = if (onSendText?.invoke(item.draftText) == true) "文字表情已发送，原草稿保持不变。"
                            else "当前不能发送，请在回复或编辑结束后重新点选。不会自动补发。"
                    } else if (!busy && sendEnabled) {
                        busy = true
                        scope.launch {
                            try { notice = if (onSendTextAsync(item.draftText)) "文字表情已发送，原草稿保持不变。"
                                else "未确认发送成功，请先核对群记录；不会自动补发。" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = "未确认发送成功，请先核对记录；不会自动重试。" }
                            finally { busy = false }
                        }
                    }
                }) {
                    Text(item.glyph, fontSize = 25.sp, lineHeight = 30.sp)
                    Text(item.label, fontSize = 10.sp, lineHeight = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            item(key = "privacy", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    StickerNote(if (onSendImage != null) "点图片立即发送真实图片，草稿保留。模型需支持图片；管理不上传。"
                        else "本机表情管理。导入与编辑不上传；回聊天点选图片才会发送。")
                    if (onSendImage != null && !sendEnabled) StickerNote("当前不能发送；不会排队或自动补发。")
                }
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("搜索标签或编号", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    shape = RoundedCornerShape(12.dp))
            }
            item(key = "management", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${snapshot.stickers.size} 张 · ${if (managing) "点图片编辑标签" else if (onSendImage != null) "点图片立即发送" else "点图片预览"}",
                        modifier = Modifier.weight(1f), fontSize = 10.sp, lineHeight = 15.sp,
                        color = OrbisTheme.colors.mutedInk)
                    TextButton(onClick = { managing = !managing }, enabled = !busy && !writeBlocked) {
                        Text(if (managing) "完成管理" else "管理标签", fontSize = 11.sp)
                    }
                }
            }
            if (writeBlocked) {
                item(key = "blocked", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        StickerNote("表情库需要重新核验，暂时不能新增、修改或选择；不会自动覆盖本地内容。")
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    repository.reloadFromStorage()
                                    notice = if (repository.writeBlocked.value) "仍无法安全使用表情库，请保留现有数据后再检查。" else "已重新读取表情库。"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { notice = "重新读取失败，请保留现有数据后再检查。" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "正在读取…" else "重新读取") }
                    }
                }
            }
            item(key = "add") {
                StickerTile(enabled = !busy && !writeBlocked, onClick = {
                    operationError = null
                    notice = null
                    try { picker.launch(arrayOf("image/*")) }
                    catch (_: Exception) { notice = "暂时无法打开系统图片选择器，请稍后重试。" }
                }) {
                    Text("＋", fontSize = 27.sp, lineHeight = 32.sp, color = OrbisTheme.colors.onSand)
                    Text("添加", fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            items(visible, key = { it.id }) { sticker ->
                val file = rememberStickerFile(repository, sticker, writeBlocked)
                StickerTile(enabled = !busy && !writeBlocked && (managing || onSendImage == null || sendEnabled) && file != null, onClick = {
                    operationError = null
                    if (managing) editingId = sticker.id
                    else if (onSendImage != null) sendImage(sticker.id)
                    else selectedId = sticker.id
                }) {
                    StickerImage(file, sticker.tags.joinToString("、"), Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(sticker.id, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1)
                    Text(sticker.tags.firstOrNull().orEmpty(), fontSize = 10.sp, lineHeight = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    StickerNote(if (query.isBlank()) "点 ＋ 选择第一张图片，填写含义与适用场合后保存。" else "没有匹配的表情，试试其他标签或编号。")
                }
            }
            item(key = "backup", span = { GridItemSpan(maxLineSpan) }) {
                StickerNote("原表情库与标签尚未纳入备份 ZIP，也未接通换机同步；请保留原图与标签，暂勿卸载或清数据。点发送后的独立聊天图片可随包含聊天附件的备份恢复，但不会还原完整表情库。")
            }
        }
    }

    selected?.let { sticker ->
        val file = rememberStickerFile(repository, sticker, writeBlocked)
        StickerDialog(onDismiss = { selectedId = null }) {
            Text("表情预览 · ${sticker.id}", fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp)
            StickerImage(file, sticker.tags.joinToString("、"), Modifier.fillMaxWidth().height(180.dp))
            Text("含义与适用场合", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(sticker.tags.joinToString("、"), fontSize = 13.sp, lineHeight = 19.sp)
            StickerNote("发送会将真实图片交给当前模型，输入框里的文字与附件不会一起发出。")
            if (onSendImage != null) {
                Button(enabled = !busy && sendEnabled && !writeBlocked && file != null, modifier = Modifier.fillMaxWidth(), onClick = insert@ {
                    if (repository.writeBlocked.value) return@insert
                    sendImage(sticker.id)
                    selectedId = null
                }) { Text("立即发送图片") }
            } else StickerNote("这是管理入口；回聊天中点表情可发送。")
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = !writeBlocked, onClick = {
                selectedId = null
                editingId = sticker.id
                operationError = null
            }) { Text("编辑含义与标签") }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { selectedId = null }) { Text("返回表情库") }
        }
    }
    if (pendingUri != null || editing != null) {
        val source = pendingUri
        val existing = editing
        val existingFile = existing?.let { rememberStickerFile(repository, it, writeBlocked) }
        var tagText by remember(source, existing?.id) { mutableStateOf(existing?.tags?.joinToString("，").orEmpty()) }
        val tags = remember(tagText) { parseStickerEditorTags(tagText) }
        val validation = stickerEditorError(tags)
        StickerDialog(onDismiss = { if (!busy) { pendingUri = null; editingId = null; operationError = null } }, dismissible = !busy) {
            Text(if (existing == null) "添加图片表情" else "编辑表情标签", fontWeight = FontWeight.Bold,
                fontSize = 16.sp, lineHeight = 22.sp)
            StickerNote(existing?.let { "编号：${it.id} · 修改标签不改变图片或编号" } ?: "编号在保存成功后自动生成，不会覆盖已有表情。")
            StickerNote("支持 PNG、JPEG、GIF、WebP；单张最多 ${OrbisStickerLimits.MAX_IMAGE_BYTES / (1024 * 1024)} MB。")
            StickerImage(source ?: existingFile, "所选表情图片", Modifier.fillMaxWidth().height(160.dp))
            OutlinedTextField(value = tagText, onValueChange = { tagText = it; operationError = null },
                modifier = Modifier.fillMaxWidth(), enabled = !busy && !writeBlocked,
                label = { Text("含义与适用场合（必填）", fontSize = 12.sp) },
                placeholder = { Text("例如：抱抱，安慰，想你时", fontSize = 12.sp) },
                minLines = 2, maxLines = 5, isError = tagText.isNotBlank() && validation != null,
                shape = RoundedCornerShape(12.dp))
            StickerNote("逗号或换行分隔，最多 ${OrbisStickerLimits.MAX_TAGS} 项；每项 ${OrbisStickerLimits.MAX_TAG_LENGTH} 字，合计 ${OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH} 字。")
            validation?.let { StickerNote(it) }
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp) }
            if (writeBlocked) StickerNote("表情库当前不可写，请取消后重新读取。")
            StickerNote("点击保存才导入本机私有目录；不上传、不发送。当前备份 ZIP 尚不包含本表情库。")
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && !writeBlocked && validation == null,
                onClick = save@ {
                    if (busy || repository.writeBlocked.value) return@save
                    busy = true
                    operationError = null
                    scope.launch {
                        try {
                            val savedId = if (source != null) repository.importImage(source, tags).id
                                else {
                                    requireNotNull(existing)
                                    repository.updateTags(existing.id, tags)
                                    existing.id
                                }
                            pendingUri = null
                            editingId = null
                            selectedId = savedId
                            notice = "已保存在本机表情库，尚未发送。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { operationError = "保存失败，请检查图片和标签后重试；未发送任何内容。" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在保存…" else "保存到本机表情库") }
            TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                pendingUri = null; editingId = null; operationError = null
            }) { Text("取消") }
        }
    }
}

@Composable
private fun rememberStickerFile(repository: AndroidStickerRepository, sticker: OrbisSticker, blocked: Boolean): File? {
    val file by produceState<File?>(null, repository, sticker, blocked) {
        value = null
        if (!blocked) {
            try { value = withContext(Dispatchers.IO) { repository.fileForId(sticker.id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
    }
    return file
}

@Composable
private fun StickerTile(enabled: Boolean = true, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), color = OrbisTheme.colors.raisedPanel,
        border = BorderStroke(1.dp, OrbisTheme.colors.border.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically), content = content)
    }
}

@Composable
private fun StickerImage(model: Any?, description: String, modifier: Modifier) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (model != null && !failed) AsyncImage(model = model, contentDescription = description,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onError = { failed = true })
        else Text("图片不可用", fontSize = 10.sp, lineHeight = 14.sp, color = OrbisTheme.colors.mutedInk)
    }
}

@Composable
private fun StickerNote(text: String) {
    Text(text, fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
}

@Composable
private fun StickerDialog(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false,
    )) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(22.dp), color = OrbisTheme.colors.panel,
            contentColor = OrbisTheme.colors.ink) {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
