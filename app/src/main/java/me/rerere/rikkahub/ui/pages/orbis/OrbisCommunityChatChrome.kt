package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground

/** Shared presentation only: neither group page selects or mutates the private conversation. */
@Composable
internal fun communityAppearance(): OrbisAppearance =
    LocalSettings.current.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized()

@Composable
internal fun OrbisCommunityBackdrop(
    settings: Settings,
    hostVisible: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val appearance = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized()
    val assistant = settings.getCurrentAssistant()
    val inherit = !appearance.backgroundEnabled && (assistant.background != null || assistant.useGradientBackground)
    Box(Modifier.fillMaxSize()) {
        if (inherit) AssistantBackground(settings, Modifier.fillMaxSize())
        OrbisChatBackdrop(appearance, Modifier.fillMaxSize(), isVisible = hostVisible, drawBackground = !inherit)
        content()
    }
}

/** Palette is a pure function of the persisted identity, never of list position or current time. */
internal fun communityMemberColor(key: String, dark: Boolean): Color {
    val hue = ((key.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
    return Color.hsl(hue, if (dark) .28f else .45f, if (dark) .24f else .93f)
}

@Composable
internal fun OrbisCommunityComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    sendEnabled: Boolean,
    placeholder: String,
    appearance: OrbisAppearance,
    attachmentActions: @Composable RowScope.() -> Unit = {},
    pendingContent: @Composable () -> Unit = {},
    sending: Boolean = false,
    onStop: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).testTag("orbis-community-composer"),
        shape = RoundedCornerShape(24.dp),
        color = OrbisTheme.colors.panel.copy(alpha = appearance.composerOpacity),
        contentColor = OrbisTheme.colors.ink,
        border = BorderStroke(1.dp, OrbisTheme.colors.border.copy(alpha = .6f)),
    ) {
        Column {
            pendingContent()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                attachmentActions()
                BasicTextField(
                    value = value, onValueChange = onValueChange, enabled = enabled,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
                    minLines = 1, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = OrbisTheme.colors.ink),
                    cursorBrush = SolidColor(OrbisTheme.colors.accent),
                    decorationBox = { inner -> Box {
                        if (value.isEmpty()) Text(placeholder, color = OrbisTheme.colors.mutedInk,
                            style = MaterialTheme.typography.bodyLarge)
                        inner()
                    } },
                )
                if (sending && onStop != null) IconButton(onClick = onStop, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "停止本轮" }) {
                    Text("■", color = OrbisTheme.colors.accent)
                } else FilledIconButton(onClick = onSend, enabled = enabled && sendEnabled,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "发送消息" }) { Text("↑", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
