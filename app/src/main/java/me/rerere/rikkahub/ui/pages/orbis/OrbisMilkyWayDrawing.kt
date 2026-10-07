package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import me.rerere.rikkahub.data.orbis.OrbisAtlasMilkyWay as MilkyWay
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas

private val DustColors = listOf(Color(0x775C6997), Color(0xAA9B89BF), Color(0x99C7BDDF), Color(0x5596A8CF),
    Color(0xAAE8DDF2), Color(0x88CE96BC), Color(0x776D8CA4), Color(0xDDEAF4FF))
internal data class MilkyWayGlow(val centre: Offset, val radius: Float, val brush: Brush)
internal data class MilkyWayFrame(val memories: List<Atlas.Projected>, val groups: List<List<Offset>>,
    val haze: List<MilkyWayGlow>, val sky: List<Offset>, val core: MilkyWayGlow?)

/** Projection, point batches and gradient objects are reused until camera/viewport changes. */
internal class MilkyWayFrameCache(private val layout: MilkyWay.Layout) {
    private var view: Atlas.Camera? = null
    private var width = 0.0
    private var height = 0.0
    private var cached: MilkyWayFrame? = null
    fun frame(camera: Atlas.Camera, w: Double, h: Double): MilkyWayFrame {
        val normalized = Atlas.normalized(camera)
        cached?.takeIf { view == normalized && width == w && height == h }?.let { return it }
        val groups = List(8) { mutableListOf<Offset>() }
        layout.dust.forEach { dot -> MilkyWay.project(dot.position, normalized, w, h)?.let {
            groups[dot.group].add(Offset(it.x.toFloat(), it.y.toFloat()))
        } }
        val unit = minOf(w * .44, h * .29) * normalized.zoom
        val haze = layout.haze.mapNotNull { position -> MilkyWay.project(position, normalized, w, h)?.let { p ->
            val centre = Offset(p.x.toFloat(), p.y.toFloat())
            val radius = (unit * .25).toFloat().coerceAtLeast(1f)
            MilkyWayGlow(centre, radius, Brush.radialGradient(listOf(Color(0x157D679C), Color.Transparent), centre, radius))
        } }
        val core = if (layout.memories.isEmpty()) null else {
            val centre = Offset((w * .5).toFloat(), (h * .55).toFloat())
            val radius = (unit * .48).toFloat().coerceAtLeast(1f)
            MilkyWayGlow(centre, radius, Brush.radialGradient(0f to Color(0xE5FFF4DB),
                .04f to Color(0xAEDDC2C9), .19f to Color(0x66CD91B1), .52f to Color(0x33635488),
                1f to Color.Transparent, center = centre, radius = radius))
        }
        val sky = List(220) { i -> Offset((Atlas.rand(i * 3 + 1, 715) * w).toFloat(),
            (Atlas.rand(i * 3 + 2, 715) * h).toFloat()) }
        return MilkyWayFrame(MilkyWay.projectMemories(layout, normalized, w, h), groups, haze, sky, core).also {
            view = normalized; width = w; height = h; cached = it
        }
    }
}

/** No per-frame particle projection/sort/path or bitmap allocation. No independent frame clock. */
internal fun DrawScope.drawMilkyWay(frame: MilkyWayFrame, density: Float, width: Double, height: Double,
    selected: Int?, colors: List<Color>, edges: List<Atlas.Edge>) {
    scale(density, density, pivot = Offset.Zero) {
        drawRect(Color(0xFF070A13), size = Size(width.toFloat(), height.toFloat()))
        drawPoints(frame.sky, PointMode.Points, Color(0x55818BAA), strokeWidth = .7f, cap = StrokeCap.Round)
        frame.haze.forEach { drawCircle(it.brush, it.radius, it.centre) }
        frame.core?.let { drawCircle(it.brush, it.radius, it.centre) }
        frame.groups.forEachIndexed { index, points ->
            drawPoints(points, PointMode.Points, DustColors[index], strokeWidth = if (index == 7) .85f else .5f, cap = StrokeCap.Round)
        }
        if (selected != null) edges.forEach { edge ->
            if (edge.a == selected || edge.b == selected) {
                val a = frame.memories.getOrNull(edge.a); val b = frame.memories.getOrNull(edge.b)
                if (a != null && b != null) drawLine(AtlasGold.copy(alpha = .7f),
                    Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), .65f)
            }
        }
        frame.memories.forEach { p ->
            val centre = Offset(p.x.toFloat(), p.y.toFloat())
            val color = colors[p.index]
            val radius = if (p.index == selected) 3.2f else 1.25f
            drawCircle(color.copy(alpha = .13f), radius * 3.5f, centre)
            drawCircle(color.copy(alpha = .30f), radius * 1.9f, centre)
            drawCircle(Color(0xFFF1EDF6), radius, centre)
            if (p.index % 5 == 0 || p.index == selected) {
                drawLine(color.copy(alpha = .55f), centre - Offset(3.4f, 0f), centre + Offset(3.4f, 0f), .45f)
                drawLine(color.copy(alpha = .55f), centre - Offset(0f, 3.4f), centre + Offset(0f, 3.4f), .45f)
            }
            if (p.index == selected) drawCircle(AtlasGold, 10f, centre, style = Stroke(.8f))
        }
    }
}
