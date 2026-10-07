package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.backup.DeepSeekImportController
import me.rerere.rikkahub.ui.pages.backup.DeepSeekImportUiState
import me.rerere.rikkahub.ui.pages.backup.ChatArchiveSource
import me.rerere.rikkahub.ui.pages.backup.importPreviewCounts
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DeepSeekImportDialogs(controller: DeepSeekImportController, state: DeepSeekImportUiState) {
    if (state.busy) {
        AlertDialog(onDismissRequest = {}, title = { Text("${controller.sourceLabel} 记录导入") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase)
                state.destinationName?.let { Text("导入到：$it", style = MaterialTheme.typography.bodySmall) }
                Text("处理在本机完成，不向模型发送。停止后保留已完成的会话，不留下半个会话。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {}, dismissButton = {
                TextButton(onClick = controller::cancel) { Text("停止") }
            })
    } else state.preview?.let { preview ->
        val selected = remember(preview) {
            mutableStateMapOf<String, String>().apply {
                preview.conversations.firstOrNull()?.let { put(it.sourceId, it.defaultLeafId) }
            }
        }
        var branchFor by remember(preview) { mutableStateOf<String?>(null) }
        var correctedOperitCopy by remember(preview) { mutableStateOf(false) }
        val date = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
        AlertDialog(onDismissRequest = controller::discard, title = { Text("选择要带入的对话") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        state.destinationName?.let { Text("导入到：$it（以本次预览选定的助手为准）") }
                        Text(if (controller.source in setOf(ChatArchiveSource.CLAUDE, ChatArchiveSource.CHATGPT))
                            "发现 ${preview.conversations.size} 条完整路径。每条路径独立保存；全选可带入全部现有分支，公共前文会重复。"
                            else "发现 ${preview.conversations.size} 个窗口。建议先导入一个长会话试用，再导入其他窗口。")
                        Text(when (controller.source) {
                            ChatArchiveSource.CHATGPT -> "只追加所选完整路径，不合并互斥分支。活动路径已标明；系统、开发者和隐藏消息不导入。历史工具只作文字，附件未恢复；相同版本跳过，变化另建窗口。请保管所有原始文件。"
                            ChatArchiveSource.CLAUDE -> "只追加所选完整路径，不把互斥分支拼成连续聊天。缺失的前文会标明，文字副本差异与思考会保留。相同版本跳过，源会话变化会另建窗口；不覆盖记录、执行历史工具或导入账号与授权。"
                            ChatArchiveSource.POLARIS -> "按原顺序追加所选窗口的聊天与独立思考；历史图片只保留引用说明。不导入账号、密钥、人格、设置或工具授权。请保管原文件。"
                            ChatArchiveSource.DEEPSEEK -> "仅追加所选完整路径，不覆盖聊天。其他分支保留在原 ZIP，可再次选择导入；请保管好原文件。历史图片不会自动联网加载。"
                            else -> "仅追加所选会话的当前回答，不合并备用回答、不运行历史工具。附件只保留文字引用，请保管原文件。"
                        },
                            style = MaterialTheme.typography.bodySmall)
                        preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (controller.source == me.rerere.rikkahub.ui.pages.backup.ChatArchiveSource.OPERIT) {
                            Row {
                                Checkbox(checked = correctedOperitCopy, onCheckedChange = { correctedOperitCopy = it })
                                Text("另建思考整理副本（保留原聊天）。仅在之前已导入但思考混在正文时选择；同一文件的整理副本再次导入会跳过。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("导入、打开记录不会请求模型。之后发消息仍按当前上下文设置发送历史；长会话可能超出所选模型容量或产生较高费用，不会自动截断或压缩。",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { preview.conversations.forEach { selected[it.sourceId] = it.defaultLeafId } }) {
                                Text(if (controller.source in setOf(ChatArchiveSource.CLAUDE, ChatArchiveSource.CHATGPT)) "全选全部路径" else "全选默认路径")
                            }
                            TextButton(onClick = { selected.clear() }) { Text("清空") }
                        }
                    }
                    items(preview.conversations, key = { it.sourceId }) { conversation ->
                        val leaf = selected[conversation.sourceId] ?: conversation.defaultLeafId
                        val branch = conversation.branches.first { it.leafId == leaf }
                        Row {
                            Checkbox(checked = conversation.sourceId in selected, onCheckedChange = {
                                if (it) selected[conversation.sourceId] = leaf else selected.remove(conversation.sourceId)
                            })
                            Column(Modifier.weight(1f)) {
                                Text(conversation.title.ifBlank { "未命名对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(importPreviewCounts(conversation, branch),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(if (controller.source == ChatArchiveSource.CLAUDE) {
                                    if (conversation.defaultSelectionReason == "claude_complete_path_missing_parent")
                                        "完整现有路径 · 导出未含部分前文，已保留缺口标记"
                                    else "完整路径 · 不代表来源选中的活动分支"
                                } else if (controller.source == ChatArchiveSource.CHATGPT) {
                                    if (conversation.defaultSelectionReason == "chatgpt_complete_path_current")
                                        "完整路径 · 导出时的活动路径（current_node）" else "完整路径 · 其他分支或导出未指定活动路径"
                                } else if (controller.source == ChatArchiveSource.POLARIS) "默认：原窗口中的聊天顺序"
                                    else if (controller.selectedAnswersOnly) "默认：导出时选中的回答"
                                    else if (conversation.defaultSelectionReason == "source_current_node")
                                        "默认：导出时的活动路径" else "默认：最后更新的末端路径",
                                    style = MaterialTheme.typography.bodySmall)
                                if (conversation.omittedSummaryCount > 0) Text(
                                    "跳过 ${conversation.omittedSummaryCount} 条内部摘要（不作为聊天或系统提示导入）",
                                    style = MaterialTheme.typography.bodySmall)
                                if (conversation.branches.size > 1) TextButton(onClick = { branchFor = conversation.sourceId }) { Text("选择分支路径") }
                            }
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    controller.import(if (correctedOperitCopy) selected.keys.associateWith {
                        me.rerere.rikkahub.data.sync.importer.OperitChatArchive.CORRECTED_COPY_PATH
                    } else selected.toMap())
                }) {
                    Text("导入 ${selected.size} 个所选路径")
                }
            }, dismissButton = { TextButton(onClick = controller::discard) { Text("取消") } })
        branchFor?.let { sourceId ->
            val conversation = preview.conversations.first { it.sourceId == sourceId }
            AlertDialog(onDismissRequest = { branchFor = null }, title = { Text("选择一条完整路径") },
                text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(conversation.branches.withIndex().toList(), key = { it.value.leafId }) { (index, branch) ->
                        TextButton(onClick = { selected[sourceId] = branch.leafId; branchFor = null }) {
                            Text("路径 ${index + 1}${if (branch.isDefault) "（默认）" else ""} · ${branch.messageCount} 条\n${date.format(branch.updatedAt)}")
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { branchFor = null }) { Text("返回") } })
        }
    }
    state.result?.let { result ->
        AlertDialog(onDismissRequest = controller::dismissResult, title = { Text("${controller.sourceLabel} 导入结果") },
            text = { Text(result) }, confirmButton = { TextButton(onClick = controller::dismissResult) { Text("知道了") } })
    }
}
