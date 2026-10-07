package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import me.rerere.rikkahub.data.orbis.OrbisAtlasMilkyWay as MilkyWay

class OrbisAtlasMilkyWayTest {
    private val stamp = "2026-10-05T00:00:00Z"
    private fun graph(count: Int) = Atlas.Snapshot(List(count) {
        Atlas.Star(it.toString().padStart(64, '0'), if (it % 2 == 0) "学习" else "情感", stamp)
    }, emptyList(), stamp, false)

    @Test fun onlyRealNodesAreSelectableAndDecorativeCountIsBounded() {
        val graph = graph(353)
        val layout = MilkyWay.layout(graph)
        assertEquals(353, layout.memories.size)
        assertEquals(MilkyWay.DUST_COUNT, layout.dust.size)
        assertEquals(MilkyWay.HAZE_COUNT, layout.haze.size)
        assertEquals(layout, MilkyWay.layout(graph))
        val points = MilkyWay.projectMemories(layout, Atlas.Camera(), 360.0, 650.0)
        assertEquals(graph.stars.indices.toList(), points.map { it.index })
        assertTrue(points.all { it.x.isFinite() && it.y.isFinite() })
        val target = points[100]
        assertNotNull(Atlas.nearestStar(points, target.x, target.y))
    }

    @Test fun emptyLiveSnapshotDoesNotInventGalaxyCoreOrNodes() {
        val layout = MilkyWay.layout(graph(0))
        assertTrue(layout.memories.isEmpty()); assertTrue(layout.dust.isEmpty()); assertTrue(layout.haze.isEmpty())
    }

    @Test fun everyModeKeepsStableDefaultsAndUnknownPreferencesAreLightweight() {
        OrbisAtlasDisplayMode.entries.forEach { assertEquals(it, OrbisAtlasDisplayMode.fromStored(it.storedValue)) }
        assertEquals(OrbisAtlasDisplayMode.LIGHTWEIGHT, OrbisAtlasDisplayMode.fromStored(null))
        assertEquals(OrbisAtlasDisplayMode.LIGHTWEIGHT, OrbisAtlasDisplayMode.fromStored("future"))
    }

    @Test fun projectionHandlesResizeAndNormalizesMalformedCamera() {
        val layout = MilkyWay.layout(graph(2))
        val camera = Atlas.Camera(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN)
        assertEquals(MilkyWay.projectMemories(layout, Atlas.Camera(), 320.0, 600.0),
            MilkyWay.projectMemories(layout, camera, 320.0, 600.0))
        assertTrue(MilkyWay.projectMemories(layout, camera, 0.0, 600.0).isEmpty())
        assertNull(MilkyWay.project(layout.memories.first(), camera, Double.NaN, 600.0))
    }
}
