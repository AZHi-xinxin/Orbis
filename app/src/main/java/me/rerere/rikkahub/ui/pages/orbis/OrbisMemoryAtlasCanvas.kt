package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import me.rerere.rikkahub.data.orbis.OrbisAtlasGalaxy as Galaxy
import me.rerere.rikkahub.data.orbis.OrbisAtlasMilkyWay as MilkyWay
import me.rerere.rikkahub.data.orbis.OrbisAtlasDisplayMode
import kotlin.math.PI
import kotlin.math.min

/** Native, bounded drawing: no WebView, bitmap texture, network, or private memory prose. */
@Composable
internal fun OrbisMemoryAtlasCanvas(
    demo: Atlas.Graph,
    camera: () -> Atlas.Camera,
    clock: () -> Double,
    selected: Int?,
    motionLabel: String,
    motionAllowed: Boolean,
    onCamera: (Atlas.Camera) -> Unit,
    onSelected: (Int?) -> Unit,
    onDragging: (Boolean) -> Unit,
    onReset: () -> Unit,
    onToggleMotion: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: OrbisAtlasDisplayMode = OrbisAtlasDisplayMode.LIGHTWEIGHT,
    sourceDescription: String? = null,
    typeColor: ((String) -> Color)? = null,
) {
    val density = LocalDensity.current.density
    val galaxy = remember(demo) { Galaxy.layout(demo) }
    val milkyWay = remember(demo, displayMode) {
        if (displayMode == OrbisAtlasDisplayMode.GALAXY) MilkyWay.layout(demo)
        else MilkyWay.Layout(emptyList(), emptyList(), emptyList())
    }
    val milkyWayCache = remember(milkyWay) { MilkyWayFrameCache(milkyWay) }
    val starColors = remember(demo, typeColor) {
        demo.stars.map { typeColor?.invoke(it.type) ?: Galaxy.tintForType(it.type).color() }
    }
    val tint = remember(galaxy, starColors, typeColor) {
        if (typeColor == null || starColors.isEmpty()) galaxy.profile.tint.color()
        else Color(starColors.sumOf { it.red.toDouble() }.toFloat() / starColors.size,
            starColors.sumOf { it.green.toDouble() }.toFloat() / starColors.size,
            starColors.sumOf { it.blue.toDouble() }.toFloat() / starColors.size)
    }
    val related = remember(demo, selected) { Atlas.relatedIndices(demo, selected) }
    val relatedEdges = remember(demo, selected) { demo.edges.filter { it.a == selected || it.b == selected } }
    val readCamera by rememberUpdatedState(camera)
    val updateCamera by rememberUpdatedState(onCamera)
    val select by rememberUpdatedState(onSelected)
    val setDragging by rememberUpdatedState(onDragging)
    val allowMotion by rememberUpdatedState(motionAllowed)
    var flow by remember(demo) { mutableStateOf(Galaxy.Flow()) }
    var touching by remember { mutableStateOf(false) }
    // The spring has no permanent clock. It is cancelled on exit, background, pause/reduced motion.
    LaunchedEffect(touching, motionAllowed, demo) {
        if (!motionAllowed) { flow = Galaxy.Flow(); return@LaunchedEffect }
        if (touching) return@LaunchedEffect
        var last = 0L
        while (isActive && flow.moving) withFrameNanos { now ->
            if (last == 0L) last = now
            else if (now - last >= 30_000_000L) {
                flow = Galaxy.settle(flow, (now - last) / 1_000_000_000.0)
                last = now
            }
        }
    }
    fun next(direction: Int): Boolean {
        if (demo.stars.isEmpty()) return false
        onSelected(((selected ?: if (direction > 0) -1 else 0) + direction + demo.stars.size) % demo.stars.size)
        return true
    }
    Canvas(modifier.clipToBounds()
        .semantics {
            contentDescription = "记忆星系，${sourceDescription ?: if (demo.isDemo) "本地演示" else "ST 只读元信息"}，${demo.stars.size} 个记忆星点。星云和微光为装饰，不代表额外记忆。拖动轻牵星云并旋转、双指缩放、轻触星点查看${if (sourceDescription == null) "时间与类型" else "元信息"}。"
            stateDescription = motionLabel
            customActions = listOf(
                CustomAccessibilityAction("下一个星点") { next(1) },
                CustomAccessibilityAction("上一个星点") { next(-1) },
                CustomAccessibilityAction("关闭星点信息") { onSelected(null); true },
                CustomAccessibilityAction("放大星盘") { onCamera(Atlas.zoomTo(camera(), camera().zoom + .15)); true },
                CustomAccessibilityAction("缩小星盘") { onCamera(Atlas.zoomTo(camera(), camera().zoom - .15)); true },
                CustomAccessibilityAction("重置星盘视角") { flow = Galaxy.Flow(); onReset(); true },
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
        .pointerInput(galaxy, density, displayMode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalPan = Offset.Zero
                var transformed = false
                var multiplePointers = false
                var cancelled = false
                var lastPosition = down.position
                touching = true
                setDragging(true)
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                        if (event.changes.count { it.pressed } > 1) multiplePointers = true
                        lastPosition = event.changes.firstOrNull { it.id == down.id }?.position ?: lastPosition
                        if (event.changes.none { it.pressed }) break
                        val pan = event.calculatePan()
                        totalPan += pan
                        if (multiplePointers || totalPan.getDistance() > viewConfiguration.touchSlop) transformed = true
                        if (transformed) {
                            var target = readCamera()
                            if (multiplePointers) {
                                target = Atlas.zoomBy(target, event.calculateZoom().toDouble())
                                flow = Galaxy.Flow()
                            } else if (allowMotion) {
                                flow = Galaxy.pull(flow, (lastPosition.x / density).toDouble(), (lastPosition.y / density).toDouble(),
                                    (pan.x / density).toDouble(), (pan.y / density).toDouble())
                            }
                            // Keep existing manual rotation, softened so the touch current remains delicate.
                            target = Atlas.drag(target, (pan.x / density * .36).toDouble(), (pan.y / density * .36).toDouble())
                            updateCamera(target)
                            event.changes.forEach { it.consume() }
                        }
                    } while (true)
                    if (!cancelled && !transformed && !multiplePointers) {
                        val points = if (displayMode == OrbisAtlasDisplayMode.GALAXY)
                            MilkyWay.projectMemories(milkyWay, readCamera(), (size.width / density).toDouble(), (size.height / density).toDouble())
                        else Galaxy.projectStars(galaxy, readCamera(), (size.width / density).toDouble(), (size.height / density).toDouble())
                            .map { Galaxy.displace(it, flow) }
                        select(Atlas.nearestStar(points, (lastPosition.x / density).toDouble(), (lastPosition.y / density).toDouble()))
                    }
                } finally {
                    touching = false
                    setDragging(false)
                    if (cancelled || !allowMotion) flow = Galaxy.Flow()
                }
            }
        }
    ) {
        val w = (size.width / density).toDouble()
        val h = (size.height / density).toDouble()
        if (w <= 0 || h <= 0) return@Canvas
        val view = Atlas.normalized(camera())
        if (displayMode == OrbisAtlasDisplayMode.GALAXY) {
            drawMilkyWay(milkyWayCache.frame(view, w, h), density, w, h, selected, starColors, demo.edges)
            return@Canvas
        }
        val time = clock()
        val touch = if (motionAllowed) flow else Galaxy.Flow()
        val points = Galaxy.projectStars(galaxy, view, w, h).map { Galaxy.displace(it, touch) }
        val pointByIndex = if (selected == null) emptyMap() else points.associateBy { it.index }
        val unit = min(w * .38, h * .26) * view.zoom
        fun project(position: Atlas.Vec3) = Atlas.project(Atlas.rotate(position, view.yaw, view.pitch), w, h, view.zoom)
            ?.let { Galaxy.displace(it, touch) }
        // dp geometry and hit testing remain identical at every screen density.
        scale(density, density, pivot = Offset.Zero) {
            drawRect(Color(0xFF050912), size = Size(w.toFloat(), h.toFloat()))
            drawRect(Brush.radialGradient(
                0f to tint.copy(alpha = .13f), .6f to Color(0xFF101323).copy(alpha = .42f), 1f to Color.Transparent,
                center = Offset((w * .5).toFloat(), (h * .49).toFloat()), radius = (h * .58).toFloat()),
                size = Size(w.toFloat(), h.toFloat()))
            repeat(Atlas.BACKGROUND_COUNT) { i ->
                Atlas.backgroundPoint(i, w, h, 0)?.let { p ->
                    drawCircle(if (i % 7 == 0) tint else Color(0xFFCAD8EE), p.radius.toFloat(),
                        Offset(p.x.toFloat(), p.y.toFloat()), alpha = p.alpha.toFloat() * .82f)
                }
            }
            // Three fine astrolabe rings live in the same tilted plane as the spiral disk.
            listOf(.57, .94, 1.34).forEachIndexed { ringIndex, radius ->
                val path = Path()
                repeat(121) { tick ->
                    val angle = tick / 120.0 * PI * 2
                    project(Atlas.Vec3(kotlin.math.cos(angle) * radius, -.05, kotlin.math.sin(angle) * radius))?.let { p ->
                        if (tick == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                }
                drawPath(path, AtlasGold, alpha = if (ringIndex == 2) .22f else .11f, style = Stroke(.5f))
                if (ringIndex == 2) repeat(72) { tick ->
                    val angle = tick / 72.0 * PI * 2
                    val major = tick % 6 == 0
                    val p = project(Atlas.Vec3(kotlin.math.cos(angle) * radius, -.05, kotlin.math.sin(angle) * radius))
                    val outer = radius + if (major) .033 else .014
                    val q = project(Atlas.Vec3(kotlin.math.cos(angle) * outer, -.05, kotlin.math.sin(angle) * outer))
                    if (p != null && q != null) drawLine(AtlasGold, p.offset(), q.offset(), .6f, alpha = if (major) .45f else .22f)
                }
            }
            galaxy.clouds.forEach { cloud ->
                project(Galaxy.orbit(cloud.position, time))?.let { p ->
                    val radius = (cloud.radius * unit * p.scale).toFloat().coerceAtLeast(1f)
                    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = cloud.alpha.toFloat()), tint.copy(alpha = 0f)),
                        center = p.offset(), radius = radius), radius, p.offset())
                }
            }
            if (galaxy.profile.hasMemories) project(Atlas.Vec3(0.0, 0.0, 0.0))?.let { core ->
                val radius = (unit * .4).toFloat().coerceAtLeast(1f)
                drawCircle(Brush.radialGradient(0f to Color(0xFFFFF0D7).copy(alpha = .38f),
                    .18f to tint.copy(alpha = .25f), 1f to tint.copy(alpha = 0f), center = core.offset(), radius = radius),
                    radius, core.offset())
            }
            galaxy.dust.forEachIndexed { i, dust ->
                project(Galaxy.orbit(dust.position, time))?.let { p ->
                    drawCircle(if (i % 4 == 0) Color(0xFFE9E1F0) else tint, (dust.radius * p.scale).toFloat(),
                        p.offset(), alpha = if (selected == null) dust.alpha.toFloat() else dust.alpha.toFloat() * .45f)
                }
            }
            relatedEdges.forEach { edge ->
                val a = pointByIndex[edge.a]; val b = pointByIndex[edge.b]
                if (a != null && b != null) drawLine(Color(0xFFEBCF98), a.offset(), b.offset(), .8f, alpha = .74f)
            }
            val stride = ((points.size + 69) / 70).coerceAtLeast(1)
            points.sortedBy { it.depth }.forEach { p ->
                val color = starColors[p.index]
                val focused = selected == null || p.index in related
                val radius = ((if (p.index == selected) 4.0 else if (points.size > 500) 1.55 else 2.25) * p.scale).toFloat()
                // All metadata nodes stay visible/tappable; expensive halos are capped at ~70 per frame.
                val halo = focused && (p.index == selected || p.index % stride == 0)
                if (halo) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .34f), color.copy(alpha = 0f)),
                    center = p.offset(), radius = radius + 8f), radius + 8f, p.offset())
                drawCircle(color, radius, p.offset(), alpha = if (focused) .94f else .20f)
                if (halo) {
                    val reach = radius * 2.4f
                    drawLine(Color(0xFFF7EEDD), p.offset() - Offset(reach, 0f), p.offset() + Offset(reach, 0f), .55f, alpha = .72f)
                    drawLine(Color(0xFFF7EEDD), p.offset() - Offset(0f, reach), p.offset() + Offset(0f, reach), .55f, alpha = .72f)
                }
                if (p.index == selected) drawCircle(Color(0xFFEDCE92), 13f, p.offset(), alpha = .8f,
                    style = Stroke(.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
            }
        }
    }
}

internal fun Galaxy.Tint.color() = Color(red.toFloat(), green.toFloat(), blue.toFloat())
private fun Atlas.Projected.offset() = Offset(x.toFloat(), y.toFloat())
