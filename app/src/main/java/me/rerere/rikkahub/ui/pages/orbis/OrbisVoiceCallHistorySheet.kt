package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallProtocol
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.archiveErrorForDisplay
import me.rerere.rikkahub.data.orbis.voice.archiveAuthorLabel
import me.rerere.rikkahub.data.orbis.voice.voiceCallTranscriptView

/** Local archive browsing only. Opening text never sends it to a conversation or model. */
@Composable
fun OrbisVoiceCallHistorySheet(
    repository: OrbisVoiceCallRepository,
    assistantId: String? = null,
    initialCallId: String? = null,
    onRetry: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val revision by repository.revision.collectAsStateWithLifecycle()
    var page by rememberSaveable(assistantId) { mutableStateOf(0) }
    var selectedId by rememberSaveable(assistantId, initialCallId) { mutableStateOf(initialCallId) }
    var records by remember(repository, assistantId) { mutableStateOf<List<OrbisVoiceCallRecord>>(emptyList()) }
    var selected by remember(repository, assistantId) { mutableStateOf<OrbisVoiceCallRecord?>(null) }
    var loading by remember { mutableStateOf(true) }
    var readFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var retryRequestedFor by remember { mutableStateOf<String?>(null) }
    var retryError by remember { mutableStateOf(false) }

    LaunchedEffect(repository, assistantId, revision, page, selectedId, refresh) {
        loading = true
        readFailed = false
        try {
            if (selectedId == null) {
                records = repository.list(assistantId = assistantId, limit = HISTORY_PAGE_SIZE, offset = page * HISTORY_PAGE_SIZE)
                selected = null
            } else {
                selected = repository.get(checkNotNull(selectedId))?.takeIf { assistantId == null || it.assistantId == assistantId }
                if (selected == null) readFailed = true
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { readFailed = true }
        finally { loading = false }
    }
    LaunchedEffect(selected?.archiveStatus, selected?.archiveError, selected?.error, revision) {
        // A durable state change acknowledges retry dispatch; the archive state now owns its UI.
        if (selected?.archiveStatus == OrbisVoiceArchiveStatus.GENERATING ||
            selected?.archiveStatus == OrbisVoiceArchiveStatus.READY || selected?.archiveStatus == OrbisVoiceArchiveStatus.FAILED) {
            retryRequestedFor = null
        }
    }
    BackHandler(enabled = selectedId != null) { selectedId = null; retryError = false }

    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = colors.panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("通话记录", fontSize = 17.sp, lineHeight = 23.sp, color = colors.ink,
                            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text(if (assistantId == null) "本机记录" else "与当前 AI 的通话 · 所有窗口",
                            fontSize = 11.sp, lineHeight = 16.sp, color = colors.mutedInk)
                    }
                    IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭通话记录", tint = colors.mutedInk) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selectedId != null) TextButton(onClick = { selectedId = null; retryError = false }) { Text("返回列表") }
                    TextButton(onClick = { retryRequestedFor = null; refresh++ }, enabled = !loading) { Text("刷新") }
                }
                if (readFailed) {
                    Text("暂时无法读取通话记录。原文件保留，可以刷新重试。",
                        Modifier.padding(18.dp), color = colors.mutedInk, fontSize = 13.sp, lineHeight = 20.sp)
                } else if (loading && (selectedId != null && selected?.id != selectedId || selectedId == null && records.isEmpty())) {
                    Text("正在读取通话记录…", Modifier.padding(18.dp), color = colors.mutedInk, fontSize = 13.sp)
                } else if (selectedId != null) {
                    selected?.takeIf { it.id == selectedId }?.let { record ->
                        VoiceCallHistoryDetail(record, Modifier.weight(1f),
                            retryRequested = retryRequestedFor == record.id, retryError = retryError,
                            onRetry = {
                                retryRequestedFor = record.id
                                retryError = false
                                try { onRetry(record.id) }
                                catch (_: Exception) { retryError = true; retryRequestedFor = null }
                            })
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (records.isEmpty()) item {
                            Text(if (page == 0) "还没有通话记录。" else "这一页没有更多记录。",
                                color = colors.mutedInk, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
                        }
                        items(records, key = { it.id }) { record ->
                            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button,
                                onClickLabel = "查看通话记录") { selected = null; selectedId = record.id; retryError = false },
                                color = colors.raisedPanel, shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, colors.border)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(voiceCallDate(record.connectedAtMs ?: record.startedAtMs), fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold, color = colors.ink)
                                    Text(voiceCallDurationLabel(record) + " · " + voiceCallArchiveLabel(record),
                                        fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                                    record.summary?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = colors.ink,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("上一页") }
                                TextButton(onClick = { page++ }, enabled = records.size == HISTORY_PAGE_SIZE && !loading) { Text("下一页") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallHistoryDetail(
    record: OrbisVoiceCallRecord,
    modifier: Modifier,
    retryRequested: Boolean,
    retryError: Boolean,
    onRetry: () -> Unit,
) {
    val colors = OrbisTheme.colors
    var showActual by rememberSaveable(record.id) { mutableStateOf(false) }
    var confirmArchive by rememberSaveable(record.id) { mutableStateOf(false) }
    val closed = record.status == OrbisVoiceCallStatus.ENDED || record.status == OrbisVoiceCallStatus.INTERRUPTED
    val transcriptView = remember(record) { voiceCallTranscriptView(record) }
    val actualTranscript = transcriptView.entries
    val canRetry = !transcriptView.sourceUnavailable && closed && record.connectedAtMs != null && (
        record.archiveStatus == OrbisVoiceArchiveStatus.FAILED || record.archiveStatus == OrbisVoiceArchiveStatus.PENDING)
    LazyColumn(modifier, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(voiceCallDate(record.connectedAtMs ?: record.startedAtMs), color = colors.ink,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(voiceCallDurationLabel(record), color = colors.mutedInk, fontSize = 12.sp)
                SelectionContainer { Text("记录 ID：${record.id}", color = colors.mutedInk, fontSize = 11.sp) }
                Text(if (transcriptView.sourceUnavailable) "部分记录暂不可读取 · 原文件保留" else voiceCallArchiveLabel(record),
                    color = colors.mutedInk, fontSize = 12.sp)
                if (transcriptView.sourceUnavailable) Text(
                    "通话源记录格式异常，以下只显示仍可读取的已保存内容。为避免遗漏，暂不发起归档；原文件没有被修改，请先备份并检查记录。",
                    color = colors.mutedInk, fontSize = 12.sp, lineHeight = 18.sp)
                record.endReason?.let { Text("结束方式：$it", color = colors.mutedInk, fontSize = 12.sp) }
                record.endReasonText?.let { Text("结束原因：$it", color = colors.mutedInk, fontSize = 12.sp) }
                record.endError?.let { Text("通话中断详情：$it", color = colors.mutedInk, fontSize = 12.sp) }
                record.archiveErrorForDisplay()?.let {
                    SelectionContainer { Text("归档未完成原因：$it", color = colors.mutedInk, fontSize = 12.sp) }
                }
                if (canRetry) TextButton(onClick = { confirmArchive = true }, enabled = !retryRequested) {
                    Text(when {
                        retryRequested -> "已请求重新整理…"
                        else -> "重新归档"
                    })
                }
                if (canRetry) Text(
                    "可单独整理这一通已保存的原文。不会恢复旧队列、重发旧消息或执行工具；原文始终保留。",
                    color = colors.mutedInk, fontSize = 11.sp, lineHeight = 17.sp)
                if (retryError) Text("暂时未能开始整理，请稍后重试。",
                    color = colors.mutedInk, fontSize = 12.sp)
            }
        }
        item {
            Text("通话摘要", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(record.summary?.takeIf { it.isNotBlank() } ?: "摘要尚未完成，已保存的实际记录仍可查看。",
                    color = colors.ink, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            HorizontalDivider(color = colors.border)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showActual, onClick = { showActual = false }, label = { Text("整理后的记录") })
                FilterChip(selected = showActual, onClick = { showActual = true }, label = { Text("实际逐条记录") })
            }
        }
        if (showActual) {
            if (actualTranscript.isEmpty()) item { Text("尚无已保存的逐条记录。", color = colors.mutedInk, fontSize = 13.sp) }
            items(actualTranscript, key = { it.id }) { entry ->
                var showOriginalAsr by rememberSaveable(record.id, entry.id) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(voiceCallRole(entry.role) + " · " + voiceCallTime(entry.timestampMs), color = colors.mutedInk,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(entry.content, color = colors.ink, fontSize = 13.sp, lineHeight = 21.sp) }
                    entry.originalTranscript?.takeIf { it != entry.content }?.let { original ->
                        TextButton(onClick = { showOriginalAsr = !showOriginalAsr }) {
                            Text(if (showOriginalAsr) "收起原识别" else "称呼已纠正 · 查看原识别")
                        }
                        if (showOriginalAsr) SelectionContainer {
                            Text(original, color = colors.mutedInk, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        } else item {
            if (record.summary != null) Text(record.archiveAuthorLabel(), color = colors.mutedInk, fontSize = 11.sp)
            SelectionContainer {
                Text(record.modelTranscript?.takeIf { it.isNotBlank() } ?: "本助手尚未完成整理，可切换查看实际逐条记录；之后也可请本助手通过通话工具补写。",
                    color = colors.ink, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("仅整理这一次通话？") },
        text = { Text("优先请当前助手本人整理本次已保存的通话文字，保持其身份且不提供工具。异常结束时若10秒内没有开始有效总结，或本人整理失败，才使用你明确配置的外部兜底模型；一旦开始有效内容，不以10秒强行截断总结。可能产生模型费用。不会继续旧队列、执行历史工具或开麦；都未完成时保留原文，可稍后让本助手补写。") },
        confirmButton = { TextButton(onClick = { confirmArchive = false; onRetry() }) { Text("确认整理") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("取消") } },
    )
}

private const val HISTORY_PAGE_SIZE = 50

private fun voiceCallDate(timeMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs))
private fun voiceCallTime(timeMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMs))

private fun voiceCallDurationLabel(record: OrbisVoiceCallRecord): String = when (record.status) {
    OrbisVoiceCallStatus.CONNECTING -> "正在连接"
    OrbisVoiceCallStatus.ACTIVE -> "通话中"
    OrbisVoiceCallStatus.INTERRUPTED -> OrbisVoiceCallProtocol.title(record.durationMs).removeSurrounding("【", "】") + "（异常终止）"
    OrbisVoiceCallStatus.ENDED -> OrbisVoiceCallProtocol.title(record.durationMs).removeSurrounding("【", "】")
}

private fun voiceCallArchiveLabel(record: OrbisVoiceCallRecord): String = when (record.archiveStatus) {
    OrbisVoiceArchiveStatus.READY -> "已归档 · 原文保留"
    OrbisVoiceArchiveStatus.GENERATING -> "AI 正在整理"
    OrbisVoiceArchiveStatus.FAILED -> "整理未完成 · 原文保留"
    OrbisVoiceArchiveStatus.PENDING -> if (record.status == OrbisVoiceCallStatus.ACTIVE || record.status == OrbisVoiceCallStatus.CONNECTING)
        "记录中" else "等待 AI 整理"
}

private fun voiceCallRole(role: String): String = when (role.lowercase()) {
    "user" -> "我"
    "assistant" -> "AI"
    "system" -> "通话提示"
    "tool" -> "工具结果"
    else -> role
}
