package com.lover.connect.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared Orbis/LC switch: grey four-point star off, illuminated gold five-point star on.
 * Shape as well as color communicates state. The visual can be small; its target remains 48 dp.
 * A null callback is a non-interactive indicator for a parent-owned toggle, like Material Switch.
 * This component owns no state, persistence, permission request or service lifecycle.
 */
@Composable
fun StarSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 28.dp,
) {
    val vertices = remember(checked) { starSwitchVertices(checked) }
    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }
    }
    Box(
        modifier = modifier
            .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(iconSize.coerceIn(20.dp, 40.dp))) {
            val alpha = if (enabled) 1f else 0.38f
            val radius = size.minDimension * 0.43f
            val path = Path().apply {
                vertices.forEachIndexed { index, vertex ->
                    val x = center.x + vertex.x * radius
                    val y = center.y + vertex.y * radius
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            if (checked) {
                drawCircle(Color(0xFFE5B74E), radius * 1.13f, alpha = alpha * 0.12f)
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(listOf(Color(0xFFFFEBA0), Color(0xFFE4B347), Color(0xFFD29A27))),
                    alpha = alpha,
                )
                drawPath(path, Color(0xFF9D711A), alpha = alpha, style = Stroke(0.8.dp.toPx()))
            } else {
                drawPath(path, Color(0xFF8B8D93), alpha = alpha)
                drawPath(path, Color(0xFF686B73), alpha = alpha, style = Stroke(0.7.dp.toPx()))
            }
        }
    }
}
