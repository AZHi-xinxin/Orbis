package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import me.rerere.rikkahub.data.orbis.OrbisAtlasGalaxy as Galaxy

class OrbisAtlasGalaxyTest {
    private val stamp = "2026-10-03T00:00:00Z"
    private fun graph(types: List<String>, edges: List<Atlas.Edge> = emptyList(), truncated: Boolean = false) =
        Atlas.Snapshot(types.mapIndexed { index, type -> Atlas.Star(index.toString().padStart(64, '0'), type, stamp) },
            edges, stamp, truncated)

    @Test fun `tint is exact received category weighted blend`() {
        val profile = Galaxy.profile(graph(listOf("情感", "情感", "学习", "其他")))
        assertEquals(mapOf("情感" to 2, "学习" to 1, "其他" to 1), profile.counts)
        assertEquals((.96 * 2 + .42 + .61) / 4, profile.tint.red, 1e-12)
        assertEquals((.44 * 2 + .58 + .66) / 4, profile.tint.green, 1e-12)
        assertEquals((.65 * 2 + .99 + .73) / 4, profile.tint.blue, 1e-12)
    }

    @Test fun `dominant emotion gives pink dominant planning learning gives blue violet`() {
        val emotion = Galaxy.profile(graph(List(9) { "情感" } + "学习")).tint
        val learning = Galaxy.profile(graph(List(9) { "学习" } + "情感")).tint
        val planning = Galaxy.profile(graph(List(9) { "规划" } + "情感")).tint
        assertTrue(emotion.red > emotion.blue && emotion.red > emotion.green)
        assertTrue(learning.blue > learning.red && learning.blue > learning.green)
        assertTrue(planning.blue > planning.red && planning.red > planning.green)
    }

    @Test fun `only explicit tools or work gives yellow green tint`() {
        val tools = Galaxy.profile(graph(listOf("工具", "工作"))).tint
        val other = Galaxy.profile(graph(listOf("其他"))).tint
        assertTrue(tools.green > tools.blue && tools.red > tools.blue)
        assertEquals(Galaxy.tintForType("其他"), other)
        assertNotEquals(tools, other)
        assertEquals(Galaxy.tintForType("其他"), Galaxy.tintForType("unknown synthetic"))
    }

    @Test fun `empty live snapshot never fills itself with decorative memory nodes or bright core`() {
        val input = graph(emptyList())
        val layout = Galaxy.layout(input)
        assertFalse(input.isDemo)
        assertFalse(layout.profile.hasMemories)
        assertTrue(layout.stars.isEmpty())
        assertTrue(layout.dust.isEmpty())
        assertTrue(layout.clouds.isEmpty())
        assertEquals(Galaxy.tintForType("其他"), layout.profile.tint)
    }

    @Test fun `truncated data is explicitly a sample not whole library composition`() {
        assertTrue(Galaxy.profile(graph(listOf("学习"), truncated = true)).sampled)
        assertFalse(Galaxy.profile(graph(listOf("学习"))).sampled)
        assertFalse(Galaxy.profile(Atlas.demo()).sampled)
    }

    @Test fun `layout is deterministic and contains exactly supplied memory nodes`() {
        val input = graph(listOf("学习", "规划", "情感", "其他"), listOf(Atlas.Edge(0, 1)))
        assertEquals(Galaxy.layout(input), Galaxy.layout(input))
        assertEquals(input.stars.size, Galaxy.layout(input).stars.size)
        assertEquals(1, input.edges.size)
        assertEquals(Galaxy.DUST_COUNT, Galaxy.layout(input).dust.size)
        assertEquals(Galaxy.CLOUD_COUNT, Galaxy.layout(input).clouds.size)
    }

    @Test fun `metadata only timestamp and category edits change galaxy shape`() {
        val input = graph(listOf("学习", "规划", "情感", "其他"))
        val changedTime = input.copy(stars = input.stars.mapIndexed { i, star ->
            if (i == 0) star.copy(storedAt = "2026-10-03T01:00:00Z") else star
        })
        val changedCategory = input.copy(stars = input.stars.mapIndexed { i, star -> if (i == 0) star.copy(type = "情感") else star })
        assertNotEquals(Galaxy.layout(input).stars, Galaxy.layout(changedTime).stars)
        assertNotEquals(Galaxy.layout(input).stars, Galaxy.layout(changedCategory).stars)
    }

    @Test fun `actual relationship pattern influences shape without inventing extra edges`() {
        val input = graph(List(12) { "学习" })
        val connected = input.copy(edges = List(11) { Atlas.Edge(it, it + 1) })
        assertTrue(Galaxy.profile(connected).thickness > Galaxy.profile(input).thickness)
        assertNotEquals(Galaxy.layout(input).stars, Galaxy.layout(connected).stars)
        assertTrue(input.edges.isEmpty())
        assertEquals(11, connected.edges.size)
    }

    @Test fun `server row reorder preserves same star identity positions and whole profile`() {
        val input = graph(listOf("学习", "规划", "情感", "其他"), listOf(Atlas.Edge(0, 2), Atlas.Edge(1, 3)))
        val reversed = input.copy(stars = input.stars.reversed(), edges = listOf(Atlas.Edge(3, 1), Atlas.Edge(2, 0)))
        assertEquals(Galaxy.profile(input), Galaxy.profile(reversed))
        assertEquals(Galaxy.layout(input).stars, Galaxy.layout(reversed).stars.reversed())
    }

    @Test fun `duplicate reversed and reordered edges do not visually pretend extra relationships`() {
        val input = graph(List(4) { "学习" }, listOf(Atlas.Edge(0, 2), Atlas.Edge(1, 3)))
        val duplicates = input.copy(edges = listOf(Atlas.Edge(3, 1), Atlas.Edge(2, 0), Atlas.Edge(0, 2)))
        assertEquals(Galaxy.profile(input), Galaxy.profile(duplicates))
    }

    @Test fun `largest allowed snapshot keeps bounded decoration and valid projected metadata indices`() {
        val input = graph(List(2000) { Atlas.SUPPORTED_TYPES[it % Atlas.SUPPORTED_TYPES.size] })
        val layout = Galaxy.layout(input)
        assertEquals(2000, layout.stars.size)
        assertEquals(Galaxy.DUST_COUNT, layout.dust.size)
        assertEquals(Galaxy.CLOUD_COUNT, layout.clouds.size)
        for (pitch in listOf(-1.3, -.62, 1.3)) for (zoom in listOf(.55, 2.0)) {
            val points = Galaxy.projectStars(layout, Atlas.Camera(pitch = pitch, zoom = zoom), 390.0, 600.0)
            assertEquals((0 until 2000).toList(), points.map { it.index })
            assertTrue(points.all { listOf(it.x, it.y, it.depth, it.scale).all(Double::isFinite) && it.scale > 0 })
        }
    }

    @Test fun `invalid viewport gives no projected stars`() {
        val layout = Galaxy.layout(graph(listOf("情感")))
        assertTrue(Galaxy.projectStars(layout, Atlas.Camera(), 0.0, 500.0).isEmpty())
        assertTrue(Galaxy.projectStars(layout, Atlas.Camera(), Double.NaN, 500.0).isEmpty())
    }

    @Test fun `all cached particle geometry is finite and bounded`() {
        val layout = Galaxy.layout(graph(List(150) { Atlas.SUPPORTED_TYPES[it % 6] }))
        (layout.dust + layout.clouds).forEach {
            assertTrue(listOf(it.position.x, it.position.y, it.position.z, it.radius, it.alpha).all(Double::isFinite))
            assertTrue(hypot(it.position.x, it.position.z) < 1.4)
            assertTrue(it.radius > 0 && it.alpha in 0.0..1.0)
        }
    }

    @Test fun `orbit depends only on caller time and preserves radius`() {
        val v = Atlas.Vec3(.5, .03, .7)
        assertEquals(v, Galaxy.orbit(v, 0.0))
        assertEquals(v, Galaxy.orbit(v, Double.NaN))
        assertEquals(Galaxy.orbit(v, 123.0), Galaxy.orbit(v, 123.0))
        assertNotEquals(v, Galaxy.orbit(v, 123.0))
        assertEquals(hypot(v.x, v.z), Galaxy.orbit(v, 123.0).let { hypot(it.x, it.z) }, 1e-12)
        assertEquals(v.y, Galaxy.orbit(v, 123.0).y, 0.0)
    }

    @Test fun `flow follows drag and never exceeds gentle displacement cap`() {
        var flow = Galaxy.Flow()
        repeat(100) { flow = Galaxy.pull(flow, 100.0, 200.0, 1000.0, 1000.0) }
        assertEquals(100.0, flow.x, 0.0)
        assertEquals(200.0, flow.y, 0.0)
        assertTrue(flow.dx > 0 && flow.dy > 0)
        assertTrue(hypot(flow.dx, flow.dy) <= Galaxy.MAX_FLOW_DP + 1e-10)
    }

    @Test fun `extreme and invalid touch data cannot produce nonfinite flow`() {
        val large = Galaxy.pull(Galaxy.Flow(dx = Double.MAX_VALUE, dy = Double.MAX_VALUE), 0.0, 0.0, Double.MAX_VALUE, Double.MAX_VALUE)
        assertTrue(listOf(large.dx, large.dy).all(Double::isFinite))
        assertTrue(hypot(large.dx, large.dy) <= Galaxy.MAX_FLOW_DP + 1e-10)
        assertEquals(Galaxy.Flow(), Galaxy.pull(large, Double.NaN, 0.0, 1.0, 1.0))
    }

    @Test fun `flow affects nearby drawn and hittested nodes equally but leaves distant nodes nearly unchanged`() {
        val point = Atlas.Projected(200.0, 300.0, .2, 1.1, 5)
        val flow = Galaxy.Flow(200.0, 300.0, 20.0, 10.0)
        val displaced = Galaxy.displace(point, flow)
        assertEquals(220.0, displaced.x, 1e-12)
        assertEquals(310.0, displaced.y, 1e-12)
        assertEquals(point.index, displaced.index)
        assertEquals(point.depth, displaced.depth, 0.0)
        assertEquals(5, Atlas.nearestStar(listOf(displaced), displaced.x, displaced.y))
        val far = point.copy(x = 1000.0, y = 1000.0)
        assertEquals(far.x, Galaxy.displace(far, flow).x, 1e-9)
        assertEquals(far.y, Galaxy.displace(far, flow).y, 1e-9)
    }

    @Test fun `release settles smoothly to original coordinates without overshoot`() {
        var flow = Galaxy.Flow(100.0, 200.0, 30.0, 10.0)
        repeat(200) {
            val next = Galaxy.settle(flow, 1.0 / 30)
            assertTrue(hypot(next.dx, next.dy) <= hypot(flow.dx, flow.dy))
            flow = next
        }
        assertEquals(Galaxy.Flow(), flow)
        val point = Atlas.Projected(100.0, 200.0, 0.0, 1.0, 0)
        assertEquals(point, Galaxy.displace(point, flow))
    }

    @Test fun `background reduced motion or paused drawing cancels inertia and resumed frame cannot catch up`() {
        val flow = Galaxy.Flow(100.0, 200.0, 30.0, 10.0)
        assertEquals(Galaxy.Flow(), Galaxy.settle(flow, .03, motionAllowed = false))
        assertEquals(Galaxy.settle(flow, .05), Galaxy.settle(flow, 900.0))
        assertEquals(flow, Galaxy.settle(flow, Double.NaN))
        assertEquals(flow, Galaxy.settle(flow, -1.0))
    }

    @Test fun `bad displacement radius or flow fails unchanged`() {
        val point = Atlas.Projected(100.0, 200.0, 0.0, 1.0, 0)
        assertEquals(point, Galaxy.displace(point, Galaxy.Flow(dx = 1.0), radiusDp = 0.0))
        assertEquals(point, Galaxy.displace(point, Galaxy.Flow(x = Double.NaN, dx = 1.0)))
        assertEquals(point, Galaxy.displace(point, Galaxy.Flow(dx = Double.NaN)))
    }
}
