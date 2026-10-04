package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningDisplayProjection

/** No historical payload or secondary cleanup tool is rendered in this host-owned tombstone. */
@Composable
internal fun ContextPruningNotice(projection: ContextPruningDisplayProjection?, onRestore: ((String) -> Unit)?) {
    if (projection == null || projection.hiddenPartIndexes.isEmpty()) return
    Text(when {
        projection.hiddenToolIndexes.isNotEmpty() -> "该条工具调用记录已删除"
        projection.hiddenReasoningIndexes.isNotEmpty() -> "该条思考记录已删除"
        else -> "旧记录已清理，清理动作不重复展开"
    }, style = MaterialTheme.typography.bodySmall)
    for (batchId in projection.activeBatchIds) {
        TextButton(enabled = onRestore != null, onClick = { onRestore?.invoke(batchId) }) {
            Text("恢复这批记录（不重执行）")
        }
    }
}
