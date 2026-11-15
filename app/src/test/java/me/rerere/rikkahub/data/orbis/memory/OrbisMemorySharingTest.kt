package me.rerere.rikkahub.data.orbis.memory

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class OrbisMemorySharingTest {
    @Test fun oldSettingsRemainPrivateAndBothLibrariesMustOptIn() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<Assistant>("{}")
        assertFalse(old.orbisSharedMemory); assertFalse(old.orbisHistoryAcrossAssistants)
        val peer = Assistant(name = "peer", orbisSharedMemory = true)
        assertEquals(setOf(old.id.toString()), sharedMemoryOwners(old.id, listOf(old, peer)).keys)
        val joined = old.copy(orbisSharedMemory = true)
        assertEquals(setOf(old.id.toString(), peer.id.toString()), sharedMemoryOwners(old.id, listOf(joined, peer)).keys)
        assertEquals(setOf(old.id.toString()), sharedMemoryOwners(old.id, listOf(joined, peer.copy(orbisSharedMemory = false))).keys)
        assertTrue(sharedMemoryOwners(old.id, listOf(peer)).isEmpty())
    }

    @Test fun sharedSourcesKeepTheirIdentityAndOriginalThreeBudgetsStillApply() {
        val own = OrbisMemorySelection(listOf(OrbisMemorySelected("own", "own pin", 1, "pinned")))
        val shared = (1..20).map { sharedMemoryEntry("other", "other assistant", OrbisMemorySelected("id$it", "foreign memory $it", 1, "pinned")) }
        assertTrue(sharedMemorySelection(OrbisMemoryMode.LIGHT, own, shared).entries.isEmpty())
        val standard = sharedMemorySelection(OrbisMemoryMode.STANDARD, own, shared)
        assertEquals(own.entries.single(), standard.entries.first())
        assertEquals(3, standard.entries.count { it.sourceAssistantId == "other" })
        assertEquals(5, sharedMemorySelection(OrbisMemoryMode.INDEPENDENT, own, shared).entries.count { it.sourceAssistantId == "other" })
        val rendered = renderOrbisMemory(standard.entries)
        assertTrue(rendered.contains("source_assistant_id")); assertTrue(rendered.contains("other assistant"))
        assertTrue(rendered.contains("\"id\":\"id1\"")); assertFalse(rendered.contains("\"id\":\"other/id1\""))
    }
}
