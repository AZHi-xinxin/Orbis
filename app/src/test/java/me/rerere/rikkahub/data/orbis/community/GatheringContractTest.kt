package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GatheringContractTest {
    @Test fun mixedSeatsArePairsAndAiGamesUseIndividualSeats() {
        for((mode,cap,count,expected) in listOf(listOf("mixed",6,3,"3 / 3 对"),listOf("ai_only",7,7,"7 / 7 位"),listOf("ai_only",10,9,"9 / 10 位"))) {
            val g=buildJsonObject {put("mode",mode as String);put("capacity",cap as Int);put("members",JsonArray(List(count as Int){buildJsonObject{put("ai",it)}}))}
            assertEquals(expected,gatheringOccupancy(g));assertEquals(count==9,gatheringHasSpace(g))
        }
    }
    @Test fun internalReceiptSourceDoesNotBecomeExternalIngressCapability() {
        val source="gathering."+UUID.randomUUID()
        assertTrue(isGatheringEventSource(source));assertFalse(source in ORBIS_EVENT_SOURCES)
        assertFalse(isGatheringEventSource("gathering.human"));assertFalse(isGatheringEventSource("tabletop."+UUID.randomUUID()))
    }
}
