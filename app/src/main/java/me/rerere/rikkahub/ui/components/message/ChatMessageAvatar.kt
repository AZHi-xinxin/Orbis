package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings

/** Small side avatar from the real message role and selected configuration, not a second name row. */
@Composable
internal fun OrbisChatMessageAvatar(
    message: UIMessage,
    model: Model?,
    assistant: Assistant?,
    loading: Boolean,
) {
    val display = LocalSettings.current.displaySetting
    if (message.parts.isEmptyUIMessage()) return
    val user = message.role == MessageRole.USER
    val assistantRole = message.role == MessageRole.ASSISTANT
    if (LocalOrbisDeepSeekStyle.current && !display.deepSeekShowAvatars) return
    val useAssistant = assistant?.useAssistantAvatar == true
    if (user && !display.showUserAvatar) return
    if (!user && (!assistantRole || !display.showModelIcon || (model == null && !useAssistant))) return
    val name = if (user) display.userNickname.ifBlank { stringResource(R.string.user_default_name) }
    else if (useAssistant) assistant!!.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    else model!!.displayName
    Box(
        modifier = Modifier.padding(top = 3.dp).size(27.dp)
            .clip(RoundedCornerShape(10.dp))
            // UIAvatar's non-editable Surface has a no-op click; do not expose it as an action.
            .clearAndSetSemantics { contentDescription = name },
    ) {
        if (user || useAssistant) {
            UIAvatar(
                name = name,
                value = if (user) display.userAvatar else assistant!!.avatar,
                modifier = Modifier.size(27.dp),
                loading = !user && loading,
            )
        } else {
            AutoAIIcon(name = model!!.modelId, modifier = Modifier.size(27.dp), loading = loading)
        }
    }
}

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(28.dp),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    if (message.role == MessageRole.ASSISTANT && (model != null || useAssistantAvatar)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (useAssistantAvatar) {
                if (showIcon) {
                    UIAvatar(
                        name = assistant.name,
                        modifier = Modifier.size(28.dp),
                        value = assistant.avatar,
                        loading = loading,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 1,
                        )
                    }
                }
            } else if (model != null) {
                if (showIcon) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier.size(28.dp),
                        loading = loading
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
