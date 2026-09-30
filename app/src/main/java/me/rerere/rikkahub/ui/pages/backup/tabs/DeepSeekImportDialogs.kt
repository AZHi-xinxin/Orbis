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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DeepSeekImportDialogs(controller: DeepSeekImportController, state: DeepSeekImportUiState) {
    if (state.busy) {
        AlertDialog(onDismissRequest = {}, title = { Text("DeepSeek 记录导入") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase)
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
        val date = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
        AlertDialog(onDismissRequest = controller::discard, title = { Text("选择要带入的对话") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("发现 ${preview.conversations.size} 个窗口。建议先导入一个长会话试用，再导入其他窗口。")
                        Text("仅追加所选完整路径，不覆盖聊天。其他分支保留在原 ZIP，可再次选择导入；请保管好原文件。历史图片不会自动联网加载。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("导入、打开记录不会请求模型。之后发消息仍按当前上下文设置发送历史；长会话可能超出所选模型容量或产生较高费用，不会自动截断或压缩。",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { preview.conversations.forEach { selected[it.sourceId] = it.defaultLeafId } }) { Text("全选默认路径") }
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
                                Text("所选路径 ${branch.messageCount} 条 · 原窗口 ${conversation.messageCount} 条 · ${conversation.branches.size} 条可选路径",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(if (conversation.defaultSelectionReason == "source_current_node")
                                    "默认：导出时的活动路径" else "默认：最后更新的末端路径",
                                    style = MaterialTheme.typography.bodySmall)
                                if (conversation.branches.size > 1) TextButton(onClick = { branchFor = conversation.sourceId }) { Text("选择分支路径") }
                            }
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = { controller.import(selected.toMap()) }) {
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
        AlertDialog(onDismissRequest = controller::dismissResult, title = { Text("DeepSeek 导入结果") },
            text = { Text(result) }, confirmButton = { TextButton(onClick = controller::dismissResult) { Text("知道了") } })
    }
}
