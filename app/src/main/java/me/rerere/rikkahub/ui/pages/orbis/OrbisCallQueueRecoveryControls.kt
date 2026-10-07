package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState

/** Manual reply admission is separate from reviewing or resuming the chat queue. */
@Composable
internal fun OrbisCallQueueRecoveryControls(
    state: VoiceSessionState,
    contentColor: Color,
    onRecover: () -> Unit,
    onReview: () -> Unit,
) {
    if (!state.replyBlocked) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val title = if (state.replyResumeChecking) "正在检查能否继续回复" else "回复已暂停，通话仍保留"
        Text(title, color = contentColor, fontSize = 15.sp,
            modifier = Modifier.testTag("orbis-call-reply-pause-title"))
        state.replyNotice?.takeIf { it.isNotBlank() && it != title }?.let {
            Text(it, color = contentColor.copy(alpha = .75f), fontSize = 12.sp,
                modifier = Modifier.testTag("orbis-call-reply-pause-notice"))
        }
        Row {
            TextButton(onClick = onRecover, enabled = !state.replyResumeChecking,
                modifier = Modifier.testTag("orbis-call-resume-replies")) {
                Text(if (state.replyResumeChecking) "检查中…" else "继续回复", color = contentColor)
            }
            TextButton(onClick = onReview, modifier = Modifier.testTag("orbis-call-review-replies")) {
                Text("回聊天处理", color = contentColor)
            }
        }
        Text("先在聊天中处理未完成的回复，再检查是否可以继续说话；不会重发旧消息。",
            color = contentColor.copy(alpha = .7f), fontSize = 11.sp)
    }
}
