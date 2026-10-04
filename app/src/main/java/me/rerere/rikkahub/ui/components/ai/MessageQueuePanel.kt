package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
) {
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    var confirmResume by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    if (state.messages.isNotEmpty() || state.paused) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_message_queue"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (state.paused) R.string.chat_page_queue_paused_count
                            else R.string.chat_page_queue_pending_count,
                            state.messages.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
                    if (state.paused) {
                        TextButton(onClick = { confirmResume = true }) { Text("检查后续消息") }
                    }
                }
                if (state.paused) Text(if (state.messages.isEmpty())
                    "当前没有排队待发的消息。暂停标记用于防止中断后自动继续，不表示聊天丢失。若窗口打不开，请到「数据与本地备份 → 会话自助恢复」检查。"
                else "这里是尚未发送的输入，不是历史聊天。暂停可防止回复中断或工具结果待核对时自动续发；你可以编辑、移除，或确认后继续。此按钮不修复聊天数据库。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                if (state.paused) {
                    TextButton(onClick = { confirmStop = true }) { Text("停止旧轮并核对网关") }
                    gatewayStopNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("chat_gateway_stop_notice")) }
                }
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
                                text = "${index + 1}. " + message.parts.joinToString(" ") {
                                    when (it) {
                                        is UIMessagePart.Text -> it.text
                                        is UIMessagePart.Image -> attachmentLabels[0]
                                        is UIMessagePart.Document -> attachmentLabels[1]
                                        is UIMessagePart.Audio -> attachmentLabels[2]
                                        is UIMessagePart.Video -> attachmentLabels[3]
                                        else -> ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { editing = onBeginEdit(message.id) },
                            ) { Text(if (message.isEditing) stringResource(R.string.chat_page_queue_editing) else stringResource(R.string.edit)) }
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { onRemove(message.id) },
                            ) { Text(stringResource(R.string.chat_page_queue_remove)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text("停止旧轮并核对？") },
        text = { Text("先停止本地生成，再向同一模型服务核对本次运行记录的精确请求。仅支持此接口的网关会在确认旧轮已收尾后结束等待，不会重发消息或工具，也不代表已取消外部工具的实际操作。队列仍保留，需你另外确认恢复。") },
        confirmButton = { TextButton(onClick = { confirmStop = false; onStopGatewayWait() }) { Text("停止并核对") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } })

    if (confirmResume) AlertDialog(onDismissRequest = { confirmResume = false },
        title = { Text("恢复后续排队消息？") },
        text = { Text("仅检查是否可以继续尚未发送的排队消息；有待发消息时，继续可能调用模型。不会重做上一条失败或结果未知的工具，也不会修复损坏的聊天。仍有未解决状态时保持暂停。窗口打不开请到「数据与本地备份 → 会话自助恢复」；通话重新归档在通话卡片中操作。") },
        confirmButton = { TextButton(onClick = { confirmResume = false; onResume() }) { Text("恢复后续消息") } },
        dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("取消") } })

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
            title = { Text(stringResource(R.string.chat_page_queue_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
