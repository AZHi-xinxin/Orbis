package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.math.PI
import kotlin.math.sqrt

class OrbisMemoryAtlasTest {
    private val atlas = OrbisMemoryAtlas
    private val camera = OrbisMemoryAtlas.Camera()

    @Test fun `demo contains exact prototype metadata counts and explicit demo provenance`() {
        val demo = atlas.demo()
        assertTrue(demo.isDemo); assertEquals("demo_not_real_memory", demo.source)
        assertEquals(56, demo.stars.size); assertEquals(75, demo.edges.size)
        assertEquals(listOf("学习", "规划", "情感", "其他"), atlas.TYPES)
        assertEquals(setOf(14), demo.stars.groupingBy { it.type }.eachCount().values.toSet())
        assertEquals((0 until 56).map { "star-$it" }, demo.stars.map { it.id })
    }

    @Test fun `stored times match JavaScript UTC month offset and millisecond format`() {
        val stars = atlas.demo().stars
        assertEquals("2026-02-01T08:00:00.000Z", stars[0].storedAt)
        assertEquals("2026-03-08T09:01:00.000Z", stars[1].storedAt)
        assertEquals("2026-02-23T15:07:00.000Z", stars[7].storedAt)
        assertEquals("2026-02-08T09:28:00.000Z", stars[28].storedAt)
        assertEquals("2026-08-08T09:55:00.000Z", stars[55].storedAt)
    }

    @Test fun `demo values are repeatable and seeds affect only decorative distribution`() {
        assertEquals(atlas.demo(), atlas.demo())
        val one = atlas.demo(123); val two = atlas.demo(456)
        assertEquals(one.stars, two.stars); assertEquals(one.edges, two.edges)
        assertEquals(atlas.starPosition(3, 123), atlas.starPosition(3, 123))
        assertNotEquals(atlas.starPosition(3, 123), atlas.starPosition(3, 456))
        assertEquals(atlas.projectStars(one, camera, 400.0, 650.0), atlas.projectStars(one, camera, 400.0, 650.0))
    }

    @Test fun `metadata whitelist regenerates ids and normalizes time without content fields`() {
        val star = OrbisMemoryAtlas.Star("private-title sentinel", "学习", "2026-02-01T16:00:00+08:00")
        val demo = atlas.metadataOnly(listOf(star), emptyList())
        assertEquals(OrbisMemoryAtlas.Star("star-0", "学习", "2026-02-01T08:00:00.000Z"), demo.stars.single())
        assertFalse(demo.toString().contains("sentinel"))
        // Compose may inject a static $stable flag; all instance data must remain whitelisted.
        assertEquals(setOf("id", "type", "storedAt"), OrbisMemoryAtlas.Star::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertEquals(setOf("a", "b"), OrbisMemoryAtlas.Edge::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    @Test fun `unknown types and invalid timestamps fail with fixed messages`() {
        for (star in listOf(OrbisMemoryAtlas.Star("x", "private sentinel", "2026-02-01T00:00:00Z"),
            OrbisMemoryAtlas.Star("x", "学习", "private sentinel"))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { atlas.metadataOnly(listOf(star), emptyList()) }
            assertEquals("invalid_star_metadata", failure.message)
        }
    }

    @Test fun `metadata whitelist limits stars and rejects invalid edge endpoints or self edges`() {
        val stars = List(2001) { OrbisMemoryAtlas.Star("ignored", "其他", "2026-02-01T00:00:00Z") }
        val bad = listOf(OrbisMemoryAtlas.Edge(-1, 0), OrbisMemoryAtlas.Edge(0, 2000), OrbisMemoryAtlas.Edge(2, 2))
        val demo = atlas.metadataOnly(stars, bad + List(5001) { OrbisMemoryAtlas.Edge(0, 1) })
        assertEquals(2000, demo.stars.size); assertEquals(5000, demo.edges.size)
        assertTrue(demo.edges.all { it == OrbisMemoryAtlas.Edge(0, 1) })
    }

    @Test fun `rand default matches original JavaScript golden values and remains bounded`() {
        assertEquals(.1274029857304413, atlas.rand(0), 1e-8)
        assertEquals(.9853793988004327, atlas.rand(1), 1e-8)
        assertEquals(.6223809080402134, atlas.rand(7), 1e-8)
        assertEquals(.5435326088663714, atlas.rand(888), 1e-8)
        for (seed in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) for (i in 0..1000) {
            val value = atlas.rand(i, seed)
            assertTrue(value.isFinite() && value >= 0 && value < 1)
        }
    }

    @Test fun `seven orbit definitions and closure match prototype`() {
        assertEquals(7, atlas.rings.size)
        assertEquals(OrbisMemoryAtlas.Ring(.5, -.68, 0.0), atlas.rings.first())
        assertEquals(1.19, atlas.rings.last().radius, 1e-12)
        atlas.rings.forEach { ring ->
            val first = atlas.pointOnRing(0.0, ring, camera, 400.0, 650.0)!!
            val last = atlas.pointOnRing(2 * PI, ring, camera, 400.0, 650.0)!!
            assertEquals(first.x, last.x, 1e-9); assertEquals(first.y, last.y, 1e-9)
        }
    }

    @Test fun `star distribution matches golden radius height and coordinates`() {
        val first = atlas.starPosition(0)
        assertEquals(.38, first.x, 1e-12); assertEquals(.21124037693225545, first.y, 1e-8)
        assertEquals(0.0, first.z, 1e-12)
        val last = atlas.starPosition(55)
        assertEquals(1.0266588555086078, last.x, 1e-9)
        assertEquals(.3243463885191886, last.y, 1e-8)
        assertEquals(.052493755873966444, last.z, 1e-9)
        for (i in 0 until 56) {
            val v = atlas.starPosition(i)
            assertEquals(.38 + (i % 7) * .108, sqrt(v.x * v.x + v.z * v.z), 1e-9)
            assertTrue(v.y in -.35.. .35)
        }
    }

    @Test fun `rotate preserves magnitude and follows original yaw then pitch convention`() {
        val v = OrbisMemoryAtlas.Vec3(1.0, 2.0, 3.0)
        assertEquals(v, atlas.rotate(v, 0.0, 0.0))
        val yaw = atlas.rotate(OrbisMemoryAtlas.Vec3(1.0, 0.0, 0.0), PI / 2, 0.0)
        assertEquals(0.0, yaw.x, 1e-12); assertEquals(1.0, yaw.z, 1e-12)
        val rotated = atlas.rotate(v)
        assertEquals(14.0, rotated.x * rotated.x + rotated.y * rotated.y + rotated.z * rotated.z, 1e-9)
    }

    @Test fun `all projected demo points preserve indices and JavaScript golden positions`() {
        val points = atlas.projectStars(atlas.demo(), camera, 400.0, 650.0)
        assertEquals((0 until 56).toList(), points.map { it.index })
        val first = points[0]
        assertEquals(252.2366359151019, first.x, 1e-6)
        assertEquals(333.84964249588006, first.y, 1e-6)
        assertEquals(-.21413474761264104, first.depth, 1e-8)
        assertEquals(.9466548182668768, first.scale, 1e-8)
        assertEquals(337.19474484805176, points[55].x, 1e-6)
        assertEquals(334.5879544898104, points[55].y, 1e-6)
    }

    @Test fun `projection uses minimum dimension and preserves center when zoomed`() {
        val zero = OrbisMemoryAtlas.Vec3(0.0, 0.0, 0.0)
        assertEquals(OrbisMemoryAtlas.Projected(200.0, 318.5, 0.0, 1.0), atlas.project(zero, 400.0, 650.0))
        val v = OrbisMemoryAtlas.Vec3(.5, .25, 0.0)
        val one = atlas.project(v, 400.0, 650.0, 1.0)!!
        val two = atlas.project(v, 400.0, 650.0, 2.0)!!
        assertEquals(2 * (one.x - 200), two.x - 200, 1e-9)
        assertEquals(2 * (one.y - 318.5), two.y - 318.5, 1e-9)
    }

    @Test fun `invalid or near camera projection never returns nan infinity or inverted scale`() {
        val zero = OrbisMemoryAtlas.Vec3(0.0, 0.0, 0.0)
        for ((width, height) in listOf(0.0 to 650.0, -1.0 to 650.0, Double.NaN to 650.0, 400.0 to Double.POSITIVE_INFINITY))
            assertNull(atlas.project(zero, width, height))
        for (z in listOf(3.8, 4.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(atlas.project(zero.copy(z = z), 400.0, 650.0))
        assertNull(atlas.project(zero, 400.0, 650.0, Double.NaN))
        assertNull(atlas.project(zero.copy(x = Double.MAX_VALUE), Double.MAX_VALUE, Double.MAX_VALUE))
        assertTrue(atlas.projectStars(atlas.demo(), camera, 0.0, 0.0).isEmpty())
    }

    @Test fun `all valid camera extremes and rings yield finite projection`() {
        for (yaw in listOf(-PI, 0.0, PI)) for (pitch in listOf(-1.3, 0.0, 1.3)) for (zoom in listOf(.55, 2.0)) {
            val view = OrbisMemoryAtlas.Camera(yaw, pitch, zoom)
            val points = atlas.projectStars(atlas.demo(), view, 390.0, 600.0)
            assertEquals(56, points.size)
            points.forEach { assertTrue(listOf(it.x, it.y, it.depth, it.scale).all(Double::isFinite)); assertTrue(it.scale > 0) }
            atlas.rings.forEach { ring -> repeat(181) { i ->
                assertNotNull(atlas.pointOnRing(i / 180.0 * 2 * PI, ring, view, 390.0, 600.0))
            } }
        }
    }

    @Test fun `nearest hit is distance based not depth and exactly twenty two is excluded`() {
        val points = listOf(OrbisMemoryAtlas.Projected(10.0, 10.0, -1.0, 1.0, 0),
            OrbisMemoryAtlas.Projected(20.0, 10.0, 1.0, 1.0, 1))
        assertEquals(0, atlas.nearestStar(points, 11.0, 10.0))
        assertEquals(1, atlas.nearestStar(points, 21.0, 10.0))
        assertNull(atlas.nearestStar(points.take(1), 32.0, 10.0))
        assertEquals(0, atlas.nearestStar(points.take(1), 31.999, 10.0))
    }

    @Test fun `hit ties are stable and invalid points or pointers cannot be selected`() {
        val same = listOf(OrbisMemoryAtlas.Projected(10.0, 10.0, 0.0, 1.0, 2),
            OrbisMemoryAtlas.Projected(10.0, 10.0, 0.0, 1.0, 1))
        assertEquals(1, atlas.nearestStar(same, 10.0, 10.0))
        assertNull(atlas.nearestStar(same, Double.NaN, 10.0))
        assertNull(atlas.nearestStar(same, 10.0, 10.0, 0.0))
        assertNull(atlas.nearestStar(listOf(same[0].copy(x = Double.NaN)), 10.0, 10.0))
        assertNull(atlas.nearestStar(listOf(same[0].copy(index = -1)), 10.0, 10.0))
    }

    @Test fun `related nodes include incoming and outgoing direct neighbors only`() {
        val demo = atlas.demo()
        assertEquals(setOf(0, 9, 19, 47), atlas.relatedIndices(demo, 0))
        assertEquals(setOf(19, 28, 10, 0), atlas.relatedIndices(demo, 19))
        assertTrue(atlas.relatedIndices(demo, null).isEmpty())
        assertTrue(atlas.relatedIndices(demo, 56).isEmpty())
        assertFalse(atlas.relatedIndices(demo, 0).contains(28))
    }

    @Test fun `camera drag matches prototype rates and clamps pitch`() {
        val moved = atlas.drag(camera, 10.0, 20.0)
        assertEquals(-.24, moved.yaw, 1e-12); assertEquals(-.52, moved.pitch, 1e-12)
        assertEquals(1.3, atlas.drag(camera, 0.0, 10_000.0).pitch, 0.0)
        assertEquals(-1.3, atlas.drag(camera, 0.0, -10_000.0).pitch, 0.0)
        assertEquals(camera, atlas.drag(camera, Double.NaN, 0.0))
    }

    @Test fun `zoom uses bounded multiplicative pinch and additive button targets`() {
        assertEquals(2.0, atlas.zoomBy(camera, 100.0).zoom, 0.0)
        assertEquals(.55, atlas.zoomBy(camera, .001).zoom, 0.0)
        assertEquals(1.15, atlas.zoomTo(camera, camera.zoom + .15).zoom, 1e-12)
        assertEquals(camera, atlas.zoomBy(camera, Double.POSITIVE_INFINITY))
        assertEquals(camera, atlas.zoomBy(camera, 0.0))
    }

    @Test fun `auto rotation advances at point zero two five radians per second with frame cap`() {
        var state = camera
        repeat(40) { state = atlas.advance(state, .025) }
        assertEquals(camera.yaw + .025, state.yaw, 1e-12)
        assertEquals(camera.yaw + .00125, atlas.advance(camera, 5.0).yaw, 1e-12)
        assertEquals(camera, atlas.advance(camera, -1.0))
        assertEquals(camera, atlas.advance(camera, Double.NaN))
    }

    @Test fun `pause selected reduced motion and hidden views do not advance themselves`() {
        assertEquals(camera, atlas.advance(camera, .05, paused = true))
        assertEquals(camera, atlas.advance(camera, .05, reducedMotion = true))
        assertEquals(camera, atlas.advance(camera, .05, selected = 0))
        assertEquals(camera, atlas.advance(camera, .05, visible = false))
        assertEquals(camera, OrbisMemoryAtlas.Camera())
    }

    @Test fun `bad camera values normalize to finite defaults and bounds`() {
        assertEquals(camera, atlas.normalized(OrbisMemoryAtlas.Camera(Double.NaN, Double.NaN, Double.NaN)))
        assertEquals(1.3, atlas.normalized(camera.copy(pitch = 100.0)).pitch, 0.0)
        assertEquals(.55, atlas.normalized(camera.copy(zoom = -1.0)).zoom, 0.0)
        assertTrue(atlas.normalized(camera.copy(yaw = Double.MAX_VALUE)).yaw.isFinite())
        assertEquals(56, atlas.projectStars(atlas.demo(), camera.copy(yaw = Double.NaN), 400.0, 650.0).size)
    }

    @Test fun `background is deterministic decoration with prototype bounds and separate count`() {
        repeat(atlas.BACKGROUND_COUNT) { i ->
            val point = atlas.backgroundPoint(i, 400.0, 650.0)!!
            assertTrue(point.x in 0.0..400.0 && point.y in 0.0..650.0)
            assertTrue(point.radius in .3.. .95 && point.alpha in .08.. .44)
            assertEquals(point, atlas.backgroundPoint(i, 400.0, 650.0))
        }
        assertNull(atlas.backgroundPoint(260, 400.0, 650.0))
        assertNull(atlas.backgroundPoint(0, 0.0, 0.0))
        assertEquals(56, atlas.demo().stars.size)
    }

    @Test fun `dust uses only caller clock remains finite and does not create memory nodes`() {
        repeat(atlas.DUST_COUNT) { i ->
            val point = atlas.dustPosition(i, 123.0)
            assertEquals(point, atlas.dustPosition(i, 123.0))
            assertTrue(point.y in -.125.. .125)
            assertTrue(sqrt(point.x * point.x + point.z * point.z) in .15..1.17)
            assertNotNull(atlas.project(atlas.rotate(point), 400.0, 650.0))
        }
        assertNotEquals(atlas.dustPosition(0, 0.0), atlas.dustPosition(0, 1.0))
        assertThrows(IllegalArgumentException::class.java) { atlas.dustPosition(680, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { atlas.dustPosition(0, Double.NaN) }
        assertEquals(56, atlas.demo().stars.size)
    }
}
