package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.boundary.*
import org.junit.Assert.*
import org.junit.Test

class BoundaryToolOutputTest {
    @Test fun pauseReceiptAlwaysExplainsWhetherTheHumanEntranceExists() {
        val state = AssistantBoundary(grants = setOf("conversation"), conversations = mapOf("chat" to ConversationBoundary()))
        val closed = boundaryStateOutput(state, "chat", 1)
        assertFalse(closed.getValue("conversation").jsonObject.getValue("allow_request").jsonPrimitive.boolean)
        assertTrue(closed.getValue("human_request").jsonPrimitive.content.contains("不会显示"))
        val open = boundaryStateOutput(state.copy(conversations = mapOf("chat" to ConversationBoundary(allowRequest = true))), "chat", 1)
        assertTrue(open.getValue("human_request").jsonPrimitive.content.contains("聊天底部"))
        assertTrue(open.getValue("conversation").jsonObject.getValue("allow_request").jsonPrimitive.boolean)
        assertFalse(boundaryStateOutput(state, "other", 1).getValue("paused").jsonPrimitive.boolean)
        assertFalse(closed.containsKey("choices"))
    }

    @Test fun largeToolCatalogCanBePagedWithoutLosingIdsOrReturningAllEntries() {
        val catalog = mapOf("tool" to (0 until 103).associate { "tool_$it" to "Synthetic tool $it" })
        val collected = mutableListOf<String>()
        var offset = 0
        do {
            val page = boundaryCatalogPage(AssistantBoundary(), "tool", catalog, emptyMap(), offset = offset, limit = 10)
            assertTrue(page.toString().length < 5000)
            val entries = page.getValue("choices").jsonObject.getValue("tool").jsonArray
            assertTrue(entries.size <= 10)
            collected += entries.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val next = page.getValue("page").jsonObject.getValue("next_offset").jsonPrimitive.intOrNull
            offset = next ?: -1
        } while (offset >= 0)
        assertEquals(catalog.getValue("tool").keys.toList(), collected)
    }

    @Test fun mcpMembershipPreviewIsBoundedAndFilteredPagesPreserveEffectiveSelection() {
        val sources = (0 until 35).associate { "mcp__Garden__tool_$it" to "garden" }
        val catalog = mapOf("mcp" to mapOf("garden" to "Garden"),
            "tool" to (sources.mapValues { "Synthetic tool" } + ("cloud_reading_list" to "Cloud")))
        val state = AssistantBoundary(grants = BOUNDARY_CATEGORIES, mcps = mapOf("garden" to BoundaryChoice(false)))
        val mcp = boundaryCatalogPage(state, "mcp", catalog, sources).getValue("choices").jsonObject.getValue("mcp").jsonArray.single().jsonObject
        assertEquals(5, mcp.getValue("tool_ids").jsonArray.size)
        assertEquals(35, mcp.getValue("tool_count").jsonPrimitive.int)
        assertTrue(mcp.getValue("tool_ids_has_more").jsonPrimitive.boolean)
        val tools = boundaryCatalogPage(state, "tool", catalog, sources, mcpId = "garden", offset = 30)
        assertEquals(5, tools.getValue("page").jsonObject.getValue("returned").jsonPrimitive.int)
        tools.getValue("choices").jsonObject.getValue("tool").jsonArray.forEach {
            assertFalse(it.jsonObject.getValue("effective_enabled").jsonPrimitive.boolean)
        }
        val cloud = boundaryCatalogPage(state, "tool", catalog, sources, query = "cloud_reading")
            .getValue("choices").jsonObject.getValue("tool").jsonArray.single().jsonObject
        assertTrue(cloud.getValue("effective_enabled").jsonPrimitive.boolean)
        assertEquals("native_cloud", cloud.getValue("source_kind").jsonPrimitive.content)
    }

    @Test fun invalidPaginationCannotAccidentallyRequestTheWholeCatalog() {
        for ((offset, limit) in listOf(-1 to 10, 0 to 0, 0 to 21)) {
            assertThrows(IllegalArgumentException::class.java) {
                boundaryCatalogPage(AssistantBoundary(), "tool", emptyMap(), emptyMap(), offset = offset, limit = limit)
            }
        }
    }
}
