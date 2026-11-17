package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.pages.chat.ManualSummaryController

@Composable
internal fun OrbisManualSummarySheet(conversation: Conversation, controller: ManualSummaryController, onDismiss: () -> Unit) {
    var through by remember(conversation.id) { mutableStateOf((conversation.messageNodes.size - 32).coerceAtLeast(1).toString()) }
    var count by remember(conversation.id) { mutableStateOf("") }
    var prompt by remember(conversation.id) { mutableStateOf("") }
    var concurrency by remember { mutableIntStateOf(3) }
    var output by remember { mutableIntStateOf(8192) }
    var selected by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val draft = controller.draft
    val busy = controller.busy
    val preview = controller.preview
    val current = draft?.summaries?.getOrNull(selected)
    var edited by remember(selected, current) { mutableStateOf(current.orEmpty()) }
    val hasEdits = current != null && current != edited
    OrbisInterfaceTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(onDismissRequest = { if (!busy && !hasEdits) onDismiss() }, containerColor = colors.panel,
            contentColor = colors.ink, sheetGesturesEnabled = !busy && !hasEdits,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy && !hasEdits,
                shouldDismissOnClickOutside = !busy && !hasEdits)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 16.dp)) {
                Text("分段生成摘要", style = MaterialTheme.typography.titleLarge)
                OrbisScrollablePanel(Modifier.weight(1f), windowInsets = WindowInsets(0,0,0,0)) {
                    Text("适合已经超过模型上限的窗口。只发送所选范围的公开文字，按顺序切成多段；不带完整聊天、世界书、工具、思考过程或图片音频。原文及附件会保留在存档中。")
                    if (controller.archiveId != null) {
                        Text("整理完成：全部分段摘要已进入当前上下文，并分别存入本机记忆库。聊天列表保留一个完整原文存档，没有自动续聊。",
                            modifier = Modifier.testTag("manual-summary-applied"))
                    } else if (draft == null) {
                        OutlinedTextField(through, { through = it }, enabled = !busy, singleLine = true,
                            label = { Text("从第 1 条归档到第 N 条") },
                            supportingText = { Text("共 ${conversation.messageNodes.size} 条；完整工具组可能使实际归档范围缩小。") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("summary-through"))
                        OutlinedTextField(count, { count = it }, enabled = !busy, singleLine = true,
                            label = { Text("生成多少段摘要（留空自动）") },
                            supportingText = { Text("1–512 段。自动按每段约 24000 字符规划；自选段数越少，单次输入越大（最多约 50 万字符）。段数太少仍可能被模型拒绝，请留足输出余量。") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("summary-count"))
                        OutlinedTextField(prompt, { if (it.length <= 8000) prompt = it }, enabled = !busy,
                            label = { Text("摘要要求（可选）") },
                            placeholder = { Text("例如：以阿止第一人称，详细保留我们的约定、关系变化与未完成事项。") },
                            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("summary-prompt"))
                        Button(onClick = { controller.plan(through.toIntOrNull() ?: 0, count.takeIf { it.isNotBlank() }?.toIntOrNull() ?: if (count.isBlank()) null else 0, prompt) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("summary-plan")) { Text("先规划分段（不请求模型）") }
                    } else {
                        Text("${draft.modelName} · ${draft.chunks.size} 段，已保存 ${draft.completed} 段，还差 ${draft.chunks.size - draft.completed} 段。",
                            modifier = Modifier.testTag("summary-progress"))
                        Text("单段最长 ${draft.chunks.maxOf { it.length }} 字符，另加摘要要求与输出。字符数不是 token 数；自选段数需按模型容量留余量。")
                        Text("摘要要求：${draft.prompt.ifBlank { "按原文语言细致总结，保留说话者归属。" }}")
                        Text("每段会分别调用当前模型并可能计费，不自动重试。已完成段逐段保存在本机草稿，关闭重开后可以继续；未完成的在途请求也可能已计费。草稿不随备份导出，请勿清除应用数据。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("同时请求")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..4).forEach { n -> FilterChip(selected = concurrency == n, onClick = { concurrency = n }, enabled = !busy && !hasEdits, label = { Text("$n 段") }) }
                        }
                        Text("每段输出上限（token）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(4096,8192,16384).forEach { n -> FilterChip(selected = output == n, onClick = { output = n }, enabled = !busy && !hasEdits, label = { Text("${n / 1024}K") }) }
                        }
                        if (!draft.complete) Button(onClick = { controller.generate(concurrency, output) }, enabled = !busy && !hasEdits,
                            modifier = Modifier.fillMaxWidth().testTag("summary-generate")) { Text(if (draft.completed == 0) "开始生成（${draft.chunks.size} 次请求）" else "继续未完成的 ${draft.chunks.size - draft.completed} 段") }
                        if (controller.generating) OutlinedButton(onClick = controller::pause, modifier = Modifier.fillMaxWidth()) { Text("暂停，保留已完成段") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected-- }, enabled = !hasEdits && selected > 0) { Text("上一段") }
                            Text("第 ${selected + 1} / ${draft.chunks.size} 段", Modifier.padding(top = 12.dp))
                            TextButton(onClick = { selected++ }, enabled = !hasEdits && selected < draft.chunks.lastIndex) { Text("下一段") }
                        }
                        if (current != null) {
                            OutlinedTextField(edited, { if (it.length <= 100_000) edited = it }, enabled = !busy,
                                label = { Text("摘要正文，可核对和修改") }, minLines = 4, maxLines = 10,
                                modifier = Modifier.fillMaxWidth().testTag("summary-edit"))
                            OutlinedButton(onClick = { controller.edit(selected, edited) }, enabled = !busy && hasEdits && edited.isNotBlank()) { Text("保存本段修改") }
                        } else Text("这一段尚未完成。")
                        if (hasEdits) Text("请先保存本段修改，再切段、继续生成或应用。")
                        if (draft.complete) OutlinedButton(onClick = controller::preview, enabled = !busy && !hasEdits,
                            modifier = Modifier.fillMaxWidth().testTag("summary-preview")) { Text("预览全部摘要的整理结果") }
                        if (preview != null && !hasEdits) {
                            Text("实际归档 ${preview.archivedCount} 条，保留 ${preview.keptCount} 条原文；${draft.chunks.size} 段摘要全部保留。文字估算 ${preview.beforeTokens} → ${preview.afterTokens} tokens。")
                            if (preview.afterTokens >= preview.beforeTokens) Text("本次估算没有减小上下文，请精简摘要或扩大归档范围。")
                            Text("估算不是服务商精确用量，系统提示与输出预留还会占用空间；不能保证一定低于模型上限。")
                            Button(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("summary-apply")) { Text("确认摘要并应用…") }
                        }
                        TextButton(onClick = { discard = true }, enabled = !busy && !hasEdits) { Text("放弃本次草稿，重新选择范围和段数") }
                    }
                    controller.error?.let { Text(it, color = colors.onSand, modifier = Modifier.testTag("summary-error")) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onDismiss, enabled = !busy && !hasEdits, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
        if (confirm && preview != null && !busy) AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("应用全部分段摘要？") },
            text = { Text("将 ${draft?.chunks?.size} 段摘要一起存入当前上下文及记忆库，并创建一个完整原文存档。确认后不会自动发送消息。") },
            confirmButton = { TextButton(onClick = { confirm = false; controller.apply() }) { Text("保留原文并应用") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回核对") } })
        if (discard && !busy) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃已保存的摘要草稿？") },
            text = { Text("这会清除本次草稿，包括已付费生成的段落。原聊天不会改变；重新生成可能再次计费。") },
            confirmButton = { TextButton(onClick = { discard = false; selected = 0; controller.discard() }) { Text("放弃草稿") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("保留") } })
    }
}
