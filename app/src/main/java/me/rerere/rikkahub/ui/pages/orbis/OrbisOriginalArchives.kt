package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveInfo
import me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveStats
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.originalArchiveChunks
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation
import me.rerere.rikkahub.data.repository.OrbisCompactionRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/** Human-only retention and read-only local viewer. No AI requests, tool execution or remote images. */
@Composable
internal fun OrbisOriginalArchives(owner: Uuid, onDismiss: () -> Unit) {
    val repository: OrbisCompactionRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsState()
    val assistant = settings.assistants.firstOrNull { it.id == owner }
    val scope = rememberCoroutineScope()
    var offset by remember(owner) { mutableIntStateOf(0) }
    var revision by remember(owner) { mutableIntStateOf(0) }
    var records by remember(owner) { mutableStateOf(emptyList<OrbisOriginalArchiveInfo>()) }
    var stats by remember(owner) { mutableStateOf(OrbisOriginalArchiveStats(0, 0)) }
    var selected by remember(owner) { mutableStateOf(emptySet<String>()) }
    var reading by remember(owner) { mutableStateOf<OrbisOriginalArchiveInfo?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var loading by remember(owner) { mutableStateOf(true) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var confirmEnable by remember(owner) { mutableStateOf(false) }
    var deletion by remember(owner) { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block(); revision++ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "操作未完成，请重试。原有数据不会因读取失败而删除。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner, offset, revision) {
        loading = true; selected = emptySet(); records = emptyList()
        try {
            stats = repository.originalArchiveStats(owner)
            records = repository.originalArchives(owner, offset)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "暂时无法读取档案。" }
        finally { loading = false }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("可选原文档案", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("为 ${assistant?.name ?: "当前助手"} 保留后续压缩原文", Modifier.weight(1f))
                    Switch(checked = assistant?.retainCompactionOriginals == true,
                        enabled = !busy && assistant != null && !settings.init,
                        onCheckedChange = { enabled ->
                            if (enabled) confirmEnable = true
                            else perform { settingsStore.updateOriginalArchiveRetention(owner, false) }
                        })
                }
                Text("默认关闭，仅由你开启。关闭后不再新增；已有档案可删除，不补存以前的聊天，也不发送给 AI。最近一次压缩撤销点独立保留。", style = MaterialTheme.typography.bodySmall)
                Text("${stats.count} 份 · 原文数据 ${archiveSize(stats.bytes)}（不含附件和数据库开销）", style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { selected = records.map { it.eventId }.toSet() }, enabled = !loading && !busy) { Text("选中本页") }
                    TextButton(onClick = { deletion = selected to false }, enabled = selected.isNotEmpty() && !busy) { Text("删除选中 (${selected.size})") }
                    TextButton(onClick = { deletion = emptySet<String>() to true }, enabled = stats.count > 0 && !busy) { Text("清空此助手") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (records.isEmpty() && !loading) Text("本页没有原文档案。关闭保留时，这是正常状态。")
                    records.forEach { record ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Row {
                                    Checkbox(record.eventId in selected, { checked -> selected = if (checked) selected + record.eventId else selected - record.eventId }, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(record.windowTitle.ifBlank { "未命名窗口" })
                                        Text(archiveDate(record.createdAt), style = MaterialTheme.typography.bodySmall)
                                        Text("${record.nodeCount} 组消息 · ${archiveSize(record.byteSize)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row {
                                    TextButton(onClick = { reading = record }, enabled = !busy) { Text("只读查看") }
                                    TextButton(onClick = { deletion = setOf(record.eventId) to false }, enabled = !busy) { Text("删除此份") }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { offset = (offset - 30).coerceAtLeast(0) }, enabled = offset > 0 && !loading && !busy) { Text("上一页") }
                    Text("第 ${offset / 30 + 1} 页")
                    TextButton(onClick = { offset += 30 }, enabled = offset + records.size < stats.count && !loading && !busy) { Text("下一页") }
                }
            }
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("开启后续原文保留？") },
        text = { Text("此助手下一次及以后的 AI 压缩会额外保存完整原文，占用本机存储。可以随时关闭、单份删除或批量清理；不会补回已经丢弃的旧原文。") },
        confirmButton = { TextButton(onClick = { confirmEnable = false; perform { settingsStore.updateOriginalArchiveRetention(owner, true) } }) { Text("由我开启") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("保持关闭") } })
    deletion?.let { (ids, all) ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text(if (all) "清空此助手的原文档案？" else "删除 ${ids.size} 份原文档案？") },
            text = { Text("不可撤销；不删除当前聊天、摘要或最近一次撤销点。数据库空间先供后续数据复用，文件大小不一定立即缩小。已有外部备份不受影响。") },
            confirmButton = { TextButton(onClick = { deletion = null; perform { repository.deleteOriginalArchives(owner, ids, all); offset = 0; selected = emptySet() } }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
    reading?.let { record -> OriginalArchiveReader(owner, record, repository) { reading = null } }
}

@Composable
private fun OriginalArchiveReader(owner: Uuid, record: OrbisOriginalArchiveInfo, repository: OrbisCompactionRepository, onDismiss: () -> Unit) {
    var nodeIndex by remember(record.eventId) { mutableIntStateOf(0) }
    var textPage by remember(record.eventId, nodeIndex) { mutableIntStateOf(0) }
    var pages by remember(record.eventId, nodeIndex) { mutableStateOf(emptyList<String>()) }
    var failure by remember(record.eventId, nodeIndex) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(nodeIndex, textPage) { scroll.scrollTo(0) }
    LaunchedEffect(owner, record.eventId, nodeIndex) {
        try {
            val node = repository.originalArchiveNode(owner, record.eventId, nodeIndex)
            pages = withContext(Dispatchers.Default) { originalArchiveChunks(originalArchiveDisplay(node)) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = true }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.97f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原文只读 · ${nodeIndex + 1}/${record.nodeCount}", style = MaterialTheme.typography.titleLarge)
                Text("包含此组的各条分支；工具仅作为文字，不会执行。隐私室记录沿用原来的展示保护。", style = MaterialTheme.typography.bodySmall)
                if (failure) Text("档案已删除或暂时无法读取。")
                else if (pages.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                SelectionContainer(Modifier.weight(1f).verticalScroll(scroll)) {
                    Text(pages.getOrNull(textPage).orEmpty(), Modifier.fillMaxWidth())
                }
                if (pages.size > 1) Row {
                    TextButton(onClick = { textPage-- }, enabled = textPage > 0) { Text("上段") }
                    Text("长文 ${textPage + 1}/${pages.size}")
                    TextButton(onClick = { textPage++ }, enabled = textPage + 1 < pages.size) { Text("下段") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { nodeIndex-- }, enabled = nodeIndex > 0) { Text("上一组") }
                    TextButton(onClick = onDismiss) { Text("关闭原文") }
                    TextButton(onClick = { nodeIndex++ }, enabled = nodeIndex + 1 < record.nodeCount) { Text("下一组") }
                }
            }
        }
    }
}

internal fun originalArchiveDisplay(node: MessageNode): String = node.messages.mapIndexed { index, source ->
    val message = source.privateRoomSafePresentation()
    "分支 ${index + 1}/${node.messages.size}${if (index == node.selectIndex) " · 当时选中" else ""} · ${message.role}\n\n" +
        message.parts.joinToString("\n\n") { part -> when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> "[思考]\n${part.reasoning}"
            else -> JsonInstant.encodeToString(part)
        } }
}.joinToString("\n\n──────────\n\n")

private fun archiveSize(bytes: Long) = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)
private fun archiveDate(time: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
