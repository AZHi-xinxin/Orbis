package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.ORBIS_MANUAL_CONTEXT_SUMMARY_MAX_CHARS
import me.rerere.rikkahub.data.model.OrbisManualContextPreview
import me.rerere.rikkahub.data.model.formatOrbisTokenCount
import kotlin.uuid.Uuid

/** Local, explicit rescue controls. Drafts and previews are never serialized into an Activity Bundle. */
@Composable
internal fun OrbisManualContextSheet(
    conversation: Conversation,
    busy: Boolean,
    preview: OrbisManualContextPreview?,
    error: String?,
    archiveId: Uuid?,
    onPreview: (Int, String) -> Unit,
    onApply: () -> Unit,
    onInvalidate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = conversation.messageNodes.size
    var endText by remember(conversation.id) { mutableStateOf(if (count == 0) "" else (count - 32).coerceAtLeast(1).toString()) }
    var summary by remember(conversation.id) { mutableStateOf("") }
    var summaryRejected by remember(conversation.id) { mutableStateOf(false) }
    var showList by remember(conversation.id) { mutableStateOf(false) }
    var requestedSource by remember(conversation.id) { mutableStateOf<Conversation?>(null) }
    var requestedDraft by remember(conversation.id) { mutableStateOf<Pair<Int, String>?>(null) }
    var confirm by remember(conversation.id) { mutableStateOf(false) }
    var applyInvoked by remember(conversation.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val end = endText.toIntOrNull()?.takeIf { it in 1..count }
    val editable = !busy && archiveId == null
    val currentPreview = preview?.takeIf {
        requestedSource === conversation && requestedDraft == (end to summary) &&
            it.conversationId == conversation.id && it.compactionEpoch == conversation.compactionEpoch &&
            it.requestedArchiveCount == end && it.requestedSummary == summary.trim() && !summaryRejected
    }
    fun invalidate() {
        requestedSource = null
        requestedDraft = null
        confirm = false
        applyInvoked = false
        onInvalidate()
    }
    fun dismiss() { if (!busy) onDismiss() }

    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(
            onDismissRequest = ::dismiss,
            containerColor = colors.panel,
            contentColor = colors.ink,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetGesturesEnabled = !busy,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy, shouldDismissOnClickOutside = !busy),
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded,
                enabledValues = if (busy) setOf(SheetValue.Expanded) else setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("人工整理上下文", Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = ::dismiss, enabled = !busy, modifier = Modifier.testTag("orbis-manual-close")) {
                        Icon(HugeIcons.Cancel01, "关闭人工整理")
                    }
                }
                OrbisScrollablePanel(Modifier.weight(1f), windowInsets = WindowInsets(0, 0, 0, 0),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    if (archiveId != null) {
                        ManualContextCard {
                            Text("整理完成，原文已留存", fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("orbis-manual-success"))
                            Text("聊天列表中已有独立的“原文存档”窗口，保存了整理前全部消息、分支和附件引用。当前窗口保留整理说明、你选填的摘要，以及范围之后的原文。")
                            Text("没有请求模型、自动发送新消息或推进旧队列。返回后检查当前上下文，再由你决定何时发送。")
                        }
                    } else {
                        ManualContextCard {
                            Text("先保留原文，再给当前窗口腾出空间", fontWeight = FontWeight.Bold)
                            Text("这是归档，不是永久删除。提交时先完整保存一个独立的“原文存档”聊天（包括全部分支和附件引用），再把当前窗口开头的一段移出活动上下文。")
                            Text("本机操作，不调用模型，不自动发送，不推进旧队列。原文存档可从聊天列表打开，但它本身仍可能超出模型容量。",
                                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                        }
                        ManualContextCard {
                            Text("1 · 选择归档范围", fontWeight = FontWeight.Bold)
                            Text("当前共 $count 条。按消息排列顺序，从最早第 1 条到你选的第 N 条；人类、AI 和工具消息都计数。默认建议保留末尾 32 条，可自行调整。")
                            OutlinedTextField(value = endText, onValueChange = {
                                if (editable && it != endText) { endText = it; invalidate() }
                            }, enabled = editable && count > 0, singleLine = true,
                                label = { Text("归档至第 N 条（含这一条）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = end == null,
                                supportingText = { Text(if (count == 0) "当前没有可整理的消息。" else if (end == null)
                                    "请输入 1 到 $count 的整数。" else "所选：第 1—$end 条；其后保留 ${count - end} 条原文。") },
                                modifier = Modifier.fillMaxWidth().testTag("orbis-manual-end"))
                            TextButton(onClick = { showList = !showList }, enabled = editable && count > 0,
                                modifier = Modifier.testTag("orbis-manual-toggle-list")) {
                                Text(if (showList) "收起消息简表" else "从消息简表选择结束位置")
                            }
                            if (showList) {
                                val listState = rememberLazyListState(initialFirstVisibleItemIndex = ((end ?: 1) - 3).coerceAtLeast(0))
                                LazyColumn(Modifier.fillMaxWidth().height(240.dp).testTag("orbis-manual-message-list"),
                                    state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(count = count, key = { conversation.messageNodes[it].id.toString() }) { index ->
                                        val selected = end == index + 1
                                        Surface(color = if (selected) colors.sand else colors.page,
                                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                                                .testTag("orbis-manual-select-${index + 1}")
                                                .clickable(enabled = editable, role = Role.Button) {
                                                    val chosen = (index + 1).toString()
                                                    if (chosen != endText) { endText = chosen; invalidate() }
                                                }) {
                                            ManualMessageBoundary("${if (selected) "已选 · " else ""}第 ${index + 1} 条",
                                                conversation.messageNodes[index], Modifier.padding(10.dp))
                                        }
                                    }
                                }
                                Text("简表只显示当前所选分支的短摘；存档会保留全部分支。不会加载图片或执行历史工具。",
                                    style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                            }
                            end?.let { selected ->
                                ManualMessageBoundary("所选起点 · 第 1 条", conversation.messageNodes.first())
                                ManualMessageBoundary("所选终点 · 第 $selected 条", conversation.messageNodes[selected - 1])
                                if (selected < count) ManualMessageBoundary("所选保留起点 · 第 ${selected + 1} 条", conversation.messageNodes[selected])
                                else Text("你选择归档全部原文；当前窗口将只保留人工整理说明及选填摘要。", color = colors.onSand)
                            }
                        }
                        ManualContextCard {
                            Text("2 · 给留下的上下文写一段说明（可不填）", fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = summary, onValueChange = { value ->
                                if (editable) {
                                    summaryRejected = value.length > ORBIS_MANUAL_CONTEXT_SUMMARY_MAX_CHARS
                                    if (!summaryRejected) summary = value
                                    invalidate()
                                }
                            }, enabled = editable, minLines = 3, maxLines = 6,
                                label = { Text("人类手写摘要 / 需要保留的线索") },
                                supportingText = { Text(if (summaryRejected) "输入超过 20000 字符，本次输入未采用；请减少后重新填写。" else
                                    "${summary.length} / 20000 字符。不填时只记录归档事实，不会冒充 AI 写过摘要。") },
                                isError = summaryRejected, modifier = Modifier.fillMaxWidth().testTag("orbis-manual-summary"))
                            Text("这段内容会明确标为人类提供。不会自动概括、补写或猜测被移出的记忆；原文仍在独立存档里。",
                                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                        }
                        if (error != null) Text(error, color = colors.onSand, modifier = Modifier.testTag("orbis-manual-error"))
                        if (currentPreview != null) ManualContextCard {
                            Text("3 · 整理结果预览", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("orbis-manual-preview-ready"))
                            Text("实际移出前 ${currentPreview.archivedCount} 条，保留 ${currentPreview.keptCount} 条原文，并添加 1 条人工整理说明。")
                            Text("本地估算：${formatOrbisTokenCount(currentPreview.beforeTokens)} → ${formatOrbisTokenCount(currentPreview.afterTokens)} tokens。")
                            if (currentPreview.afterTokens >= currentPreview.beforeTokens) Surface(
                                color = colors.sand, contentColor = colors.onSand, shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().testTag("orbis-manual-not-smaller"),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("注意：这次没有减小上下文", fontWeight = FontWeight.Bold)
                                    Text("若要降低模型占用，请扩大归档范围，或缩短 / 留空摘要，然后重新预览。你仍可选择继续整理，但本次不能当作已经解决容量超限。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (currentPreview.additionalProtocolMessages > 0) Text(
                                "为保留完整的工具调用与结果，实际归档范围已缩小：比你选的范围多保留 ${currentPreview.additionalProtocolMessages} 条。", color = colors.onSand)
                            if (currentPreview.archivedCount in 1..count) ManualMessageBoundary(
                                "实际归档终点 · 第 ${currentPreview.archivedCount} 条", conversation.messageNodes[currentPreview.archivedCount - 1])
                            if (currentPreview.archivedCount in 0 until count) ManualMessageBoundary(
                                "实际保留起点 · 第 ${currentPreview.archivedCount + 1} 条", conversation.messageNodes[currentPreview.archivedCount])
                            Text("整理说明预览：\n${currentPreview.summaryText.take(600)}${if (currentPreview.summaryText.length > 600) "\n…这里只显示开头，提交会保存完整已填写内容。" else ""}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("估算不等于供应商精确计数，也不保证一定低于模型容量；系统提示、工具、附件和模型输出预留都会占空间。请留足余量。",
                                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                        } else Text("先预览，再确认。修改范围或摘要、窗口记录发生变化后，需要重新预览。工具协议需要完整保留，最终可归档范围可能缩小。",
                            style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("本机正在处理，请先不要关闭或切换。", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("orbis-manual-busy"))
                    }
                    if (archiveId != null) Button(onClick = ::dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("完成，回到聊天") }
                    else {
                        OutlinedButton(onClick = {
                            if (editable && end != null && !summaryRejected) {
                                focus.clearFocus(); confirm = false; applyInvoked = false
                                requestedSource = conversation; requestedDraft = end to summary
                                onPreview(end, summary)
                            }
                        }, enabled = editable && end != null && !summaryRejected,
                            modifier = Modifier.fillMaxWidth().testTag("orbis-manual-preview")) { Text("预览整理结果") }
                        Button(onClick = { if (editable && currentPreview != null && !applyInvoked) { focus.clearFocus(); confirm = true } },
                            enabled = editable && currentPreview != null && !applyInvoked,
                            modifier = Modifier.fillMaxWidth().testTag("orbis-manual-apply")) { Text("确认整理范围…") }
                    }
                }
            }
        }
        if (confirm && currentPreview != null && editable && !applyInvoked) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("保留原文存档，并整理当前窗口？") },
            text = { Text("将完整复制整理前的聊天到独立的“原文存档”，然后仅从当前窗口移出前 ${currentPreview.archivedCount} 条，保留 ${currentPreview.keptCount} 条原文和人工整理说明。\n\n这不是永久删除，不请求模型，也不会自动发送。请确认范围无误。" +
                if (currentPreview.afterTokens >= currentPreview.beforeTokens) "\n\n注意：此次估算占用没有下降，不能当作已解决模型容量超限。" else "") },
            confirmButton = { TextButton(onClick = {
                if (!busy && !applyInvoked) { applyInvoked = true; confirm = false; onApply() }
            }, modifier = Modifier.testTag("orbis-manual-confirm")) { Text("保留原文存档并整理") } },
            dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("orbis-manual-cancel-confirm")) { Text("返回检查") } },
        )
    }
}

@Composable
private fun ManualContextCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = OrbisTheme.colors
    Surface(Modifier.fillMaxWidth(), color = colors.page, contentColor = colors.ink,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ManualMessageBoundary(label: String, node: MessageNode, modifier: Modifier = Modifier) {
    val message = node.messages.getOrNull(node.selectIndex)
    val role = when (message?.role) { MessageRole.USER -> "人类"; MessageRole.ASSISTANT -> "AI"; MessageRole.TOOL -> "工具"; MessageRole.SYSTEM -> "系统"; null -> "无有效消息" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label · $role", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
        Text("${message?.createdAt?.toString()?.replace('T', ' ')?.take(19) ?: "时间未知"} · ${node.messages.size} 个分支",
            style = MaterialTheme.typography.labelSmall, color = OrbisTheme.colors.mutedInk)
        Text(manualContextMessageSnippet(node), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Bound both inspected parts and characters before any whitespace processing or layout. */
internal fun manualContextMessageSnippet(node: MessageNode): String {
    val message = node.messages.getOrNull(node.selectIndex)?.privateRoomSafePresentation() ?: return "[无有效的所选分支]"
    val short = buildString {
        for (part in message.parts.take(8)) {
            if (part is UIMessagePart.Text) {
                if (length > 0) append(' ')
                append(part.text.take((120 - length).coerceAtLeast(0)))
                if (length >= 120) break
            }
        }
    }.replace(Regex("\\s+"), " ").trim()
    return short.ifBlank { "[工具、思考或附件消息；简表不加载正文]" }
}
