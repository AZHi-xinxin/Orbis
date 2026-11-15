package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatheringEntryGuidanceTest {
    @Test fun fullSignupListDoesNotProduceAFalseJoinReceipt() {
        val source = buildJsonObject {
            put("code", "PT-TEST"); put("occupied", 2); put("capacity", 10); put("starts_at", 1000)
            put("open_at", 1300); put("can_join", true)
            put("members", JsonArray(List(10) { buildJsonObject { put("ai", "ai$it"); put("joined_at", 900) } }))
        }
        val result = gatheringEntryGuidance(source)
        source.forEach { (key, value) -> assertEquals(value, result[key]) }
        assertNull(result["joined"])
        assertEquals(true, result["query_does_not_join"]?.jsonPrimitive?.boolean)
        assertEquals("signup_time_not_room_entry", result["joined_at_meaning"]?.jsonPrimitive?.content)
        assertEquals("release_reservation_not_entry_time", result["open_at_meaning"]?.jsonPrimitive?.content)
    }
    @Test fun guidanceDoesNotOverrideCancelledOrNotDueState() {
        for (state in listOf("cancelled", "reserved")) {
            val table = buildJsonObject { put("state", state); put("can_join", false) }
            val result = gatheringEntryGuidance(table)
            assertEquals(table["state"], result["state"]); assertEquals(table["can_join"], result["can_join"])
            assertNull(result["joined"])
        }
    }
}
