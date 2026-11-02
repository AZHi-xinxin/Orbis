package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.ui.hooks.ChatInputState

/** Uses the composer's background and foreground, including its adjustable opacity. */
@Composable
internal fun HumanThoughtInput(state: ChatInputState) {
    val ink = LocalContentColor.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var thoughtFocused by remember(state) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("human-thought-editor")) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (state.humanThoughtExpanded) "收起思考链输入" else "展开思考链输入",
                ) {
                    if (state.humanThoughtExpanded && thoughtFocused) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                    state.humanThoughtExpanded = !state.humanThoughtExpanded
                }
                .testTag("human-thought-input-toggle")
                .semantics {
                    stateDescription = if (state.humanThoughtExpanded) "已展开" else "已折叠"
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HugeIcons.Idea01, null, Modifier.size(16.dp))
            Text("思考链", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            Text(if (state.humanThoughtContent.text.isNotBlank()) "已填写" else "选填",
                style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = ink.alpha * .7f))
            Icon(if (state.humanThoughtExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                null, Modifier.size(16.dp))
        }
        if (state.humanThoughtExpanded) {
            TextField(
                state = state.humanThoughtContent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .onFocusChanged { thoughtFocused = it.isFocused }
                    .testTag("human-thought-input"),
                placeholder = { Text("写下想分享的想法，可以留空") },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                textStyle = MaterialTheme.typography.bodySmall,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = chatInputFieldColors().copy(
                    focusedTextColor = ink,
                    unfocusedTextColor = ink,
                    cursorColor = ink,
                    focusedPlaceholderColor = ink.copy(alpha = ink.alpha * .6f),
                    unfocusedPlaceholderColor = ink.copy(alpha = ink.alpha * .6f),
                ),
            )
        }
    }
}
