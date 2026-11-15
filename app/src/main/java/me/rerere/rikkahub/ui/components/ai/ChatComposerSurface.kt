package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Translucent surfaces must not expose the interior of Android's elevation shadow. */
@Composable
internal fun ChatComposerSurface(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color,
    border: BorderStroke,
    orbis: Boolean,
    content: @Composable () -> Unit,
) {
    me.rerere.rikkahub.ui.components.message.ThemeChromeSurface(role = "composer", enabled = orbis,
        modifier = modifier, shape = shape, radius = 23, color = color, contentColor = contentColor,
        border = border,
        shadowElevation = if (orbis && color.alpha >= 1f) 6.dp else 0.dp,
        content = content)
}

@Composable
internal fun chatInputFieldColors(): TextFieldColors {
    val theme = me.rerere.rikkahub.ui.pages.orbis.LocalChatTheme.current?.body
    val base = TextFieldDefaults.colors()
    val ink = theme?.let { Color(me.rerere.rikkahub.data.orbis.theme.themeArgb(it.chrome.onComposer ?: it.colors.text)) }
    return base.copy(
    focusedTextColor = ink ?: base.focusedTextColor,
    unfocusedTextColor = ink ?: base.unfocusedTextColor,
    cursorColor = ink ?: base.cursorColor,
    focusedPlaceholderColor = ink?.copy(alpha=.65f) ?: base.focusedPlaceholderColor,
    unfocusedPlaceholderColor = ink?.copy(alpha=.65f) ?: base.unfocusedPlaceholderColor,
    focusedLeadingIconColor = ink ?: base.focusedLeadingIconColor,
    unfocusedLeadingIconColor = ink ?: base.unfocusedLeadingIconColor,
    focusedTrailingIconColor = ink ?: base.focusedTrailingIconColor,
    unfocusedTrailingIconColor = ink ?: base.unfocusedTrailingIconColor,
    unfocusedIndicatorColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
)
}
