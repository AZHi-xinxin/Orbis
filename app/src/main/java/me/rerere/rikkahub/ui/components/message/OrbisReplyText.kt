package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.data.model.OrbisReplySlice
import me.rerere.rikkahub.data.model.splitOrbisReply
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.context.orbisChatTextStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

private data class RenderedReply(
    val original: String,
    val options: OrbisChatFlowSettings,
    val slices: List<OrbisReplySlice>,
)

/** Display projection only. The original UIMessage still owns copy, TTS, edits and requests. */
@Composable
internal fun OrbisReplyText(
    content: String,
    appearance: OrbisAppearance,
    contentKey: String,
    onClickCitation: (String) -> Unit = {},
) = key(contentKey) {
    val flow = appearance.chatFlow
    // Markdown boundary analysis must not block the UI thread on a long streaming answer.
    // Parse serially and conflate incoming chunks: never spawn one uncancellable CPU parse
    // per token. Keep the last complete projection while the newest chunk is processed.
    val latestContent by rememberUpdatedState(content)
    var parsed by remember { mutableStateOf<RenderedReply?>(null) }
    LaunchedEffect(flow) {
        snapshotFlow { latestContent }.conflate().collect { source ->
            parsed = withContext(Dispatchers.Default) {
                val slices = try {
                    splitOrbisReply(source, flow)
                } catch (_: IllegalArgumentException) {
                    listOf(OrbisReplySlice(0, source.length))
                }
                RenderedReply(source, flow, slices)
            }
        }
    }
    val reply = parsed?.takeIf { it.options == flow && content.startsWith(it.original) }
    if (reply == null) {
        Text("…", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("orbis-reply-parsing"))
    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reply.slices.forEach { slice ->
            val text = reply.original.substring(slice.start, slice.endExclusive)
            if (text.isNotBlank()) key(slice.start, slice.action) {
                if (slice.action) {
                    OrbisActionText(text, appearance, slice.start, onClickCitation)
                } else {
                    OrbisMessageBubble(
                        user = false,
                        appearance = appearance,
                        modifier = Modifier.testTag("orbis-reply-bubble-${slice.start}"),
                    ) {
                        MarkdownBlock(content = text, style = orbisChatTextStyle(),
                            onClickCitation = onClickCitation)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbisActionText(
    content: String,
    appearance: OrbisAppearance,
    start: Int,
    onClickCitation: (String) -> Unit,
) {
    val folded = appearance.chatFlow.foldActions
    var expanded by remember(folded) { mutableStateOf(false) }
    val ink = appearance.normalized().chatTextColor?.let { Color(it) } ?: OrbisTheme.colors.ink
    CompositionLocalProvider(LocalOrbisChatTextColor provides ink, LocalContentColor provides ink) {
        Column(Modifier.padding(horizontal = 8.dp).testTag("orbis-action-$start"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (folded) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .semantics { stateDescription = if (expanded) "动作已展开" else "动作已折叠" }
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                        .testTag("orbis-action-toggle-$start"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("动作", style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .72f))
                    Icon(if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                        contentDescription = null, Modifier.size(15.dp), tint = ink.copy(alpha = .72f))
                }
            }
            if (!folded || expanded) {
                // Keep markers and original words. Dimming is presentation, not deletion or TTS filtering.
                Column(Modifier.then(if (folded) Modifier.clip(RoundedCornerShape(12.dp))
                    .background(OrbisTheme.colors.ink.copy(alpha = .06f)).padding(8.dp) else Modifier)
                    .alpha(.72f).testTag("orbis-action-content-$start")) {
                    MarkdownBlock(content = content, style = orbisChatTextStyle(), onClickCitation = onClickCitation)
                }
            }
        }
    }
}
