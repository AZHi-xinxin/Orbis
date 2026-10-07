package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.OrbisKaomoji
import me.rerere.rikkahub.data.orbis.OrbisKaomojiRepository
import me.rerere.rikkahub.data.orbis.OrbisKaomojis

/** Selection is explicit. The host chooses draft insertion or standalone sending; management never sends. */
@Composable
internal fun OrbisKaomojiPanel(onInsertText: ((String) -> Unit)? = null, enabled: Boolean = true,
    onSendText: (suspend (String) -> Boolean)? = null) {
    val context = LocalContext.current.applicationContext
    var repository by remember(context) { mutableStateOf<OrbisKaomojiRepository?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, attempt) {
        failed = false
        try { repository = withContext(Dispatchers.IO) { OrbisKaomojis.open(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val loaded = repository
    if (loaded != null) KaomojiLibrary(loaded, onInsertText, enabled, onSendText)
    else Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(if (failed) "暂时无法读取颜文字库；未改动原文件。" else "正在读取本机颜文字库…")
        if (failed) TextButton(onClick = { attempt++ }) { Text("重新读取") }
    }
}

@Composable
private fun KaomojiLibrary(repository: OrbisKaomojiRepository, onInsertText: ((String) -> Unit)?, enabled: Boolean,
    onSendText: (suspend (String) -> Boolean)?) {
    val snapshot by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OrbisKaomoji?>(null) }
    var deleting by remember { mutableStateOf<OrbisKaomoji?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val visible = remember(snapshot.entries, query) {
        val keyword = query.trim()
        snapshot.entries.filter { entry -> keyword.isEmpty() || entry.label.contains(keyword, true) ||
            entry.text.contains(keyword, true) || entry.tags.any { it.contains(keyword, true) } }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(135.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("颜文字 · 本机共用库", style = MaterialTheme.typography.titleSmall)
                Text(when {
                    onSendText != null -> "点选立即发送一条颜文字，输入框草稿保持不变。"
                    onInsertText != null -> "点选加入输入框，确认后再发送。"
                    else -> "管理与你的 AI 共用的文字表情。"
                }, style = MaterialTheme.typography.bodySmall)
                if (!enabled && (onSendText != null || onInsertText != null)) Text("当前不能发送；不会排队或自动补发。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = { creating = true }) { Text("新增") }
                    TextButton(enabled = !busy, onClick = { managing = !managing }) { Text(if (managing) "完成管理" else "管理") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { repository.reload() }
                                notice = "已重新读取。"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = "重新读取失败，未覆盖原文件。" }
                            finally { busy = false }
                        }
                    }) { Text("重新读取") }
                }
                OutlinedTextField(value = query, onValueChange = { if (it.length <= 100) query = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("搜索名称、标签或颜文字") }, singleLine = true)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (visible.isEmpty()) Text("还没有匹配的颜文字，可以新增。", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(visible, key = { it.id }) { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    TextButton(onClick = {
                        if (busy || !enabled) return@TextButton
                        if (onSendText == null) {
                            onInsertText?.invoke(item.text); notice = "已加入输入框，尚未发送。"
                        } else {
                            busy = true; notice = null
                            scope.launch {
                                try { notice = if (onSendText(item.text)) "颜文字已发送，原草稿保持不变。"
                                    else "未确认发送成功，请核对聊天记录；不会自动补发。" }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { notice = "未确认发送成功，请核对聊天记录；不会自动重试。" }
                                finally { busy = false }
                            }
                        }
                    },
                        enabled = enabled && !busy && (onInsertText != null || onSendText != null) && !managing,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(item.text, maxLines = 7, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                    }
                    Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (managing || (onInsertText == null && onSendText == null)) Row {
                        TextButton(enabled = !busy, onClick = { editing = item }) { Text("编辑") }
                        TextButton(enabled = !busy, onClick = { deleting = item }) { Text("删除") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) KaomojiEditor(repository, editing, onDismiss = { creating = false; editing = null },
        onSaved = { notice = "已保存到共用库，没有发送消息。" })
    deleting?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除这个颜文字？") },
            text = { Text("${candidate.label}\n${candidate.text}\n只从本机共用库删除，不修改已经发出的消息。") },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.delete(candidate.id, candidate.revision) }
                        notice = "已从共用库删除，原聊天记录不变。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = kaomojiUiError(error) }
                    finally { deleting = null; busy = false }
                }
            }) { Text("删除") } })
    }
}

@Composable
private fun KaomojiEditor(repository: OrbisKaomojiRepository, original: OrbisKaomoji?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var label by remember(original) { mutableStateOf(original?.label.orEmpty()) }
    var text by remember(original) { mutableStateOf(original?.text.orEmpty()) }
    var tags by remember(original) { mutableStateOf(original?.tags?.joinToString("，").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (original == null) "新增颜文字" else "编辑颜文字") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只保存文字，不读取聊天。与 AI 的颜文字工具共用同一份库。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = label, onValueChange = { if (OrbisKaomojiRepository.characterCount(it) <= 40) label = it }, label = { Text("名称") }, singleLine = true, enabled = !busy)
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("颜文字正文 · 支持换行和 emoji") },
                    minLines = 3, maxLines = 10, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = OrbisKaomojiRepository.characterCount(text) > OrbisKaomojiRepository.MAX_TEXT_CHARACTERS,
                    supportingText = { Text("${OrbisKaomojiRepository.characterCount(text)} / ${OrbisKaomojiRepository.MAX_TEXT_CHARACTERS} 字符（按 Unicode 字符计数，不按字节）") })
                OutlinedTextField(value = tags, onValueChange = { if (it.length <= 200) tags = it }, label = { Text("标签，用逗号分隔（最多 8 个）") }, singleLine = true, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy && label.isNotBlank() && text.isNotBlank() &&
            OrbisKaomojiRepository.characterCount(text) <= OrbisKaomojiRepository.MAX_TEXT_CHARACTERS, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val parsedTags = tags.split(',', '，').map(String::trim).filter(String::isNotEmpty)
                    withContext(Dispatchers.IO) {
                        if (original == null) repository.add(label, text, parsedTags)
                        else repository.update(original.id, original.revision, label, text, parsedTags)
                    }
                    onSaved(); onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = kaomojiUiError(failure) }
                finally { busy = false }
            }
        }) { Text(if (busy) "保存中…" else "保存") } })
}

private fun kaomojiUiError(error: Exception): String = when (error.message) {
    "kaomoji_revision_conflict", "kaomoji_not_found" -> "条目已被修改或删除。请关闭编辑框，重新读取后再编辑。"
    "kaomoji_invalid_text", "kaomoji_text_not_emoji" -> "名称最多 40 字，正文最多 ${OrbisKaomojiRepository.MAX_TEXT_CHARACTERS} 个 Unicode 字符，支持多行与 emoji；不能全为空白或含异常控制字符。"
    "kaomoji_invalid_tags" -> "最多 8 个标签，每个标签最多 20 字。"
    "kaomoji_duplicate_text" -> "库中已有这个颜文字，请编辑已有条目。"
    "kaomoji_library_full" -> "颜文字已达 500 个，请先整理现有条目。"
    else -> "没有确认保存成功；请先重新读取并核对，避免重复操作。原聊天未改动。"
}
