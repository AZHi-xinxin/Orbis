package me.rerere.rikkahub.ui.pages.favorite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.favorite.OrbisFavoritePartKind
import me.rerere.rikkahub.data.favorite.OrbisFavoriteSnapshot

data class OrbisFavoriteDetail(val snapshot: OrbisFavoriteSnapshot?, val legacy: Boolean, val preview: String)

/** Static local text only: no tool UI, media loader, WebView, model, or source-conversation fetch. */
@Composable
fun OrbisFavoriteDetailDialog(detail: OrbisFavoriteDetail, onDismiss: () -> Unit, onJump: () -> Unit) {
    var expandedReasoning by remember(detail) { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏快照") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("正文与思考保存在本机收藏夹，随“聊天数据库”备份。这里不会发给 AI。",
                        style = MaterialTheme.typography.bodySmall)
                }
                val snapshot = detail.snapshot
                if (snapshot == null) {
                    item {
                        Text(if (detail.legacy) "这是旧收藏，只保留了预览，没有保存完整正文与思考。原消息仍在时可定位查看。"
                            else "这份收藏快照无法读取；原记录未改动，可尝试定位原消息。")
                        SelectionContainer { Text(detail.preview) }
                    }
                } else {
                    snapshot.quote?.let { quote ->
                        item {
                            Text("消息中引用的正文", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer { Text(quote.bodySnapshot) }
                        }
                    }
                    itemsIndexed(snapshot.parts) { index, part ->
                        if (part.kind == OrbisFavoritePartKind.REASONING) {
                            TextButton(onClick = { expandedReasoning = if (index in expandedReasoning)
                                expandedReasoning - index else expandedReasoning + index }) {
                                Text(if (index in expandedReasoning) "收起思考" else "展开已保存的思考")
                            }
                        }
                        if (part.kind == OrbisFavoritePartKind.BODY || index in expandedReasoning) {
                            SelectionContainer { Text(part.text, modifier = Modifier.padding(horizontal = 4.dp)) }
                        }
                    }
                    if (snapshot.parts.isEmpty()) item { Text("这条消息没有可保存的正文或思考。") }
                    if (snapshot.unarchivedPartCount > 0) item {
                        Text("另有 ${snapshot.unarchivedPartCount} 段附件或工具内容未独立保存。此处不能播放或执行；原附件删除后可能不可用。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onJump) { Text("定位原消息") } },
    )
}
