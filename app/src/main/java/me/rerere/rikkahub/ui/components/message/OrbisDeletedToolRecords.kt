package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor

@Composable
internal fun ToolRecordDeleteButton(onDelete: () -> Unit) {
    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp).testTag("orbis-delete-tool-record")) {
        Icon(HugeIcons.Delete01, contentDescription = "删除这条工具调用及回执",
            modifier = Modifier.size(17.dp))
    }
}

/** Local-only receipt. It is not a message part and never becomes model context. */
@Composable
internal fun OrbisDeletedToolRecords(
    records: List<Pair<String, String>>,
    onRestore: ((String) -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val color = LocalOrbisChatTextColor.current ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("orbis-deleted-tools")) {
            Text("已删 ${records.size} 条工具记录 · ${if (expanded) "收起" else "查看 / 撤销"}",
                color = color.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            Text("已从后续上下文移除调用与回执；不会撤销工具已做的事。撤销会把原记录放回上下文。",
                color = color.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
            records.forEach { (id, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = .7f))
                    TextButton(onClick = { onRestore?.invoke(id) }, enabled = onRestore != null,
                        modifier = Modifier.testTag("orbis-restore-tool-$id")) { Text("撤销删除") }
                }
            }
        }
    }
}
