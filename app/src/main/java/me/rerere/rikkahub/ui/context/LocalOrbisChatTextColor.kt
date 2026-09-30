package me.rerere.rikkahub.ui.context

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Provided only inside an Orbis message bubble, never by the application theme. */
internal val LocalOrbisChatTextColor = staticCompositionLocalOf<Color?> { null }

/** Markdown resolves TextStyle before LocalContentColor, so prose needs an explicit style. */
@Composable
internal fun orbisChatTextStyle(base: TextStyle = LocalTextStyle.current): TextStyle =
    LocalOrbisChatTextColor.current?.let { base.copy(color = it) } ?: base

/** Code has its own filled surface and syntax palette, not the chat wallpaper. */
@Composable
internal fun PreserveOrbisCodeContrast(content: @Composable () -> Unit) {
    if (LocalOrbisChatTextColor.current == null) {
        content()
    } else {
        val surfaceInk = MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(
            LocalOrbisChatTextColor provides null,
            LocalContentColor provides surfaceInk,
            LocalTextStyle provides LocalTextStyle.current.copy(color = surfaceInk),
            content = content,
        )
    }
}
