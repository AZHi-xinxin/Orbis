package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.isConversationNotification

/** The inbox is a durable conversation notification source, not a fabricated model response.
 * Only unmaterialized cards are projected: compaction/deletion of committed history stays final.
 */
internal fun orbisPendingConversationNotifications(events: List<OrbisInboxEvent>, conversation: Conversation): List<OrbisInboxEvent> {
    val materialized = conversation.messageNodes.flatMap { it.messages }.map { it.id.toString() }.toSet()
    return events.filter { it.isConversationNotification(conversation.assistantId.toString(), conversation.id.toString()) &&
        it.id !in materialized }.sortedBy { it.receivedAt }
}

internal fun independentNotificationLabel(event: OrbisInboxEvent): String = when (event.state) {
    "accepted" -> "哨兵通知已到 · 待 AI 处理"
    "queued", "generating" -> "哨兵通知已到 · 正在处理"
    "unknown" -> "哨兵通知已到 · 本条回复未确认，不会自动重试"
    else -> "哨兵通知已到 · 本条回复未完成，不影响发送新消息"
}

@Composable
internal fun OrbisPendingNotificationCard(event: OrbisInboxEvent, onDismiss: () -> Unit = {}) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    var dismissed by rememberSaveable(event.id) { mutableStateOf(false) }
    if (dismissed) return
    Surface(modifier = Modifier.fillMaxWidth().testTag("sentinel-notice-${event.id}"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(independentNotificationLabel(event), style = MaterialTheme.typography.labelMedium)
            Text(me.rerere.rikkahub.ui.components.message.orbisEventReceivedTime(event.receivedAt),
                style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(event.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis) }
            if (event.localImage != null) Text("本条附有观察截图。", style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                TextButton(onClick = { dismissed = true; onDismiss() }) { Text("关闭通知") }
            }
        }
    }
}
