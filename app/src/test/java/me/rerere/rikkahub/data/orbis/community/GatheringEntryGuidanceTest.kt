package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatheringEntryGuidanceTest {
    @Test fun anotherAssistantOnTheSameAccountIsNotMySignup() {
        val table = buildJsonObject {
            put("code", "PT-1"); put("mode", "ai_only"); put("can_manage", true)
            put("creator", buildJsonObject { put("id", "first") })
            put("mine", JsonArray(listOf(JsonPrimitive("first"))))
            put("members", JsonArray(listOf(buildJsonObject { put("ai", "first") })))
        }
        val first = gatheringForActor(table, "first")
        val second = gatheringForActor(table, "second")
        assertEquals(JsonPrimitive(true), first["self_signed_up"])
        assertEquals(JsonPrimitive(false), second["self_signed_up"])
        assertEquals(JsonArray(emptyList()), second["mine"])
        assertEquals(JsonPrimitive(false), second["can_manage"])
        assertEquals(table["members"], second["members"])
        val page = buildJsonObject { put("items", JsonArray(listOf(table))); put("next_offset", 30) }
        assertEquals(0, gatheringListForActor(page, "second", true)["items"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(30), gatheringListForActor(page, "second", true)["next_offset"])
        assertEquals(1, gatheringListForActor(page, "second", false)["items"]!!.jsonArray.size)
    }
    @Test fun twoRegisteredAssistantsEachSeeOnlyTheirOwnReceipt() {
        val table = buildJsonObject {
            put("mine", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            put("members", JsonArray(listOf("a", "b").map { buildJsonObject { put("ai", it) } }))
        }
        for (actor in listOf("a", "b")) {
            val result = gatheringForActor(table, actor)
            assertEquals(JsonArray(listOf(JsonPrimitive(actor))), result["mine"])
            assertEquals(JsonPrimitive(true), result["self_signed_up"])
        }
    }
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
