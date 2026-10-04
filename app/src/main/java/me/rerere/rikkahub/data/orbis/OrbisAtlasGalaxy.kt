package me.rerere.rikkahub.data.orbis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas

/** Metadata-only presentation. Decorative clouds/dust are never memory nodes or invented edges. */
object OrbisAtlasGalaxy {
    const val DUST_COUNT = 560
    const val CLOUD_COUNT = 42
    const val MAX_FLOW_DP = 38.0
    private const val TAU = PI * 2

    data class Tint(val red: Double, val green: Double, val blue: Double)
    data class Profile(
        val counts: Map<String, Int>,
        val tint: Tint,
        val arms: Int,
        val winding: Double,
        val thickness: Double,
        val flattening: Double,
        val seed: Int,
        val hasMemories: Boolean,
        val sampled: Boolean,
    )
    data class Particle(val position: Atlas.Vec3, val radius: Double, val alpha: Double)
    data class Layout(val profile: Profile, val stars: List<Atlas.Vec3>, val dust: List<Particle>, val clouds: List<Particle>)
    data class Flow(val x: Double = 0.0, val y: Double = 0.0, val dx: Double = 0.0, val dy: Double = 0.0) {
        val moving: Boolean get() = hypot(dx, dy) > .05
    }

    fun tintForType(type: String): Tint = when (type) {
        "情感" -> Tint(.96, .44, .65)
        "学习" -> Tint(.42, .58, .99)
        "规划" -> Tint(.66, .44, .94)
        "工具" -> Tint(.82, .86, .40)
        "工作" -> Tint(.51, .79, .46)
        else -> Tint(.61, .66, .73)
    }

    /** Uses the received snapshot, not a claimed whole-library total. Unknowns remain neutral. */
    fun profile(graph: Atlas.Graph): Profile {
        val counts = graph.stars.groupingBy { it.type }.eachCount().toSortedMap()
        val total = graph.stars.size
        val tint = if (total == 0) tintForType("其他") else {
            fun channel(select: (Tint) -> Double) = counts.entries.sumOf { (type, count) -> select(tintForType(type)) * count } / total
            Tint(channel { it.red }, channel { it.green }, channel { it.blue })
        }
        // Canonical ordering makes a server row reorder visually inert. Metadata changes do alter shape.
        var seed = graph.seed
        graph.stars.sortedBy { it.id }.forEach { star ->
            seed = seed * 31 + star.id.hashCode()
            seed = seed * 31 + star.type.hashCode()
            seed = seed * 31 + star.storedAt.hashCode()
        }
        val edgeIds = graph.edges.mapNotNull { edge ->
            val a = graph.stars.getOrNull(edge.a)?.id ?: return@mapNotNull null
            val b = graph.stars.getOrNull(edge.b)?.id ?: return@mapNotNull null
            if (a == b) null else if (a < b) "$a:$b" else "$b:$a"
        }.distinct().sorted()
        edgeIds.forEach { seed = seed * 31 + it.hashCode() }
        val activeTypes = counts.values.count { it > 0 }
        val relationshipDensity = if (total == 0) 0.0 else (edgeIds.size.toDouble() / total / 3).coerceIn(0.0, 1.0)
        val dominant = if (total == 0) 0.0 else (counts.values.maxOrNull() ?: 0).toDouble() / total
        return Profile(counts, tint, (2 + activeTypes / 2).coerceIn(2, 5),
            winding = 3.1 + (1 - dominant) * 2.2 + Atlas.rand(771, seed) * .8,
            thickness = .04 + relationshipDensity * .09,
            flattening = .8 + Atlas.rand(97, seed) * .18,
            seed = seed, hasMemories = total > 0, sampled = (graph as? Atlas.Snapshot)?.truncated == true)
    }

    fun layout(graph: Atlas.Graph): Layout {
        val profile = profile(graph)
        val stars = graph.stars.map { star ->
            val seed = star.id.hashCode()
            val radius = .16 + sqrt(Atlas.rand(10, seed)) * .98
            val arm = (Atlas.rand(11, seed) * profile.arms).toInt()
            val angle = arm * TAU / profile.arms + radius * profile.winding + (Atlas.rand(12, seed) - .5) * .46
            Atlas.Vec3(cos(angle) * radius, (Atlas.rand(13, seed) - .5) * profile.thickness * 2,
                sin(angle) * radius * profile.flattening)
        }
        // Empty live snapshots have no nebula core or invented memory dots; only the distant sky remains.
        if (!profile.hasMemories) return Layout(profile, stars, emptyList(), emptyList())
        val dust = List(DUST_COUNT) { i ->
            val radius = .06 + sqrt(Atlas.rand(i + 701, profile.seed)) * 1.2
            val arm = i % profile.arms
            val angle = arm * TAU / profile.arms + radius * profile.winding + (Atlas.rand(i + 991, profile.seed) - .5) * .54
            Particle(Atlas.Vec3(cos(angle) * radius, (Atlas.rand(i + 123, profile.seed) - .5) * profile.thickness,
                sin(angle) * radius * profile.flattening), .32 + Atlas.rand(i + 882, profile.seed) * .82,
                .16 + Atlas.rand(i + 97, profile.seed) * .48)
        }
        val clouds = List(CLOUD_COUNT) { i ->
            val radius = .10 + (i / profile.arms).toDouble() / max(1, CLOUD_COUNT / profile.arms) * 1.04
            val angle = (i % profile.arms) * TAU / profile.arms + radius * profile.winding
            Particle(Atlas.Vec3(cos(angle) * radius, .0, sin(angle) * radius * profile.flattening),
                .10 + (1.3 - radius) * .085, .07 + Atlas.rand(i + 770, profile.seed) * .055)
        }
        return Layout(profile, stars, dust, clouds)
    }

    fun projectStars(layout: Layout, camera: Atlas.Camera, width: Double, height: Double): List<Atlas.Projected> {
        val safe = Atlas.normalized(camera)
        return layout.stars.mapIndexedNotNull { index, position ->
            Atlas.project(Atlas.rotate(position, safe.yaw, safe.pitch), width, height, safe.zoom)?.copy(index = index)
        }
    }

    /** Caller owns the only clock; there is no animation or background work in this model. */
    fun orbit(position: Atlas.Vec3, clockSeconds: Double): Atlas.Vec3 {
        if (!clockSeconds.isFinite()) return position
        val radius = hypot(position.x, position.z)
        val angle = (clockSeconds * (.008 / (.45 + radius))) % TAU
        return Atlas.Vec3(position.x * cos(angle) - position.z * sin(angle), position.y,
            position.x * sin(angle) + position.z * cos(angle))
    }

    /** Screen-space touch current, bounded regardless of pointer speed or interrupted events. */
    fun pull(previous: Flow, x: Double, y: Double, dx: Double, dy: Double): Flow {
        if (!listOf(x, y, dx, dy).all(Double::isFinite)) return Flow()
        val rawX = (previous.dx.takeIf(Double::isFinite)?.coerceIn(-MAX_FLOW_DP, MAX_FLOW_DP) ?: 0.0) * .78 + dx.coerceIn(-MAX_FLOW_DP, MAX_FLOW_DP) * .5
        val rawY = (previous.dy.takeIf(Double::isFinite)?.coerceIn(-MAX_FLOW_DP, MAX_FLOW_DP) ?: 0.0) * .78 + dy.coerceIn(-MAX_FLOW_DP, MAX_FLOW_DP) * .5
        val length = hypot(rawX, rawY)
        val scale = if (length > MAX_FLOW_DP) MAX_FLOW_DP / length else 1.0
        return Flow(x, y, rawX * scale, rawY * scale)
    }

    fun settle(flow: Flow, dtSeconds: Double, motionAllowed: Boolean = true): Flow {
        if (!motionAllowed || !listOf(flow.x, flow.y, flow.dx, flow.dy).all(Double::isFinite)) return Flow()
        if (!dtSeconds.isFinite() || dtSeconds <= 0) return flow
        val decay = exp(-dtSeconds.coerceAtMost(.05) * 3.3)
        val next = flow.copy(dx = flow.dx * decay, dy = flow.dy * decay)
        return if (next.moving) next else Flow()
    }

    fun displace(point: Atlas.Projected, flow: Flow, radiusDp: Double = 135.0): Atlas.Projected {
        if (!flow.moving || !radiusDp.isFinite() || radiusDp <= 0 ||
            !listOf(flow.x, flow.y, flow.dx, flow.dy, point.x, point.y).all(Double::isFinite)) return point
        val distance = hypot(point.x - flow.x, point.y - flow.y) / radiusDp
        val weight = exp(-distance * distance * 2.5)
        val magnitude = hypot(flow.dx, flow.dy)
        val bounded = if (magnitude > MAX_FLOW_DP) MAX_FLOW_DP / magnitude else 1.0
        return point.copy(x = point.x + flow.dx * bounded * weight, y = point.y + flow.dy * bounded * weight)
    }
}
