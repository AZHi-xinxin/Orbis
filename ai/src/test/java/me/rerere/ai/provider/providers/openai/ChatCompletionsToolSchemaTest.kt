package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.InvalidToolSchemaException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ChatCompletionsToolSchemaTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `tool without parameters uses empty object schema`() {
        val tool = Tool(
            name = "get_current_time",
            description = "Get the current date and time.",
            execute = { emptyList() },
        )

        val body = buildRequest(tool)
        val function = body["tools"]
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?.get("function")
            ?.jsonObject
            ?: error("function tool not found")
        val parameters = function["parameters"]?.jsonObject
            ?: error("parameters schema not found")

        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertEquals(JsonObject(emptyMap()), parameters["properties"]?.jsonObject)
        assertFalse(parameters.containsKey("required"))
    }

    @Test
    fun `each outgoing tool keeps its own definitions and nested constraints`() {
        fun tool(name: String, type: String): Tool = Tool(
            name = name, description = "fixture", execute = { emptyList() },
            parameters = { InputSchema.Obj(
                properties = Json.parseToJsonElement("""{"input":{"DOLLARref":"#/DOLLARdefs/Input"}}""".replace("DOLLAR", "\$")).jsonObject,
                required = listOf("input"),
                defs = Json.parseToJsonElement("""{"Input":{"type":"$type","minLength":2,"description":"retained"}}""").jsonObject,
            ) },
        )
        val body = buildRequest(tool("mcp__garden__review_drift_bottles", "string"), tool("mcp__mail__mail_inbox", "object"))
        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        tools.forEachIndexed { index, item ->
            val schema = item.jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
            val definition = schema["\$defs"]!!.jsonObject["Input"]!!.jsonObject
            assertEquals(if (index == 0) "string" else "object", definition["type"]!!.jsonPrimitive.content)
            assertEquals("2", definition["minLength"]!!.jsonPrimitive.content)
            assertEquals("retained", definition["description"]!!.jsonPrimitive.content)
            assertEquals("#/\$defs/Input", schema["properties"]!!.jsonObject["input"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `old broken cached schema is rejected locally before HTTP`() {
        val broken = Tool(name = "mcp__garden__review_drift_bottles", description = "fixture", execute = { emptyList() },
            parameters = { InputSchema.Obj(Json.parseToJsonElement("""{"input":{"DOLLARref":"#/DOLLARdefs/Input"}}""".replace("DOLLAR", "\$")).jsonObject) })
        val error = assertThrows(InvocationTargetException::class.java) { buildRequest(broken) }
        assertTrue(error.cause is InvalidToolSchemaException)
        assertEquals("missing_local_reference", (error.cause as InvalidToolSchemaException).reasonCode)
    }

    private fun buildRequest(vararg tools: Tool): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        return method.invoke(
            api,
            listOf(UIMessage.user("Use the get_current_time tool.")),
            TextGenerationParams(
                model = Model(
                    modelId = "test-model",
                    abilities = listOf(ModelAbility.TOOL),
                ),
                tools = tools.toList(),
            ),
            ProviderSetting.OpenAI(),
            false,
        ) as JsonObject
    }
}
