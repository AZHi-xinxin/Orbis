package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.memory.latestMemoryHuman
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConsultationMemoryScopeTest {
    @Test fun relayInputPreservesHostIdentityAndTextButIsNotHumanAuthored() {
        val id = Uuid.random()
        val message = consultationNonHumanMessage("synthetic consultation source", id)
        assertEquals(id, message.id)
        assertEquals(MessageRole.USER, message.role)
        assertEquals("synthetic consultation source", message.toText())
        assertTrue(message.isSynthetic)
        assertEquals(JsonPrimitive(false), message.parts.single().metadata?.get("human_authored"))
        assertNull(latestMemoryHuman(listOf(message)))
    }

    @Test fun savedCheckpointCannotTurnPeerOrRelayIntoANewHumanMemoryAnchor() {
        for (body in listOf("synthetic peer reply", "synthetic relay wake", "synthetic archive instruction")) {
            val original = consultationNonHumanMessage(body)
            val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(original))
            assertFalse(restored.isSynthetic) // The actual transient flag is lost on persistence.
            assertEquals(original.id, restored.id)
            assertEquals(original.parts, restored.parts)
            assertNull(latestMemoryHuman(listOf(restored)))
        }
    }

    @Test fun relayMarkerDoesNotReclassifyAnActualPrivateHumanMessage() {
        val realHuman = UIMessage.user("synthetic private human question")
        val relay = Json.decodeFromString<UIMessage>(Json.encodeToString(consultationNonHumanMessage("peer")))
        assertEquals(realHuman, latestMemoryHuman(listOf(realHuman, relay)))
        assertEquals(realHuman, latestMemoryHuman(listOf(relay, realHuman)))
        assertNull(realHuman.parts.single().metadata?.get("human_authored"))
    }

    @Test fun legacyReferenceConsultationCannotObtainLocalMemoryThroughCatalogueOrForgedAllowlists() {
        for (phase in listOf("ACTIVE", "ARCHIVING")) {
            assertFalse(consultationToolSelectable("orbis_memory", phase))
            for (action in listOf("read", "history", "store", "update", "delete", "set_state", "restore")) {
                assertFalse(consultationToolAllowed("orbis_memory", buildJsonObject { put("action", action) },
                    phase, setOf("orbis_memory"), setOf("orbis_memory"), "a".repeat(32)))
            }
            // This memory-specific regression must not silently disable existing scoped ST reads.
            assertTrue(consultationToolSelectable("mcp__ST__recall_work_memory", phase))
            assertTrue(consultationToolAllowed("mcp__ST__recall_work_memory", buildJsonObject {},
                phase, emptySet(), emptySet(), "a".repeat(32)))
        }
    }
}
