package me.rerere.rikkahub.data.orbis.soup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class SoupToolsTest {
    private fun call(tool: Tool, raw: String = "{}") = runBlocking {
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text).jsonObject
    }
    private val id = "00000000-0000-0000-0000-000000000001"
    @Test fun catalogueHasNoFilePrivatePuzzleStartOrResetToolAndAllChangesRequireApproval() {
        val tools = createSoupTools { _, _ -> buildJsonObject {} }
        assertEquals(listOf("orbis_soup_current", "orbis_soup_ask", "orbis_soup_hint", "orbis_soup_submit", "orbis_soup_reveal"), tools.map { it.name })
        assertFalse(tools.first().needsApproval(buildJsonObject {})); assertNull(tools.first().hostApproval)
        tools.drop(1).forEach { assertTrue(it.needsApproval(buildJsonObject {})); assertNotNull(it.hostApproval) }
    }
    @Test fun oneMutatingActionPerTurnDoesNotPreventReadOnlyDiscussion() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; buildJsonObject {} }
        assertTrue(call(tools[1], """{"session_id":"$id","text":"成立吗？"}""")["ok"]!!.jsonPrimitive.boolean)
        assertFalse(call(tools[2], """{"session_id":"$id"}""")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(call(tools[0])["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, calls)
    }
    @Test fun maliciousExtraFieldsCannotSelectHumanRoleReadSolutionOrChooseNetworkRoute() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; buildJsonObject {} }
        listOf("""{"session_id":"$id","text":"成立吗？","player":"HUMAN"}""", """{"session_id":"$id","text":"成立吗？","url":"https://memory.example"}""", """{"session_id":"../../private","text":"成立吗？"}""").forEach {
            assertFalse(call(tools[1], it)["ok"]!!.jsonPrimitive.boolean)
        }
        assertFalse(call(tools[0], """{"include_solution":true}""")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, calls)
    }
    @Test fun transportErrorsNeverExposeCredentialsPrivatePathsOrHostPayloads() {
        val tools = createSoupTools { _, _ -> error("secret-key /private/path secret_solution") }
        val text = call(tools[0]).toString()
        assertFalse(text.contains("secret-key")); assertFalse(text.contains("/private/path")); assertFalse(text.contains("secret_solution"))
    }
}
