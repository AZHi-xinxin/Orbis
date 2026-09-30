package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.deepSeekWelcomeText
import kotlin.math.roundToInt

internal const val ORBIS_PERSONAL_NICKNAME_MAX_CHARS = 80
internal fun orbisNicknameWithinLimit(value: String): Boolean = value.length <= ORBIS_PERSONAL_NICKNAME_MAX_CHARS

/** A local draft: only Save updates the single existing DisplaySetting.userNickname field. */
@Composable
internal fun OrbisNicknameSetting(nickname: String, onSave: (String) -> Unit) {
    // Do not serialize even a legacy nickname into the Activity saved-state Bundle.
    var draft by remember(nickname) { mutableStateOf(nickname) }
    var rejected by remember(nickname) { mutableStateOf(false) }
    val invalid = rejected || !orbisNicknameWithinLimit(draft)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                rejected = !orbisNicknameWithinLimit(it)
                if (!rejected) draft = it
            },
            label = { Text("我的昵称") },
            placeholder = { Text("填写你想显示的名字") },
            singleLine = true,
            isError = invalid,
            supportingText = { Text(if (rejected) "昵称最多 80 字符，本次超长输入未采用；请缩短后重新填写。"
                else if (invalid) "现有昵称超过 80 字符，请改成较短昵称后保存。"
                else "${draft.length} / 80 字符") },
            modifier = Modifier.fillMaxWidth().testTag("orbis-personal-nickname"),
        )
        Text("欢迎语预览：${deepSeekWelcomeText(draft)}", style = MaterialTheme.typography.bodySmall,
            color = OrbisTheme.colors.mutedInk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (!invalid) { draft = draft.trim(); onSave(draft) } }, enabled = draft != nickname && !invalid,
                modifier = Modifier.testTag("orbis-personal-nickname-save")) { Text("保存昵称") }
            TextButton(onClick = { draft = nickname; rejected = false }, enabled = draft != nickname || rejected,
                modifier = Modifier.testTag("orbis-personal-nickname-cancel")) { Text("取消修改") }
        }
        Text("用于你的显示名称与新聊天欢迎语；保存后所有主题共用。不修改 AI 名称或已有聊天正文。",
            style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
    }
}

/** Each role changes only its override; unset roles still inherit the original saved opacity. */
@Composable
internal fun OrbisBubbleOpacityControls(
    appearance: OrbisAppearance,
    onUpdateAppearance: ((OrbisAppearance) -> OrbisAppearance) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(true, false).forEach { user ->
            val label = if (user) "人类消息" else "AI 消息"
            val value = appearance.bubbleOpacityForRole(user)
            Text("$label ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value,
                onValueChange = { opacity -> onUpdateAppearance { old ->
                    if (user) old.copy(userBubbleOpacity = opacity) else old.copy(assistantBubbleOpacity = opacity)
                } },
                valueRange = 0f..1f,
                steps = 99,
                modifier = Modifier.testTag(if (user) "orbis-user-opacity" else "orbis-assistant-opacity")
                    .semantics { contentDescription = "$label 气泡不透明度，百分之零到百分之一百" },
            )
        }
        Text("两类消息分别保存；Orbis 与 DeepSeek 主题各自保留设置。不会改变输入框或哨兵事件卡的不透明度。",
            style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
    }
}
