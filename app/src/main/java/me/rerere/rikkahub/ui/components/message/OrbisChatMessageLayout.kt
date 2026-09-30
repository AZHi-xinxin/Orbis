package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.toMessageTimeString

/** Shared by the main conversation and garden drawer; presentation only, never a ChatVM. */
@Composable
internal fun rememberChatMessageTextStyle(): TextStyle {
    val display = LocalSettings.current.displaySetting
    val family = LocalChatFontFamily.current ?: rememberChatFontFamily(display)
    return LocalTextStyle.current.copy(
        fontSize = (if (BuildConfig.ORBIS_ENABLED) 14.sp else LocalTextStyle.current.fontSize) * display.fontSizeRatio,
        lineHeight = (if (BuildConfig.ORBIS_ENABLED) 22.4.sp else LocalTextStyle.current.lineHeight) * display.fontSizeRatio,
        fontFamily = family,
    )
}

/** All role opacity, bubble style, side-avatar, timestamp and custom prose-color rules live here. */
@Composable
internal fun OrbisChatMessageLayout(
    message: UIMessage,
    model: Model?,
    assistant: Assistant?,
    loading: Boolean,
    segmentedReply: Boolean,
    content: @Composable () -> Unit,
) {
    val display = LocalSettings.current.displaySetting
    val appearance = display.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            9.dp, if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        ),
        verticalAlignment = Alignment.Top,
    ) {
        if (message.role != MessageRole.USER) OrbisChatMessageAvatar(message, model, assistant, loading)
        if (segmentedReply) {
            CompositionLocalProvider(
                LocalOrbisChatTextColor provides appearance.normalized().chatTextColor?.let { Color(it) },
            ) {
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                    if (!loading && display.showDateTimeInMessage) {
                        Text(message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                            modifier = Modifier.align(Alignment.End), color = OrbisTheme.colors.mutedInk,
                            fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        } else OrbisMessageBubble(
            user = message.role == MessageRole.USER,
            appearance = appearance,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            content()
            if (!loading && display.showDateTimeInMessage) {
                Text(message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                    modifier = Modifier.align(Alignment.End), color = OrbisTheme.colors.mutedInk,
                    fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
        if (message.role == MessageRole.USER) OrbisChatMessageAvatar(message, model, assistant, loading)
    }
}
