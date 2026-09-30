package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisGardenReadingTools
import me.rerere.rikkahub.data.ai.tools.executeOrbisGardenReadingRequest
import me.rerere.rikkahub.data.orbis.OrbisGardenExtrasSnapshot
import me.rerere.rikkahub.data.orbis.soup.createSoupTools
import org.junit.Assert.*
import org.junit.Test

class OrbisDefaultLocalReadsTest {
    @Test fun noOptionsExposesOnlyLocalBookReadsAndPublicCurrent() {
        val reading = createOrbisGardenReadingTools { error("must not execute while constructing") }
        val soup = createSoupTools { _, _ -> error("must not execute while constructing") }
        assertEquals(listOf("orbis_reading_list", "orbis_reading_read_chapter", "orbis_reading_list_annotations"), selectLocalReadingTools(emptyList(), reading).map { it.name })
        assertEquals(listOf("orbis_soup_current"), selectLocalSoupTools(emptyList(), soup).map { it.name })
        (selectLocalReadingTools(emptyList(), reading) + selectLocalSoupTools(emptyList(), soup)).forEach {
            assertFalse(it.needsApproval(JsonObject(emptyMap())))
            assertNull(it.hostApproval)
        }
    }

    @Test fun explicitWriteOptionsAddOnlyTheirOwnApprovedActions() {
        val reading = createOrbisGardenReadingTools { error("not executing") }
        val soup = createSoupTools { _, _ -> error("not executing") }
        assertEquals(reading, selectLocalReadingTools(listOf(LocalToolOption.LocalReading), reading))
        assertEquals(3, selectLocalReadingTools(listOf(LocalToolOption.LocalSoup), reading).size)
        assertEquals(soup, selectLocalSoupTools(listOf(LocalToolOption.LocalSoup), soup))
        assertEquals(1, selectLocalSoupTools(listOf(LocalToolOption.LocalReading), soup).size)
        (reading.filter { it.name == "orbis_reading_annotate" } + soup.filter { it.name != "orbis_soup_current" }).forEach {
            assertTrue(it.needsApproval(JsonObject(emptyMap()))); assertNotNull(it.hostApproval)
        }
    }

    @Test fun defaultEmptyLibraryReadReturnsEmptyWithoutLoadingAnyCloudSettingsOrWriting() = runBlocking {
        var loads = 0; var saves = 0
        val tools = selectLocalReadingTools(emptyList(), createOrbisGardenReadingTools { request ->
            executeOrbisGardenReadingRequest(request, load = {
                loads++; OrbisGardenExtrasSnapshot(0, JsonObject(emptyMap()))
            }, save = { _, _ -> saves++; error("no write") }, companionName = "")
        })
        val result = Json.parseToJsonElement((tools.first().execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("result").jsonObject.getValue("total").jsonPrimitive.int)
        assertTrue(result.getValue("result").jsonObject.getValue("books").jsonArray.isEmpty())
        assertEquals(1, loads); assertEquals(0, saves)
        assertFalse(result.getValue("network_requested").jsonPrimitive.boolean)
    }

    @Test fun defaultPublicGameReadCanReturnEmptyWithoutCallingDm() = runBlocking {
        var reads = 0
        val tools = selectLocalSoupTools(emptyList(), createSoupTools { action, _ ->
            assertEquals("current", action); reads++
            buildJsonObject { put("active", false) }
        })
        val result = Json.parseToJsonElement((tools.single().execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertFalse(result.getValue("result").jsonObject.getValue("active").jsonPrimitive.boolean)
        assertEquals(1, reads)
    }
}
