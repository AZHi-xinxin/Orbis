package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import kotlin.math.PI

/** All geometry and hit testing use logical dp, matching the original HTML's CSS pixels. */
@Composable
internal fun OrbisMemoryAtlasCanvas(
    demo: Atlas.Graph,
    camera: () -> Atlas.Camera,
    clock: () -> Double,
    selected: Int?,
    motionLabel: String,
    onCamera: (Atlas.Camera) -> Unit,
    onSelected: (Int?) -> Unit,
    onDragging: (Boolean) -> Unit,
    onReset: () -> Unit,
    onToggleMotion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val readCamera by rememberUpdatedState(camera)
    val updateCamera by rememberUpdatedState(onCamera)
    val select by rememberUpdatedState(onSelected)
    val setDragging by rememberUpdatedState(onDragging)
    fun next(direction: Int): Boolean {
        if (demo.stars.isEmpty()) return false
        onSelected(((selected ?: if (direction > 0) -1 else 0) + direction + demo.stars.size) % demo.stars.size)
        return true
    }
    Canvas(modifier.clipToBounds()
        .semantics {
            contentDescription = "记忆星盘，${if (demo.isDemo) "本地演示" else "ST 只读元信息"}，${demo.stars.size} 个星点。可拖动旋转、双指缩放、轻触查看时间与类型。"
            stateDescription = motionLabel
            customActions = listOf(
                CustomAccessibilityAction("下一个星点") { next(1) },
                CustomAccessibilityAction("上一个星点") { next(-1) },
                CustomAccessibilityAction("关闭星点信息") { onSelected(null); true },
                CustomAccessibilityAction("放大星盘") { onCamera(Atlas.zoomTo(camera(), camera().zoom + .15)); true },
                CustomAccessibilityAction("缩小星盘") { onCamera(Atlas.zoomTo(camera(), camera().zoom - .15)); true },
                CustomAccessibilityAction("重置星盘视角") { onReset(); true },
            )
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionRight, Key.DirectionDown -> next(1)
                Key.DirectionLeft, Key.DirectionUp -> next(-1)
                Key.Enter -> next(1)
                Key.Escape -> { onSelected(null); true }
                Key.Spacebar -> { onToggleMotion(); true }
                else -> false
            }
        }
        .focusable()
        .pointerInput(demo, density) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalPan = Offset.Zero
                var transformed = false
                var multiplePointers = false
                var cancelled = false
                var lastPosition = down.position
                setDragging(true)
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                        if (event.changes.count { it.pressed } > 1) multiplePointers = true
                        if (event.changes.none { it.pressed }) {
                            lastPosition = event.changes.firstOrNull { it.id == down.id }?.position ?: lastPosition
                            break
                        }
                        val pan = event.calculatePan()
                        totalPan += pan
                        if (multiplePointers || totalPan.getDistance() > viewConfiguration.touchSlop) transformed = true
                        if (transformed) {
                            var target = readCamera()
                            if (multiplePointers) target = Atlas.zoomBy(target, event.calculateZoom().toDouble())
                            target = Atlas.drag(target, (pan.x / density).toDouble(), (pan.y / density).toDouble())
                            updateCamera(target)
                            event.changes.forEach { it.consume() }
                        }
                        lastPosition = event.changes.firstOrNull { it.id == down.id }?.position ?: lastPosition
                    } while (true)
                    if (!cancelled && !transformed && !multiplePointers) {
                        val points = Atlas.projectStars(demo, readCamera(), (size.width / density).toDouble(), (size.height / density).toDouble())
                        select(Atlas.nearestStar(points, (lastPosition.x / density).toDouble(), (lastPosition.y / density).toDouble()))
                    }
                } finally { setDragging(false) }
            }
        }
    ) {
        val w = (size.width / density).toDouble()
        val h = (size.height / density).toDouble()
        if (w <= 0 || h <= 0) return@Canvas
        val view = Atlas.normalized(camera())
        val time = clock()
        val points = Atlas.projectStars(demo, view, w, h)
        val related = Atlas.relatedIndices(demo, selected)
        // Uniform canvas density scaling preserves sub-dp hairlines and the exact 22dp hit radius.
        scale(density, density, pivot = Offset.Zero) {
            drawRect(Color(0xFF070913), size = androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()))
            drawRect(Brush.radialGradient(
                0f to Color(0xFF302331), .54f to Color(0xFF131323), 1f to Color(0xFF070913),
                center = Offset((w * .5).toFloat(), (h * .47).toFloat()), radius = (h * .58).toFloat()),
                size = androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()))
            repeat(Atlas.BACKGROUND_COUNT) { i ->
                Atlas.backgroundPoint(i, w, h, demo.seed)?.let { p ->
                    drawCircle(Color(0xFFE0C793), p.radius.toFloat(), Offset(p.x.toFloat(), p.y.toFloat()), alpha = p.alpha.toFloat())
                }
            }
            Atlas.rings.forEachIndexed { index, ring ->
                val path = Path()
                repeat(181) { t ->
                    Atlas.pointOnRing(t / 180.0 * PI * 2, ring, view, w, h)?.let { p ->
                        if (t == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                }
                drawPath(path, AtlasGold, alpha = .23f, style = Stroke(if (index == 5) .85f else .55f))
                repeat(120) { tick ->
                    val angle = tick / 120.0 * PI * 2
                    val p = Atlas.pointOnRing(angle, ring, view, w, h)
                    val major = tick % 10 == 0
                    val q = Atlas.pointOnRing(angle, ring.copy(radius = ring.radius + if (major) .026 else .012), view, w, h)
                    if (p != null && q != null) drawLine(AtlasGold, p.offset(), q.offset(), .55f, alpha = if (major) .55f else .25f)
                }
            }
            repeat(Atlas.DUST_COUNT) { i ->
                val dust = Atlas.dustPosition(i, time, demo.seed)
                Atlas.project(Atlas.rotate(dust, view.yaw, view.pitch), w, h, view.zoom)?.let { p ->
                    drawCircle(Color(0xFFEDD3A0), (.3 + Atlas.rand(i + 601, demo.seed) * .85).toFloat(), p.offset(),
                        alpha = if (selected == null) (.17 + Atlas.rand(i + 97, demo.seed) * .48).toFloat() else .12f)
                }
            }
            if (selected != null) {
                val indexed = points.associateBy { it.index }
                demo.edges.filter { it.a == selected || it.b == selected }.forEach { edge ->
                    val a = indexed[edge.a]; val b = indexed[edge.b]
                    if (a != null && b != null) drawLine(Color(0xFFEBCF98), a.offset(), b.offset(), .9f, alpha = .78f)
                }
            }
            points.sortedBy { it.depth }.forEach { p ->
                val color = AtlasColors[Atlas.TYPES.indexOf(demo.stars[p.index].type)]
                val focused = selected == null || p.index in related
                val radius = ((if (p.index == selected) 4.2 else 2.3) * p.scale).toFloat()
                if (focused) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .35f), color.copy(alpha = 0f)),
                    center = p.offset(), radius = radius + 9f), radius + 9f, p.offset())
                drawCircle(color, radius, p.offset(), alpha = if (focused) .95f else .15f)
                if (focused) {
                    val reach = radius * 2.6f
                    drawLine(Color(0xFFFFF0CC), p.offset() - Offset(reach, 0f), p.offset() + Offset(reach, 0f), .65f, alpha = .7f)
                    drawLine(Color(0xFFFFF0CC), p.offset() - Offset(0f, reach), p.offset() + Offset(0f, reach), .65f, alpha = .7f)
                }
                if (p.index == selected) drawCircle(Color(0xFFEDCE92), 13f, p.offset(), alpha = .8f,
                    style = Stroke(.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
            }
        }
    }
}

private fun Atlas.Projected.offset() = Offset(x.toFloat(), y.toFloat())
