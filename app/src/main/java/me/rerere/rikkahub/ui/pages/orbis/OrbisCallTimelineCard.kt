package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.voiceCallTranscriptView
import me.rerere.rikkahub.data.orbis.voice.archiveAuthorLabel
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject

/** Loads only the visible call, scopes it to this conversation, and never sends a model request on open. */
@Composable
internal fun OrbisCallTimelineCard(
    callId: String, conversationId: String, modifier: Modifier = Modifier,
    sourceFallback: (@Composable () -> Unit)? = null,
    sourceDetails: (@Composable () -> Unit)? = null,
) {
    val service = koinInject<ChatService>()
    val repository = service.voiceCalls
    val revision by repository.revision.collectAsStateWithLifecycle()
    var record by remember(callId, conversationId) { mutableStateOf<OrbisVoiceCallRecord?>(null) }
    var readFailed by remember(callId, conversationId) { mutableStateOf(false) }
    var showHistory by rememberSaveable(callId) { mutableStateOf(false) }
    var confirmRetry by rememberSaveable(callId) { mutableStateOf(false) }
    var retryRequested by remember(callId) { mutableStateOf(false) }
    var showSourceDetails by rememberSaveable(callId) { mutableStateOf(false) }
    LaunchedEffect(callId, conversationId, revision) {
        try {
            record = repository.get(callId)?.takeIf { it.conversationId == conversationId }
            readFailed = record == null
            retryRequested = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { record = null; readFailed = true }
    }
    OrbisCallTimelineCardContent(record, readFailed, retryRequested, modifier,
        onDetails = { showHistory = true }, onRetry = { confirmRetry = true }, sourceFallback = sourceFallback,
        onSourceDetails = sourceDetails?.let { { showSourceDetails = true } })
    if (showSourceDetails && sourceDetails != null) Dialog(
        onDismissRequest = { showSourceDetails = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(.95f), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("本次通话的原始消息与工具", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showSourceDetails = false }) { Text("关闭") }
                }
                Text("保留原消息与工具结果；打开这里不会重新执行工具。", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) { sourceDetails() }
            }
        }
    }
    if (showHistory && record != null) OrbisVoiceCallHistorySheet(repository,
        assistantId = record?.assistantId, initialCallId = callId,
        onRetry = service::retryVoiceCallArchiveIsolated, onDismiss = { showHistory = false })
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false },
        title = { Text("重新归档这一次通话？") },
        text = { Text("优先请当前助手本人总结本次已保存的原文；失败时才用已配置的外部兜底。异常结束时，本助手10秒没有开始有效总结才转兜底。可能产生模型费用，但不会恢复旧队列、执行工具或打开麦克风；未完成的原文始终保留，也可请本助手之后补写。") },
        confirmButton = { TextButton(enabled = !retryRequested, onClick = {
            confirmRetry = false; retryRequested = true; service.retryVoiceCallArchiveIsolated(callId)
        }) { Text("确认归档") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("取消") } })
}

/** Stateless so an isolated UI test cannot accidentally touch a real transcript, microphone or model. */
@Composable
internal fun OrbisCallTimelineCardContent(
    record: OrbisVoiceCallRecord?, readFailed: Boolean = false, retryRequested: Boolean = false,
    modifier: Modifier = Modifier, onDetails: () -> Unit = {}, onRetry: () -> Unit = {},
    sourceFallback: (@Composable () -> Unit)? = null,
    onSourceDetails: (() -> Unit)? = null,
) {
    val transcriptView = remember(record) { record?.let(::voiceCallTranscriptView) }
    if ((readFailed || transcriptView?.sourceUnavailable == true) && sourceFallback != null) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.fillMaxWidth().testTag("orbis-call-timeline-source-fallback"),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text("独立通话记录暂不可读取，聊天中的原始消息仍保留；可查看原始消息与工具，不会自动归档或恢复队列。",
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            sourceFallback()
            if (onSourceDetails != null) TextButton(onClick = onSourceDetails,
                modifier = Modifier.testTag("orbis-call-source-details")) { Text("原始消息与工具") }
        }
        return
    }
    var expanded by rememberSaveable(record?.id) { mutableStateOf(false) }
    val active = record?.status in setOf(OrbisVoiceCallStatus.ACTIVE, OrbisVoiceCallStatus.CONNECTING)
    val title = when {
        record == null -> if (readFailed) "通话记录暂不可读取" else "正在读取通话记录…"
        active -> "正在通话中"
        else -> {
            val seconds = record.durationMs?.coerceAtLeast(0)?.div(1000)
            val duration = seconds?.let { "通话时长 ${it / 60}分${it % 60}秒" } ?: "通话已结束 · 时长未确定"
            duration + if (record.status == OrbisVoiceCallStatus.INTERRUPTED) "（异常终止）" else ""
        }
    }
    val status = when {
        transcriptView?.sourceUnavailable == true -> "部分记录暂不可读取 · 原文件保留 · 暂不归档"
        active -> "转写已折叠 · 可继续浏览聊天与设置"
        record?.archiveStatus == OrbisVoiceArchiveStatus.READY -> "已归档 · 原文保留"
        record?.archiveStatus == OrbisVoiceArchiveStatus.GENERATING -> "正在整理摘要 · 原文保留"
        record != null -> "未归档 · 原文保留 · 可重试"
        else -> "不会自动重试模型或恢复旧队列"
    }
    Surface(modifier.fillMaxWidth().testTag("orbis-call-timeline-card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .68f),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().clickable(enabled = record != null, role = Role.Button,
                onClickLabel = if (expanded) "收起通话摘要" else "展开通话摘要") { expanded = !expanded }
                .testTag("orbis-call-timeline-toggle")) {
                Text("◌  $title", style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded && record != null) {
                if (record.summary != null) Text(record.archiveAuthorLabel(), style = MaterialTheme.typography.labelSmall)
                val excerpt = record.summary?.takeIf { it.isNotBlank() } ?: transcriptView?.entries.orEmpty()
                    .filter { it.role.equals("user", true) || it.role.equals("assistant", true) }
                    .let { if (it.size <= 2) it else listOf(it.first(), it.last()) }
                    .joinToString("\n…\n") { "${if (it.role.equals("user", true)) "我" else "AI"}：${it.content.take(160)}" }
                    .ifBlank { "暂未产生文字，通话状态已保存。" }
                Text(excerpt.take(2000), style = MaterialTheme.typography.bodyMedium)
                Text("这里只折叠显示，不裁剪模型上下文，也不删除逐条原文。", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = record != null, onClick = onDetails) { Text("查看完整记录") }
                if (record != null && transcriptView?.sourceUnavailable != true && !active && record.connectedAtMs != null &&
                    record.archiveStatus in setOf(OrbisVoiceArchiveStatus.PENDING, OrbisVoiceArchiveStatus.FAILED)) {
                    TextButton(enabled = !retryRequested, onClick = onRetry) { Text(if (retryRequested) "已请求归档…" else "重新归档") }
                }
            }
            if (onSourceDetails != null) TextButton(onClick = onSourceDetails,
                modifier = Modifier.testTag("orbis-call-source-details")) { Text("原始消息与工具") }
        }
    }
}
