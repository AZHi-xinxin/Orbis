package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.service.QueueRecoveryPhase
import me.rerere.rikkahub.service.QueueRecoveryState
import me.rerere.rikkahub.ui.hooks.ChatInputState

@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onResume: () -> Unit,
    onStopGatewayWait: () -> Unit = {},
    gatewayStopNotice: String? = null,
    recovery: QueueRecoveryState = QueueRecoveryState(),
    onDismissRecoveryResult: (QueueRecoveryState) -> Unit = {},
) {
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    var confirmResume by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var showManagement by remember { mutableStateOf(false) }
    var rawMessage by remember { mutableStateOf<QueuedMessage?>(null) }
    val needsRecovery = state.paused
    val recovering = recovery.isRunning
    val unresolvedResult = recovery.phase == QueueRecoveryPhase.PENDING ||
        recovery.phase == QueueRecoveryPhase.FAILURE
    // Old success is presentation, not a reason to keep a card over the conversation.
    // A voice-only pause or historical receipt is not a paused chat queue.
    val resultMessage = recovery.message.takeUnless { needsRecovery && recovery.phase == QueueRecoveryPhase.SUCCESS }
    fun closeManagement() {
        showManagement = false
        if (!recovery.isRunning && recovery.phase != QueueRecoveryPhase.IDLE)
            onDismissRecoveryResult(recovery) // Consume presentation only; never release a safety hold.
    }
    if (state.messages.isNotEmpty() || needsRecovery) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag("chat_message_queue"),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        recovering -> "正在核对连接"
                        state.paused -> "消息已暂停 · ${state.messages.size} 条待发"
                        state.messages.isNotEmpty() && state.messages.all { it.recoveryHeldReason != null } ->
                            "保留待核对 · ${state.messages.size}"
                        state.messages.isNotEmpty() -> "待发消息 · ${state.messages.size}"
                        else -> "连接待核对"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                TextButton(onClick = { showManagement = true },
                    modifier = Modifier.testTag("chat_queue_manage")) { Text("管理") }
            }
        }
    }
    if (showManagement) AlertDialog(
        onDismissRequest = ::closeManagement,
        title = { Text("待发消息与暂停处理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这里是尚未发送的内容，不是历史聊天。关闭此窗口不会发送消息、结束通话或解除安全暂停。",
                    style = MaterialTheme.typography.bodySmall)
                if (!resultMessage.isNullOrBlank()) Text(resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("chat_queue_recovery_result"))
                if (state.messages.any { it.isEditing }) Text("请先保存或取消正在编辑的待发消息。",
                    style = MaterialTheme.typography.bodySmall)
                if (state.paused || unresolvedResult) {
                    TextButton(onClick = { confirmResume = true },
                        enabled = !recovering && state.messages.none { it.isEditing },
                        modifier = Modifier.testTag("chat_queue_recover")) {
                        Text(if (recovering) "正在核对…" else "检查后续消息")
                    }
                }
                if (needsRecovery || unresolvedResult || gatewayStopNotice != null) {
                    TextButton(onClick = { confirmStop = true }, enabled = !recovering,
                        modifier = Modifier.testTag("chat_queue_stop_gateway")) {
                        Text("停止旧轮并核对连接")
                    }
                    gatewayStopNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("chat_gateway_stop_notice")) }
                }
                if (state.messages.isEmpty()) Text("当前没有待发消息。",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(
                        state.messages,
                        key = { _, message -> message.id }) { index, message ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val attachmentLabels = listOf(
                                stringResource(R.string.chat_page_queue_image),
                                stringResource(R.string.chat_page_queue_file),
                                stringResource(R.string.chat_page_queue_audio),
                                stringResource(R.string.chat_page_queue_video),
                            )
                            Text(
                                text = "${index + 1}. " + (queuedCallLabel(message) ?: message.parts.joinToString(" ") {
                                    when (it) {
                                        is UIMessagePart.Text -> it.text
                                        is UIMessagePart.Image -> attachmentLabels[0]
                                        is UIMessagePart.Document -> attachmentLabels[1]
                                        is UIMessagePart.Audio -> attachmentLabels[2]
                                        is UIMessagePart.Video -> attachmentLabels[3]
                                        else -> ""
                                    }
                                }) + if (message.recoveryHeldReason != null) " · 保留待核对" else "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (queuedMessageCanBecomeHumanInput(message)) TextButton(
                                enabled = !message.isEditing && !recovering,
                                onClick = { editing = onBeginEdit(message.id) },
                            ) { Text(if (message.isEditing) stringResource(R.string.chat_page_queue_editing)
                                else if (message.recoveryHeldReason != null) "编辑为新消息" else stringResource(R.string.edit)) }
                            else TextButton(onClick = { rawMessage = message }) { Text("原文") }
                            TextButton(
                                enabled = !message.isEditing && !recovering,
                                onClick = { onRemove(message.id) },
                            ) { Text(stringResource(R.string.chat_page_queue_remove)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::closeManagement,
            modifier = Modifier.testTag("chat_queue_close_management")) { Text("关闭") } },
    )

    if (confirmResume) AlertDialog(onDismissRequest = { confirmResume = false },
        title = { Text("继续后续排队消息？") },
        text = { Text("先检查当前回复、工具和连接状态。确认安全后才继续尚未发送的普通消息，可能调用模型；不会重做旧工具或自动恢复通话收音。仍有未解决状态时保持暂停。") },
        confirmButton = { TextButton(onClick = { confirmResume = false; onResume() },
            enabled = !recovering && state.messages.none { it.isEditing },
            modifier = Modifier.testTag("chat_queue_confirm_resume")) { Text("检查并继续") } },
        dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("取消") } })

    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text("停止旧轮并核对？") },
        text = { Text("先停止本地生成，再向同一模型服务核对本次运行记录的精确请求。仅支持此接口的网关会在确认旧轮已收尾后结束等待，不会重发消息或工具，也不代表已取消外部工具的实际操作。队列仍保留，需你另外确认恢复。") },
        confirmButton = { TextButton(onClick = { confirmStop = false; onStopGatewayWait() },
            enabled = !recovering) { Text("停止并核对") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } })

    rawMessage?.let { message -> AlertDialog(onDismissRequest = { rawMessage = null },
        title = { Text(queuedCallLabel(message) ?: "待发消息原文") },
        text = { Text(message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            .take(16_384), modifier = Modifier.heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()).testTag("chat_queue_raw_message")) },
        confirmButton = { TextButton(onClick = { rawMessage = null }) { Text("关闭") } }) }

    editing?.let { message ->
        val input = remember(message.id) {
            ChatInputState().apply {
                editingMessage = message.id
                setContents(message.parts)
            }
        }
        val finishEdit by rememberUpdatedState(onFinishEdit)
        // Leaving the page or dismissing the dialog must release the queue item.
        DisposableEffect(message.id) {
            onDispose { finishEdit(message.id, null) }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (message.recoveryHeldReason != null) "编辑为新消息"
                else stringResource(R.string.chat_page_queue_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.recoveryHeldReason != null) Text(
                        "旧内容不会自动发送。确认后把这里的内容作为一条新输入发送，不会补跑旧工具或通话控制。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("chat_queue_fresh_input_notice"),
                    )
                    if (input.messageContent.isNotEmpty()) MediaFileInputRow(input)
                    TextField(
                        state = input.textContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_queue_edit_text"),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 3,
                            maxHeightInLines = 8
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !input.isEmpty(),
                    onClick = {
                        onFinishEdit(message.id, input.getContents())
                        editing = null
                    },
                ) { Text(if (message.recoveryHeldReason != null)
                    "作为新消息发送" else stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Editing cannot turn an automatic event or internal call control into fresh human permission. */
internal fun queuedMessageCanBecomeHumanInput(message: QueuedMessage): Boolean =
    message.orbisEventId == null &&
        ((message.voiceCallId == null && message.voiceCallKind == null) ||
            (message.voiceCallId != null && message.voiceCallKind == "turn"))

/** Use host metadata, not guesses from user text, to label internal call envelopes. */
internal fun queuedCallLabel(message: QueuedMessage): String? = if (message.voiceCallId == null) null else
    when (message.voiceCallKind) {
        "turn" -> "未发送的通话文字"
        "begin" -> "通话开始记录"
        "end" -> "通话结束记录"
        "opening" -> "通话开场记录"
        "visual" -> "视频画面更新"
        else -> "待核对的通话记录"
    }
