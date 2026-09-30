package me.rerere.rikkahub.ui.pages.orbis

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val StartupNight = Color(0xFF111526)
private val StartupGold = OrbisPalette.Dark.star
private val StartupInk = OrbisPalette.Dark.ink
private val StartupMuted = OrbisPalette.Dark.mutedInk

/** Visual only: the host owns readiness, timeout, lifecycle, touch and background semantics.
 * No minimum display time, startup work, navigation or network access lives in this scene. */
@Composable
internal fun OrbisStartupScene(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    slowLoading: Boolean = false,
    onContinue: () -> Unit = {},
) {
    // Keep the State itself, not its value, in composition. Only the small emblem redraws.
    val breath: State<Float> = if (animate && startupSystemAnimationsEnabled()) {
        rememberStartupBreath()
    } else {
        remember { mutableFloatStateOf(.5f) }
    }
    Box(modifier.fillMaxSize().background(StartupNight)) {
        StartupSky(Modifier.fillMaxSize())
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            val emblemSize = minOf(
                if (maxHeight < 420.dp) 144.dp else 232.dp,
                (maxWidth - 64.dp).coerceAtLeast(80.dp),
            )
            Column(
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StartupEmblem(breath, Modifier.size(emblemSize))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "ORBiS",
                    color = StartupInk,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 42.sp,
                    lineHeight = 54.sp,
                    letterSpacing = 7.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "正在点亮星图",
                    color = StartupMuted,
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )
                if (slowLoading) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "加载比平时稍久，可先进入查看",
                        color = StartupMuted,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onContinue,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StartupGold.copy(alpha = .48f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = StartupGold.copy(alpha = .06f),
                            contentColor = StartupGold,
                        ),
                    ) { Text("进入界面") }
                }
            }
        }
    }
}

@Composable
private fun StartupSky(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1C2037), StartupNight, Color(0xFF201A30))))
        drawRect(Brush.radialGradient(
            colors = listOf(Color(0xFF514264).copy(alpha = .23f), Color.Transparent),
            center = Offset(size.width * .24f, size.height * .28f),
            radius = size.maxDimension * .72f,
        ))
        // Exactly 52 deterministic, stationary points. No random layout or twinkling.
        repeat(52) { index ->
            val x = ((index * 37 + 13) % 103) / 103f
            val y = ((index * 61 + 7) % 107) / 107f
            val point = Offset(size.width * x, size.height * y)
            val quietCenter = x in 0.2f..0.8f && y in 0.3f..0.76f
            val alpha = if (quietCenter) .14f else if (index % 5 == 0) .55f else .3f
            val radius = (if (index % 5 == 0) 1.15f else .65f).dp.toPx()
            if (index % 5 == 0) drawCircle(StartupGold.copy(alpha = .045f), radius * 3.6f, point)
            drawCircle(StartupGold.copy(alpha = alpha), radius, point)
            if (index % 13 == 0) {
                drawLine(StartupGold.copy(alpha = .24f),
                    point - Offset(radius * 2.8f, 0f), point + Offset(radius * 2.8f, 0f), .5.dp.toPx())
                drawLine(StartupGold.copy(alpha = .24f),
                    point - Offset(0f, radius * 2.8f), point + Offset(0f, radius * 2.8f), .5.dp.toPx())
            }
        }
    }
}

@Composable
private fun StartupEmblem(breath: State<Float>, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val pulse = breath.value
        val radius = size.minDimension * .5f
        val haloRadius = radius * (.66f + pulse * .025f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(StartupGold.copy(alpha = .09f + pulse * .025f),
                    Color(0xFFBDA1C8).copy(alpha = .035f), Color.Transparent),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
        )
        drawCircle(StartupGold.copy(alpha = .13f), radius * .72f, style = Stroke(.7.dp.toPx()))
        rotate(-28f) {
            val orbit = Size(radius * 1.88f, radius * .8f)
            val topLeft = center - Offset(orbit.width / 2f, orbit.height / 2f)
            drawOval(StartupGold.copy(alpha = .23f + pulse * .07f), topLeft, orbit,
                style = Stroke(.85.dp.toPx()))
            drawArc(StartupGold.copy(alpha = .4f), 208f, 31f, false, topLeft, orbit,
                style = Stroke(1.25.dp.toPx()))
            val angle = -Math.PI / 5
            val satellite = center + Offset(cos(angle).toFloat() * orbit.width / 2f,
                sin(angle).toFloat() * orbit.height / 2f)
            drawCircle(StartupGold.copy(alpha = .07f), 7.dp.toPx(), satellite)
            drawCircle(StartupGold.copy(alpha = .86f), 2.dp.toPx(), satellite)
        }
        rotate(52f) {
            val orbit = Size(radius * 1.72f, radius * 1.02f)
            drawOval(StartupGold.copy(alpha = .1f + pulse * .035f),
                center - Offset(orbit.width / 2f, orbit.height / 2f), orbit,
                style = Stroke(.65.dp.toPx()))
        }
        val outer = radius * .29f
        val star = Path().apply {
            repeat(10) { index ->
                val angle = -Math.PI / 2 + index * Math.PI / 5
                val length = if (index % 2 == 0) outer else outer * .44f
                val x = center.x + cos(angle).toFloat() * length
                val y = center.y + sin(angle).toFloat() * length
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(star, Brush.linearGradient(
            colors = listOf(Color(0xFFFFF1CF), StartupGold, Color(0xFFD3AE77)),
            start = center - Offset(outer, outer), end = center + Offset(outer, outer),
        ))
        drawPath(star, Color(0xFFFFF4DC).copy(alpha = .5f), style = Stroke(.65.dp.toPx()))
    }
}

@Composable
private fun rememberStartupBreath(): State<Float> {
    val transition = rememberInfiniteTransition(label = "Orbis startup breath")
    // Compose's animation clock applies the system duration scale; do not scale twice.
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Orbit and halo breath",
    )
}

/** Respect changes to the existing system preference; never write it. No observer or
 * transition is created when the host passes animate=false. A failed read stays static. */
@Composable
private fun startupSystemAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = runCatching {
        AndroidSettings.Global.getFloat(resolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(false)
    var enabled by remember(resolver) { mutableStateOf(read()) }
    var observing by remember(resolver) { mutableStateOf(false) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = read() }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                AndroidSettings.Global.getUriFor(AndroidSettings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
            enabled = read()
        }.isSuccess
        observing = registered
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return enabled && observing
}
