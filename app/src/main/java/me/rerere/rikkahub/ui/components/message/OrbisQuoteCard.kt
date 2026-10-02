package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.rikkahub.utils.stripMarkdown

@Composable
fun OrbisQuotePreview(quote: OrbisMessageQuote, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("引用${if (quote.sourceRole == MessageRole.USER) "自己" else "AI"}的正文", style = MaterialTheme.typography.labelMedium)
                Text(quote.bodySnapshot, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel) { Text("取消引用") }
        }
    }
}

@Composable
fun OrbisQuoteCard(
    quote: OrbisMessageQuote,
    modifier: Modifier = Modifier,
    onJump: (() -> Unit)? = null,
) {
    var showDetail by remember(quote) { mutableStateOf(false) }
    val sourceLabel = if (quote.sourceRole == MessageRole.USER) "自己" else "AI"
    val preview = remember(quote.bodySnapshot) {
        // A bounded, one-paragraph display preview only. Keep the saved/request snapshot intact.
        val head = quote.bodySnapshot.take(2048)
        val plain = head.stripMarkdown().ifBlank { head }.replace(Regex("\\s+"), " ").trim()
        plain.take(500).ifBlank { "引用正文" } +
            if (plain.length > 500 || quote.bodySnapshot.length > head.length) "…" else ""
    }
    Surface(
        onClick = { showDetail = true },
        modifier = modifier.widthIn(max = 300.dp).testTag("orbis-quote-card")
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
    ) {
        Text(
            "$sourceLabel：$preview",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp).testTag("orbis-quote-preview-text"),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("引用 · $sourceLabel") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(quote.bodySnapshot) }
                    if (onJump == null) {
                        Text("这里保留的是引用快照，当前无法定位原消息。",
                            modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text("关闭") } },
            dismissButton = {
                if (onJump != null) TextButton(onClick = {
                    showDetail = false
                    onJump()
                }) { Text("定位原消息") }
            },
        )
    }
}
