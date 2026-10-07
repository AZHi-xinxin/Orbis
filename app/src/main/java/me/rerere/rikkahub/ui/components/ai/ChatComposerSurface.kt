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
    Surface(modifier = modifier, shape = shape, color = color, contentColor = contentColor,
        border = border, tonalElevation = 0.dp,
        shadowElevation = if (orbis && color.alpha >= 1f) 6.dp else 0.dp,
        content = content)
}

@Composable
internal fun chatInputFieldColors(): TextFieldColors = TextFieldDefaults.colors().copy(
    unfocusedIndicatorColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
)
