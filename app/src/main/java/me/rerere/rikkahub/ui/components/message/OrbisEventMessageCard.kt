package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.model.OrbisAppearance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A quiet timestamp is only presentation. Its source remains external data, never instructions. */
@Composable
internal fun OrbisEventMessageCard(
    event: OrbisEventMetadata,
    originalText: String,
    onUpdate: suspend (OrbisEventMetadata) -> Unit,
    onOpacityChange: suspend (Float) -> Unit,
    modifier: Modifier = Modifier,
    appearance: OrbisAppearance = OrbisAppearance(),
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val normalized = appearance.normalized()
    val time = orbisEventTimePresentation(event, now, zone)
    val ink = normalized.chatTextColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface
    // Background alpha never fades text. A subtle contrasting halo helps on photo wallpapers.
    val shadow = Shadow(
        color = if (ink.luminance() > .5f) Color.Black.copy(alpha = .7f) else Color.White.copy(alpha = .7f),
        blurRadius = 2f,
    )
    val smallStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily, shadow = shadow,
    )
    var showAppearance by remember(event.recordId) { mutableStateOf(false) }
    // Expanding an already-received event is a local view action, not a history edit or
    // a new sentinel delivery. An active reply / slow writer must never hide its text.
    // Keep the user's choice across recreation; stale persistence callbacks cannot undo it.
    var localCollapsed by rememberSaveable(event.recordId) { mutableStateOf<Boolean?>(null) }
    var locallyRead by rememberSaveable(event.recordId) { mutableStateOf(false) }
    val collapsed = localCollapsed ?: event.collapsed
    val updates = remember(event.recordId) { Channel<OrbisEventMetadata>(Channel.CONFLATED) }
    val savePresentation by rememberUpdatedState(onUpdate)
    DisposableEffect(updates) { onDispose { updates.close() } }
    LaunchedEffect(updates) {
        // One observer, latest choice wins. Storage is best effort for these cosmetic flags;
        // it never controls visibility and never changes the original event or reply.
        for (update in updates) {
            try {
                savePresentation(update)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The local saved-state choice still works. Chat persistence failures are
                // reported by the chat writer, not by opening a timestamp.
            }
        }
    }
    Column(modifier.fillMaxWidth().testTag("orbis-event-card")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("orbis-event-collapse")
                .semantics {
                    stateDescription = if (collapsed) "已折叠" else "已展开"
                    contentDescription = "${time.detailLabel}，${if (collapsed) "点按展开消息" else "点按折叠消息"}"
                }
                .clickable(role = Role.Button,
                    onClickLabel = if (collapsed) "展开消息" else "折叠消息") {
                    val next = toggleOrbisEventPresentation(event.copy(
                        collapsed = collapsed, read = event.read || locallyRead))
                    localCollapsed = next.collapsed
                    locallyRead = next.read
                    showAppearance = false
                    updates.trySend(next)
                }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(time.compactLabel, modifier = Modifier.testTag("orbis-event-time"),
                color = ink.copy(alpha = .64f), style = smallStyle)
        }
        if (!collapsed) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("orbis-event-details"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = normalized.eventOpacity),
                contentColor = ink,
                border = BorderStroke(1.dp, ink.copy(alpha = .25f * normalized.eventOpacity)),
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${orbisEventSourceLabel(event.source)}\n${time.detailLabel}",
                            modifier = Modifier.weight(1f), color = ink, style = smallStyle)
                        IconButton(onClick = { showAppearance = true },
                            modifier = Modifier.size(48.dp).testTag("orbis-event-appearance")) {
                            Icon(HugeIcons.Settings03, contentDescription = "系统消息背景设置",
                                tint = ink, modifier = Modifier.size(18.dp))
                        }
                    }
                    SelectionContainer {
                        // Plain text preserves whitespace and never executes links/HTML.
                        Text(originalText, modifier = Modifier.testTag("orbis-event-original"), color = ink,
                            style = LocalTextStyle.current.copy(shadow = shadow))
                    }
                }
            }
        }
    }
    if (showAppearance && !collapsed) {
        EventAppearanceDialog(normalized.eventOpacity, onOpacityChange) { showAppearance = false }
    }
}

@Composable
private fun EventAppearanceDialog(opacity: Float, onSave: suspend (Float) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableFloatStateOf(opacity) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        title = { Text("系统消息背景") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("所有系统消息共用此设置。0% 为完全透明；文字颜色沿用聊天字体设置。")
                Text("背景不透明度 ${(draft * 100).roundToInt()}%", Modifier.testTag("orbis-event-opacity-value"))
                Slider(value = draft, onValueChange = { draft = it; failed = false }, enabled = !saving,
                    valueRange = 0f..1f,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("orbis-event-opacity-slider")
                        .semantics { contentDescription = "系统消息背景不透明度" })
                if (failed) Text("未能保存，请重试。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, modifier = Modifier.testTag("orbis-event-opacity-save"), onClick = {
                saving = true
                scope.launch {
                    try {
                        onSave(draft)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss,
                modifier = Modifier.testTag("orbis-event-opacity-cancel")) { Text("取消") }
        },
    )
}

internal fun orbisEventSourceLabel(source: String): String = when (source) {
    "rikka_sentinel" -> "聊天哨兵"
    "lc_sentinel" -> "手机陪伴哨兵"
    "self_reminder" -> "自主提醒"
    "legacy_sentinel" -> "旧哨兵（归属待核实）"
    else -> if (me.rerere.rikkahub.data.orbis.isNativeSentinelSource(source)) "本地自主哨兵" else "未识别来源：$source"
}

internal fun orbisEventReceivedTime(receivedAt: Long): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(receivedAt))
}.getOrDefault("时间不可用")
