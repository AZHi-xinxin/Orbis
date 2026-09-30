package me.rerere.rikkahub.data.orbis

import me.rerere.rikkahub.data.orbis.integration.parseOrbisAtlas
import org.junit.Assert.*
import org.junit.Test

class OrbisAtlasCrossContractTest {
    @Test fun actualPythonAdapterFixtureIsAcceptedWithoutReadingPrivateContent() {
        // Captured from AtlasMetadataReader over test_atlas_metadata.AtlasFixture, never production.
        val bytes = javaClass.classLoader!!.getResourceAsStream("orbis-atlas-st-fixture.json")!!.use { it.readBytes() }
        val snapshot = parseOrbisAtlas(bytes)
        assertFalse(snapshot.isDemo)
        assertEquals(setOf("情感", "学习", "规划"), snapshot.stars.map { it.type }.toSet())
        assertEquals(3, snapshot.stars.size)
        assertTrue(snapshot.edges.isEmpty())
        assertFalse(snapshot.truncated)
    }
}
