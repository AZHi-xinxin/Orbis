package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class OrbisHumanThoughtToolTest {
    @Test fun `permission is off by default and AI cannot grant itself access`() = runBlocking {
        val owner = Assistant()
        var settings = Settings(assistants = listOf(owner))
        val tool = humanThoughtTool(owner.id, { settings }, { settings = it(settings); true })
        val response = (tool.execute(buildJsonObject { put("action", "enable") }).single() as UIMessagePart.Text).text
        assertTrue(response.contains("human_thought_not_authorized"))
        assertFalse(settings.assistants.single().allowHumanThought)
        assertFalse(settings.assistants.single().humanThoughtEnabled)
    }

    @Test fun `AI can toggle only its authorized assistant and revocation wins even with old tool`() = runBlocking {
        val owner = Assistant(allowHumanThought = true)
        val other = Assistant(allowHumanThought = true)
        var settings = Settings(assistants = listOf(owner, other))
        val tool = humanThoughtTool(owner.id, { settings }, { settings = it(settings); true })
        suspend fun call(action: String): JsonObject = Json.parseToJsonElement(
            (tool.execute(buildJsonObject { put("action", action) }).single() as UIMessagePart.Text).text).jsonObject
        assertFalse(call("status")["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(call("enable")["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(other, settings.assistants.last())
        assertFalse(call("disable")["enabled"]!!.jsonPrimitive.boolean)
        settings = settings.copy(assistants = listOf(owner.copy(allowHumanThought = false), other))
        assertEquals("human_thought_not_authorized", call("enable")["code"]!!.jsonPrimitive.content)
        assertFalse(settings.assistants.first().humanThoughtEnabled)
    }

    @Test fun `unknown arguments and persistence failure never report success`() = runBlocking {
        val owner = Assistant(allowHumanThought = true)
        val settings = Settings(assistants = listOf(owner))
        val tool = humanThoughtTool(owner.id, { settings }, { false })
        for (args in listOf("{}", "{\"action\":\"grant\"}", "{\"action\":\"enable\",\"owner\":\"other\"}", "{\"action\":\"enable\"}")) {
            val result = (tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text).text
            assertFalse(Json.parseToJsonElement(result).jsonObject["ok"]!!.jsonPrimitive.boolean)
        }
    }
}
