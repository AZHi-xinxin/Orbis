package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.orbis.desire.desiresOverflow

/** Decorative only: no pointer handler, no private prose, and no loop while hidden/reduced-motion. */
@Composable internal fun OrbisDesireGlow(count: Int, animate: Boolean, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    if (!desiresOverflow(count)) return
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label="desire glow")
        val amount by transition.animateFloat(.24f,.52f,infiniteRepeatable(tween(2400),RepeatMode.Reverse),label="breath")
        amount
    } else .35f
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color.Transparent,Color(0xffba5966).copy(alpha=alpha)),
                center=Offset(size.width/2,size.height/2),radius=maxOf(size.width,size.height)*.72f))
        }
        if (showLabel) Text("想要的有很多…", color=Color(0xffffcad1).copy(alpha=(alpha+.45f).coerceAtMost(1f)),
            fontSize=14.sp, modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=44.dp))
    }
}
