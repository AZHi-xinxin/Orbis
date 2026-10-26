package me.rerere.rikkahub.ui.components.message

import android.animation.ValueAnimator
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.data.orbis.theme.*

internal val LocalThemeMotionClock = staticCompositionLocalOf<State<Long>?> { null }

/** One draw-only clock per visible chat, not a timer per message or a background service. */
@Composable internal fun ThemeMotionProvider(enabled: Boolean, content: @Composable () -> Unit) {
    val time = remember { mutableLongStateOf(0L) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(enabled,lifecycle,focused) {
        if(enabled && focused && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()) {
            val start=withFrameNanos { it }; val previous=time.longValue
            while(isActive) {
                time.longValue=previous+(withFrameNanos { it }-start)/1_000_000
                delay(32)
            }
        }
    }
    CompositionLocalProvider(LocalThemeMotionClock provides if(enabled)time else null, content=content)
}

internal fun List<ThemePathNode>.asThemePath(sx: Float=1f, sy: Float=1f) = Path().also { path ->
    forEach { (op,v) -> when(op) {
        'M' -> path.moveTo(v[0]*sx,v[1]*sy)
        'L' -> path.lineTo(v[0]*sx,v[1]*sy)
        'Q' -> path.quadraticTo(v[0]*sx,v[1]*sy,v[2]*sx,v[3]*sy)
        'C' -> path.cubicTo(v[0]*sx,v[1]*sy,v[2]*sx,v[3]*sy,v[4]*sx,v[5]*sy)
        'Z' -> path.close()
    } }
}
internal fun ThemeGradient.brush(alpha: Float=1f, sx: Float=1f, sy: Float=1f) = Brush.linearGradient(
    colors.map { Color(themeArgb(it)).copy(alpha=alpha) },Offset(x1*sx,y1*sy),Offset(x2*sx,y2*sy))

internal fun DrawScope.drawThemeLayers(drawing: ThemeDrawing, paths: List<Path>, elapsedMs: Long, opacity: Float) {
    drawing.layers.forEachIndexed { i, layer ->
        val frame=layer.motion?.at(elapsedMs) ?: ThemeKeyframe(0f)
        withTransform({
            scale(size.width/100,size.height/100,Offset.Zero)
            translate(frame.x,frame.y)
            rotate(frame.rotation,Offset(50f,50f));scale(frame.scale,frame.scale,Offset(50f,50f))
        }) {
            val alpha=opacity*layer.opacity*frame.opacity
            if(layer.gradient!=null) drawPath(paths[i],layer.gradient.brush(),alpha=alpha)
            else layer.fill?.let { drawPath(paths[i],Color(themeArgb(it)),alpha=alpha) }
            if(layer.strokeWidth>0) layer.stroke?.let { drawPath(paths[i],Color(themeArgb(it)),alpha=alpha,style=Stroke(layer.strokeWidth)) }
        }
    }
}
