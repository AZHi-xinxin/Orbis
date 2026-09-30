package me.rerere.rikkahub.ui.pages.chat

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** Scoped to the active chat entry; no polling, delivery, or network-health interpretation. */
internal val LocalOrbisChatAnimationVisible = staticCompositionLocalOf { true }

internal fun shouldAnimateOrbisHeartbeat(
    visible: Boolean,
    resumed: Boolean,
    reduceMotion: Boolean,
    systemAnimationsEnabled: Boolean,
): Boolean = visible && resumed && !reduceMotion && systemAnimationsEnabled

@Composable
internal fun OrbisHeartbeatLoading(
    visible: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val systemAnimations = rememberHeartbeatSystemAnimations()
    OrbisHeartbeatIndicator(
        visible = visible,
        animate = shouldAnimateOrbisHeartbeat(visible, lifecycle.isAtLeast(Lifecycle.State.RESUMED),
            reduceMotion, systemAnimations),
        modifier = modifier,
        textColor = textColor,
    )
}

/** Pure presentation also used by synthetic tests. No click action or request callback exists. */
@Composable
internal fun OrbisHeartbeatIndicator(
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
) {
    if (!visible) return
    val heartbeatColor = if (LocalOrbisDeepSeekStyle.current) OrbisTheme.colors.accent else Color(0xFFE8995C)
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "Orbis heartbeat")
        val sweep by transition.animateFloat(
            initialValue = -.25f, targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
            label = "ECG sweep",
        )
        sweep
    } else .5f
    Column(
        modifier = modifier.widthIn(max = 220.dp).fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "等待回复" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(40.dp).testTag("orbis-heartbeat-canvas")) {
            val peach = heartbeatColor
            val heartSize = minOf(16.dp.toPx(), size.width * .1f)
            fun heart(left: Float) = Path().apply {
                val top = (size.height - heartSize) / 2f
                fun x(v: Float) = left + v * heartSize
                fun y(v: Float) = top + v * heartSize
                moveTo(x(.5f), y(.9f))
                cubicTo(x(.35f), y(.78f), x(.03f), y(.55f), x(.03f), y(.32f))
                cubicTo(x(.03f), y(.05f), x(.36f), y(.02f), x(.5f), y(.24f))
                cubicTo(x(.64f), y(.02f), x(.97f), y(.05f), x(.97f), y(.32f))
                cubicTo(x(.97f), y(.55f), x(.65f), y(.78f), x(.5f), y(.9f))
                close()
            }
            drawPath(heart(0f), peach)
            drawPath(heart(size.width - heartSize), peach)
            val left = heartSize + minOf(8.dp.toPx(), size.width * .05f)
            val lineWidth = (size.width - left * 2f).coerceAtLeast(0f)
            // Original Orbis asset: 150x48 SVG polyline, with a quiet base and a 2.5s sweep.
            val points = listOf(0f to 24f, 34f to 24f, 42f to 6f, 50f to 42f,
                58f to 14f, 66f to 24f, 150f to 24f)
            val line = Path().apply {
                points.forEachIndexed { index, (x, y) ->
                    val px = left + x / 150f * lineWidth
                    val py = y / 48f * size.height
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(line, peach.copy(alpha = if (animate) .32f else .85f), style = stroke)
            if (animate && lineWidth > 0f) {
                val center = left + phase * lineWidth
                val half = lineWidth * .16f
                drawPath(line, Brush.linearGradient(
                    listOf(peach.copy(alpha = 0f), peach, peach.copy(alpha = 0f)),
                    start = Offset(center - half, 0f), end = Offset(center + half, 0f),
                ), style = stroke)
            }
        }
        Text("等待回复", style = MaterialTheme.typography.labelSmall,
            color = textColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun rememberHeartbeatSystemAnimations(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember(resolver, lifecycle) { mutableStateOf(false) }
    DisposableEffect(resolver, lifecycle) {
        fun refresh() {
            enabled = runCatching {
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(false)
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }.isSuccess
        val lifecycleObserver = LifecycleEventObserver { _, _ -> if (registered) refresh() }
        lifecycle.addObserver(lifecycleObserver)
        if (registered) refresh()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            if (registered) resolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}
