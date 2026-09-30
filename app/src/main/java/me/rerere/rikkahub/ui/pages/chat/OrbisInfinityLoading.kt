package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** Calm Orbis mark: no perpetual motion, and compatible with reduced-motion preferences. */
@Composable
internal fun OrbisInfinityLoading(modifier: Modifier = Modifier) {
    val color = OrbisTheme.colors.accent
    Canvas(modifier.semantics { contentDescription = "Orbis 正在回复" }) {
        val path = Path().apply {
            moveTo(size.width * .5f, size.height * .5f)
            cubicTo(size.width * .15f, -size.height * .02f, -size.width * .08f,
                size.height * .86f, size.width * .29f, size.height * .75f)
            cubicTo(size.width * .56f, size.height * .67f, size.width * .66f,
                size.height * .1f, size.width * .87f, size.height * .28f)
            cubicTo(size.width * 1.12f, size.height * .5f, size.width * .81f,
                size.height * 1.04f, size.width * .5f, size.height * .5f)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
