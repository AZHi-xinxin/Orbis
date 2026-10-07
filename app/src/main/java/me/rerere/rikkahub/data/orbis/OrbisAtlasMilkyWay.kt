package me.rerere.rikkahub.data.orbis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas

enum class OrbisAtlasDisplayMode(val storedValue: String) {
    LIGHTWEIGHT("lightweight"), GALAXY("galaxy");
    companion object {
        fun fromStored(value: String?): OrbisAtlasDisplayMode = entries.find { it.storedValue == value } ?: LIGHTWEIGHT
    }
}

/**
 * Bounded, deterministic decorative geometry. Only [Layout.memories] participate in hit tests.
 * Research: https://github.com/ggwzrd/threejs-galaxy (MIT), layered spiral dust, not copied code.
 * This original orthographic implementation does not depend on Three.js/WebGL or remote assets.
 */
object OrbisAtlasMilkyWay {
    const val DUST_COUNT = 4200
    const val HAZE_COUNT = 28
    data class Dust(val position: Atlas.Vec3, val group: Int)
    data class Layout(val memories: List<Atlas.Vec3>, val dust: List<Dust>, val haze: List<Atlas.Vec3>)

    fun layout(graph: Atlas.Graph): Layout {
        val base = OrbisAtlasGalaxy.layout(graph)
        if (!base.profile.hasMemories) return Layout(emptyList(), emptyList(), emptyList())
        val p = base.profile
        val dust = List(DUST_COUNT) { i ->
            val core = i % 5 == 0
            val radius = if (core) Atlas.rand(i + 171, p.seed) * .5 else .08 + sqrt(Atlas.rand(i + 701, p.seed)) * 1.22
            val angle = if (core) Atlas.rand(i + 233, p.seed) * PI * 2 else
                (i % p.arms) * PI * 2 / p.arms + radius * p.winding + (Atlas.rand(i + 991, p.seed) - .5) * .8
            Dust(Atlas.Vec3(cos(angle) * radius, 0.0, sin(angle) * radius * p.flattening), i % 8)
        }
        val haze = List(HAZE_COUNT) { i ->
            val radius = .10 + (i / p.arms).toDouble() / (HAZE_COUNT / p.arms).coerceAtLeast(1) * 1.05
            val angle = (i % p.arms) * PI * 2 / p.arms + radius * p.winding
            Atlas.Vec3(cos(angle) * radius, 0.0, sin(angle) * radius * p.flattening)
        }
        return Layout(base.stars, dust, haze)
    }

    /** Orthographic projection keeps drawing and hit testing identical, including large zoom. */
    fun project(position: Atlas.Vec3, camera: Atlas.Camera, width: Double, height: Double, index: Int = -1): Atlas.Projected? {
        if (width <= 0 || height <= 0 || !width.isFinite() || !height.isFinite()) return null
        val c = Atlas.normalized(camera)
        val diskX = position.x * cos(c.yaw) - position.z * sin(c.yaw)
        val diskY = (position.x * sin(c.yaw) + position.z * cos(c.yaw)) * abs(sin(c.pitch)).coerceIn(.23, 1.0)
        val tilt = -PI / 7
        val unit = minOf(width * .44, height * .29) * c.zoom
        return Atlas.Projected(width * .5 + (diskX * cos(tilt) - diskY * sin(tilt)) * unit,
            height * .55 + (diskX * sin(tilt) + diskY * cos(tilt)) * unit, diskY, c.zoom, index)
    }

    fun projectMemories(layout: Layout, camera: Atlas.Camera, width: Double, height: Double): List<Atlas.Projected> =
        layout.memories.mapIndexedNotNull { index, p -> project(p, camera, width, height, index) }
}
