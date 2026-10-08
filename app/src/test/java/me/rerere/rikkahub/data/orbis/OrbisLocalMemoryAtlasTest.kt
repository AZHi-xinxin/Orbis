package me.rerere.rikkahub.data.orbis

import java.lang.reflect.Modifier
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMetadata
import org.junit.Assert.*
import org.junit.Test

class OrbisLocalMemoryAtlasTest {
    private fun row(id: String, state: String = "static", tags: List<String> = emptyList(), deleted: Boolean = false) =
        OrbisMemoryMetadata(id, state, tags, 1_780_000_000_000, 1_780_000_000_001, 2, deleted)

    @Test fun `local graph preserves provenance without adding local states to ST types`() {
        val graph = localMemoryAtlas(localMemoryAtlasStates.mapIndexed { index, state -> row("note-$index", state) }, 4)
        assertFalse(graph.isDemo)
        assertEquals("local_assistant_metadata", graph.source)
        assertEquals(localMemoryAtlasStates, graph.stars.map { it.type })
        assertTrue(localMemoryAtlasStates.none { it in OrbisMemoryAtlas.SUPPORTED_TYPES })
        assertFalse(graph.truncated)
    }

    @Test fun `metadata projection has no prose summary title keywords or version payload`() {
        val fields = OrbisMemoryMetadata::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("id", "state", "tags", "createdAt", "updatedAt", "revision", "deleted"), fields)
        assertEquals(setOf("id", "type", "storedAt"), OrbisMemoryAtlas.Star::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    @Test fun `edges express exact shared tags only and deduplicate repeated tags`() {
        val graph = localMemoryAtlas(listOf(row("a", tags = listOf("work", "work")),
            row("b", tags = listOf("work")), row("c", tags = listOf("Work")), row("d", "pinned")), 4)
        assertEquals(listOf(OrbisMemoryAtlas.Edge(0, 1)), graph.edges)
        assertEquals("仅存", localMemoryStateLabel(graph.stars[0].type))
    }

    @Test fun `same tags create a sparse bounded chain rather than a quadratic clique`() {
        val rows = List(500) { row(it.toString().padStart(4, '0'), tags = listOf("shared")) }
        assertEquals(499, localMemoryAtlas(rows, 500).edges.size)
        val limited = localMemoryAtlas(rows, 1000, maxStars = 100, maxEdges = 25)
        assertEquals(100, limited.stars.size)
        assertEquals(25, limited.edges.size)
        assertTrue(limited.truncated)
        assertTrue(limited.edges.all { it.a in limited.stars.indices && it.b in limited.stars.indices && it.a != it.b })
    }

    @Test fun `soft deleted records are not rendered or connected but paused records remain metadata`() {
        val graph = localMemoryAtlas(listOf(row("deleted", tags = listOf("shared"), deleted = true),
            row("kept", "paused", listOf("shared"))), 1)
        assertEquals(listOf("kept"), graph.stars.map { it.id })
        assertEquals("已暂停浮现", localMemoryStateLabel(graph.stars.single().type))
        assertTrue(graph.edges.isEmpty())
        assertFalse(graph.truncated)
    }

    @Test fun `immutable snapshot ordering and metadata do not change when caller lists mutate`() {
        val tags = mutableListOf("shared")
        val input = mutableListOf(row("b", tags = tags), row("a", tags = listOf("shared")))
        val graph = localMemoryAtlas(input, 2)
        assertEquals(graph, localMemoryAtlas(input.reversed(), 2))
        tags += "later"
        input.clear()
        assertEquals(listOf("a", "b"), graph.stars.map { it.id })
        assertEquals(listOf("shared"), graph.metadata[1].tags)
    }

    @Test fun `invalid metadata is rejected with a fixed content free error`() {
        for (bad in listOf(row("a", "private sentinel"), row("a").copy(createdAt = -1), row("a").copy(revision = -1))) {
            val error = assertThrows(IllegalArgumentException::class.java) { localMemoryAtlas(listOf(bad), 1) }
            assertEquals("invalid_local_memory_metadata", error.message)
        }
        assertThrows(IllegalArgumentException::class.java) { localMemoryAtlas(listOf(row("a"), row("a")), 2) }
    }

    @Test fun `empty local snapshots invent no stars or associations`() {
        val graph = localMemoryAtlas(emptyList(), 0)
        assertTrue(graph.stars.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertFalse(graph.truncated)
        assertFalse(OrbisAtlasGalaxy.profile(graph).hasMemories)
    }
}
