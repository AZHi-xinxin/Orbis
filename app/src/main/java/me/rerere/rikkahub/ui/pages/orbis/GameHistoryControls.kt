package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class GameRecordPage<T>(val records: List<T>, val index: Int, val pages: Int)
internal fun <T> gameRecordPage(records: List<T>, requested: Int): GameRecordPage<T> {
    val pages = ((records.size + 4) / 5).coerceAtLeast(1)
    val index = requested.coerceIn(0, pages - 1)
    return GameRecordPage(records.asReversed().drop(index * 5).take(5), index, pages)
}

@Composable
internal fun GameHistoryToggle(title: String, count: Int, expanded: Boolean, tag: String, onToggle: () -> Unit) {
    OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text("${if (expanded) "▾" else "▸"} $title · $count 局", Modifier.weight(1f))
        Text(if (expanded) "收起" else "展开")
    }
}

@Composable
internal fun GameHistoryPager(index: Int, pages: Int, tag: String, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onPage(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("$tag-previous")) { Text("上一页") }
        Text("${index + 1} / $pages · 每页 5 条")
        TextButton(onClick = { onPage(index + 1) }, enabled = index + 1 < pages, modifier = Modifier.testTag("$tag-next")) { Text("下一页") }
    }
}
