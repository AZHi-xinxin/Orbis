package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import org.junit.Assert.*
import org.junit.Test

class OrbisConversationSettingsToolTest {
    private class Fixture {
        var enabled = true
        var value = OrbisConversationPrompt("existing preference", true, "existing world", true)
        var writes = 0
        var failWrite = false
        val tool = createOrbisConversationSettingsTool({ enabled }, { value }) { change ->
            if (failWrite) error("synthetic storage failure")
            value = change(value)
            writes++
            value
        }
        suspend fun call(json: String): JsonObject = Json.parseToJsonElement(
            (tool.execute(Json.parseToJsonElement(json)).single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `inspect reads current state without writing`() = runBlocking {
        val f = Fixture()
        val result = f.call("""{"action":"inspect"}""")
        assertEquals("existing world", result["world_book_text"]!!.jsonPrimitive.content)
        assertEquals(0, f.writes)
    }

    @Test fun `partial edit preserves current other fields including newer human edits`() = runBlocking {
        val f = Fixture()
        f.call("""{"action":"inspect"}""")
        f.value = f.value.copy(worldBookText = "new human world", worldBookEnabled = false)
        val result = f.call("""{"action":"update","text":"new preference"}""")
        assertEquals(OrbisConversationPrompt("new preference", true, "new human world", false), f.value)
        assertEquals("saved", result["status"]!!.jsonPrimitive.content)
        assertEquals("next_turn", result["effective_from"]!!.jsonPrimitive.content)
        assertEquals(1, f.writes)
    }

    @Test fun `disable preserves text and explicit empty text clears only chosen field`() = runBlocking {
        val f = Fixture()
        f.call("""{"action":"update","enabled":false,"world_book_enabled":false}""")
        assertEquals(OrbisConversationPrompt("existing preference", false, "existing world", false), f.value)
        f.call("""{"action":"update","world_book_text":"","enabled":true}""")
        assertEquals(OrbisConversationPrompt("existing preference", true, "", false), f.value)
    }

    @Test fun `invalid input never mutates and cannot name another conversation`() = runBlocking {
        val f = Fixture()
        for (input in listOf("{}", "[]", """{"action":"update"}""",
            """{"action":"inspect","text":"changed"}""",
            """{"action":"update","enabled":"false"}""",
            """{"action":"update","text":null}""",
            """{"action":"update","text":"changed","conversation_id":"elsewhere"}""")) {
            assertTrue(input, runCatching { f.call(input) }.isFailure)
        }
        assertEquals(0, f.writes)
    }

    @Test fun `disabled tool cannot read or write and failed storage cannot return saved`() = runBlocking {
        val f = Fixture()
        f.enabled = false
        assertTrue(runCatching { f.call("""{"action":"inspect"}""") }.isFailure)
        assertTrue(runCatching { f.call("""{"action":"update","text":"changed"}""") }.isFailure)
        f.enabled = true
        f.failWrite = true
        assertTrue(runCatching { f.call("""{"action":"update","text":"changed"}""") }.isFailure)
        assertEquals("existing preference", f.value.text)
        assertEquals(0, f.writes)
    }
}
