package me.rerere.rikkahub.data.orbis

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry port of v05-stars.js. Demo and validated real metadata have distinct provenance.
 * No text content/title/summary, network, storage, model call, or autonomous clock enters this API.
 * Callers own lifecycle/reduced-motion handling; animation advances only when they call advance.
 */
object OrbisMemoryAtlas {
    const val STAR_COUNT = 56
    const val BACKGROUND_COUNT = 260
    const val DUST_COUNT = 680
    const val MIN_ZOOM = .55
    const val MAX_ZOOM = 2.0
    const val AUTO_YAW_RADIANS_PER_SECOND = .025
    const val MAX_FRAME_SECONDS = .05
    private const val TAU = 2.0 * PI
    private const val CAMERA_DISTANCE = 3.8
    private const val NEAR_DISTANCE = .05
    val TYPES = listOf("学习", "规划", "情感", "其他")
    // Never reinterpret an old adapter's "其他" as work or tools.
    val SUPPORTED_TYPES = TYPES + listOf("工具", "工作")
    private val storedAtFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    data class Star(val id: String, val type: String, val storedAt: String)
    data class Edge(val a: Int, val b: Int)
    sealed interface Graph {
        val stars: List<Star>
        val edges: List<Edge>
        val seed: Int
        val isDemo: Boolean
    }
    @ConsistentCopyVisibility
    data class Demo internal constructor(override val stars: List<Star>, override val edges: List<Edge>, override val seed: Int) : Graph {
        override val isDemo: Boolean get() = true
        val source: String get() = "demo_not_real_memory"
    }
    @ConsistentCopyVisibility
    data class Snapshot internal constructor(override val stars: List<Star>, override val edges: List<Edge>,
        val generatedAt: String, val truncated: Boolean, override val seed: Int = 0) : Graph {
        override val isDemo: Boolean get() = false
        val source: String get() = "st_metadata_endpoint"
    }
    data class Vec3(val x: Double, val y: Double, val z: Double)
    data class Projected(val x: Double, val y: Double, val depth: Double, val scale: Double, val index: Int = -1)
    data class Camera(val yaw: Double = -.3, val pitch: Double = -.62, val zoom: Double = 1.0)
    data class Ring(val radius: Double, val tilt: Double, val axis: Double)
    data class BackgroundPoint(val x: Double, val y: Double, val radius: Double, val alpha: Double)

    val rings: List<Ring> = List(7) { ring -> Ring(.5 + ring * .115, (ring % 3 - 1) * .68, ring * .52) }

    /** Default seed keeps the prototype's exact 56 metadata rows and 75 unlabeled edges. */
    fun demo(seed: Int = 0): Demo {
        val stars = List(STAR_COUNT) { i ->
            val time = LocalDateTime.of(2026, 2 + i % 7, 1 + (i * 7 % 27), 8 + i % 9, i % 60)
                .toInstant(ZoneOffset.UTC)
            Star("star-$i", TYPES[i % 4], storedAtFormat.format(time))
        }
        val edges = buildList {
            repeat(STAR_COUNT) { i ->
                add(Edge(i, (i + 9) % STAR_COUNT))
                if (i % 3 == 0) add(Edge(i, (i + 19) % STAR_COUNT))
            }
        }
        return metadataOnly(stars, edges, seed)
    }

    /** v05-core.js whitelist contract, kept demo-only until a separate real-data protocol exists.
     * IDs are regenerated; edges have endpoints only. Unsupported types/times fail closed.
     */
    fun metadataOnly(stars: List<Star>, edges: List<Edge>, seed: Int = 0): Demo {
        val safeStars = stars.take(2000).mapIndexed { index, star ->
            require(star.type in SUPPORTED_TYPES && star.storedAt.length in 1..80) { "invalid_star_metadata" }
            val time = try { Instant.parse(star.storedAt) }
            catch (_: Exception) { throw IllegalArgumentException("invalid_star_metadata") }
            Star("star-$index", star.type, storedAtFormat.format(time))
        }
        val safeEdges = edges.asSequence().filter {
            it.a in safeStars.indices && it.b in safeStars.indices && it.a != it.b
        }.take(5000).map { Edge(it.a, it.b) }.toList()
        return Demo(safeStars, safeEdges, seed)
    }

    /** Seed zero exactly preserves Math.sin(i*127.1+31.7)*43758.5453 from the prototype. */
    fun rand(index: Int, seed: Int = 0): Double {
        val i = index.toDouble() + seed.toDouble() * 4099.0
        val n = sin(i * 127.1 + 31.7) * 43758.5453
        return n - floor(n)
    }

    fun starPosition(index: Int, seed: Int = 0): Vec3 {
        require(index in 0 until 2000) { "invalid_star_index" }
        val angle = index * 2.3999632297
        val radius = .38 + (index % 7) * .108
        return Vec3(cos(angle) * radius, (rand(index + 57, seed) - .5) * .7, sin(angle) * radius)
    }

    fun rotate(v: Vec3, yaw: Double = -.3, pitch: Double = -.62): Vec3 {
        require(v.finite() && yaw.isFinite() && pitch.isFinite()) { "invalid_atlas_vector" }
        val angle = yaw % TAU
        val tilt = pitch % TAU
        val x1 = v.x * cos(angle) - v.z * sin(angle)
        val z1 = v.x * sin(angle) + v.z * cos(angle)
        return Vec3(x1, v.y * cos(tilt) - z1 * sin(tilt), v.y * sin(tilt) + z1 * cos(tilt)).also {
            require(it.finite()) { "invalid_atlas_vector" }
        }
    }

    /** Projects logical pixels/dp, not density-scaled physical pixels. Near/invalid input is culled.
     * Normal prototype vectors stay far inside the near bound, so their layout is unchanged.
     */
    fun project(v: Vec3, width: Double, height: Double, zoom: Double = 1.0): Projected? {
        if (!v.finite() || !viewportValid(width, height) || !zoom.isFinite()) return null
        val denominator = CAMERA_DISTANCE - v.z
        if (!denominator.isFinite() || denominator < NEAR_DISTANCE) return null
        val perspective = CAMERA_DISTANCE / denominator
        val size = min(width * .38, height * .26) * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val point = Projected(width / 2 + v.x * size * perspective,
            height * .49 + v.y * size * perspective, v.z, perspective)
        return point.takeIf { it.finite() && it.scale > 0 }
    }

    fun projectStars(demo: Graph, camera: Camera, width: Double, height: Double): List<Projected> {
        if (!viewportValid(width, height)) return emptyList()
        val safe = normalized(camera)
        return demo.stars.indices.mapNotNull { index ->
            project(rotate(starPosition(index, demo.seed), safe.yaw, safe.pitch), width, height, safe.zoom)
                ?.copy(index = index)
        }
    }

    fun pointOnRing(angle: Double, ring: Ring, camera: Camera, width: Double, height: Double): Projected? {
        if (!angle.isFinite() || !ring.radius.isFinite() || ring.radius !in 0.0..2.0 ||
            !ring.tilt.isFinite() || !ring.axis.isFinite()) return null
        val safe = normalized(camera)
        val v = rotate(Vec3(cos(angle) * ring.radius, sin(angle) * ring.radius, 0.0), ring.axis, ring.tilt)
        return project(rotate(v, safe.yaw, safe.pitch), width, height, safe.zoom)
    }

    /** Background dots are decoration, never returned as memory stars or edges. */
    fun backgroundPoint(index: Int, width: Double, height: Double, seed: Int = 0): BackgroundPoint? {
        if (index !in 0 until BACKGROUND_COUNT || !viewportValid(width, height)) return null
        return BackgroundPoint(rand(index + 1, seed) * width, rand(index + 888, seed) * height,
            .3 + rand(index + 77, seed) * .65, .08 + rand(index + 57, seed) * .36)
    }

    /** Orbit dust is also decoration. Clock is caller-owned seconds, never a global timer. */
    fun dustPosition(index: Int, clockSeconds: Double, seed: Int = 0): Vec3 {
        require(index in 0 until DUST_COUNT && clockSeconds.isFinite()) { "invalid_atlas_decoration" }
        val angle = rand(index + 301, seed) * TAU + (clockSeconds * .012) % TAU
        val radius = .15 + sqrt(rand(index + 600, seed)) * 1.02
        return Vec3(cos(angle) * radius, (rand(index + 400, seed) - .5) * .25, sin(angle) * radius)
    }

    /** Nearest logical-point center strictly inside the prototype's 22px radius.
     * Equal distances choose the lower index, matching stable prototype ordering.
     */
    fun nearestStar(points: List<Projected>, x: Double, y: Double, radius: Double = 22.0): Int? {
        if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0) return null
        return points.asSequence().filter { it.index >= 0 && it.finite() && it.scale > 0 }
            .map { it.index to hypot(it.x - x, it.y - y) }
            .filter { it.second < radius }
            .minWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })?.first
    }

    fun relatedIndices(demo: Graph, selected: Int?): Set<Int> {
        if (selected == null || selected !in demo.stars.indices) return emptySet()
        return buildSet {
            add(selected)
            demo.edges.forEach { edge -> if (edge.a == selected || edge.b == selected) { add(edge.a); add(edge.b) } }
        }
    }

    fun normalized(camera: Camera): Camera = Camera(
        yaw = camera.yaw.takeIf(Double::isFinite)?.rem(TAU) ?: -.3,
        pitch = (camera.pitch.takeIf(Double::isFinite) ?: -.62).coerceIn(-1.3, 1.3),
        zoom = (camera.zoom.takeIf(Double::isFinite) ?: 1.0).coerceIn(MIN_ZOOM, MAX_ZOOM),
    )

    fun drag(camera: Camera, dx: Double, dy: Double): Camera {
        val safe = normalized(camera)
        if (!dx.isFinite() || !dy.isFinite()) return safe
        return normalized(safe.copy(yaw = safe.yaw + dx * .006, pitch = safe.pitch + dy * .005))
    }
    /** Factor, not an additive delta: use zoomTo(camera, camera.zoom +/- .15) for +/- buttons. */
    fun zoomBy(camera: Camera, factor: Double): Camera {
        val safe = normalized(camera)
        if (!factor.isFinite() || factor <= 0) return safe
        val target = if (factor > MAX_ZOOM / safe.zoom) MAX_ZOOM else safe.zoom * factor
        return zoomTo(safe, target)
    }
    fun zoomTo(camera: Camera, zoom: Double): Camera = normalized(camera.copy(zoom = zoom))

    fun advance(camera: Camera, dtSeconds: Double, paused: Boolean = false, reducedMotion: Boolean = false,
                selected: Int? = null, visible: Boolean = true): Camera {
        val safe = normalized(camera)
        if (paused || reducedMotion || selected != null || !visible || !dtSeconds.isFinite() || dtSeconds <= 0) return safe
        return normalized(safe.copy(yaw = safe.yaw + min(dtSeconds, MAX_FRAME_SECONDS) * AUTO_YAW_RADIANS_PER_SECOND))
    }

    private fun viewportValid(width: Double, height: Double) = width.isFinite() && height.isFinite() && width > 0 && height > 0
    private fun Vec3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
    private fun Projected.finite() = x.isFinite() && y.isFinite() && depth.isFinite() && scale.isFinite()
}
