package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production request builder without account access, HTTP or tool execution. */
class ChatGptPlanRequestCapabilitiesTest {
    private val encoder = ResponseAPI(OkHttpClient())
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-test-account")
    private val messages = listOf(UIMessage.user("Continue with a text reply."))

    @Test fun legacyImageToggleCannotBlockTextAfterModelSwitchesOrNewTurns() {
        val models = listOf("gpt-5.6-sol", "gpt-6.1-sol").associateWith {
            Model(modelId = it, tools = setOf(BuiltInTools.ImageGeneration))
        }
        for (modelId in listOf("gpt-5.6-sol", "gpt-6.1-sol", "gpt-5.6-sol")) {
            val model = models.getValue(modelId)
            val params = TextGenerationParams(model = model)
            repeat(2) {
                val body = buildChatGptPlanRequestBody(encoder, plan, messages, params)
                assertEquals(modelId, body["model"]?.jsonPrimitive?.content)
                assertFalse(body.containsKey("tools"))
                assertEquals(encoder.buildMessages(messages), body["input"])
                assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
                assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
                assertSame(model, params.model)
                assertEquals(setOf(BuiltInTools.ImageGeneration), model.tools)
            }
        }
    }

    @Test fun projectionKeepsDeclaredWorkshopFunctionsSearchAndToolHistory() {
        val workshop = Tool(
            name = "orbis_image_workshop",
            description = "Queue an image with the user's separately configured image provider.",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { error("Request construction must not execute tools") },
        )
        val model = Model(
            modelId = "gpt-6.1-sol",
            abilities = listOf(ModelAbility.TOOL),
            tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration, BuiltInTools.UrlContext),
        )
        val history = listOf(
            UIMessage.user("Make an image using the configured workshop."),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "workshop-call",
                toolName = workshop.name,
                input = "{}",
                output = listOf(UIMessagePart.Text("Image task queued.")),
            ))),
            UIMessage.user("While it runs, continue chatting."),
        )
        val body = buildChatGptPlanRequestBody(encoder, plan, history,
            TextGenerationParams(model = model, tools = listOf(workshop)))

        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("namespace", "web_search"), tools.map { it["type"]?.jsonPrimitive?.content })
        val namespace = tools.first()
        assertEquals("orbis", namespace["name"]?.jsonPrimitive?.content)
        val function = namespace["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", function["type"]?.jsonPrimitive?.content)
        assertEquals(workshop.name, function["name"]?.jsonPrimitive?.content)
        assertEquals(workshop.description, function["description"]?.jsonPrimitive?.content)
        assertEquals("object", function["parameters"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        val call = input.single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("orbis", call["namespace"]?.jsonPrimitive?.content)
        assertEquals(workshop.name, call["name"]?.jsonPrimitive?.content)
        val output = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("workshop-call", output["call_id"]?.jsonPrimitive?.content)
        assertTrue(output["output"]!!.jsonPrimitive.content.contains("Image task queued."))
        assertEquals(3, model.tools.size)
    }

    @Test fun alreadySupportedRequestsAreUnchanged() {
        val params = TextGenerationParams(model = Model(modelId = "gpt-5.6-sol",
            abilities = listOf(ModelAbility.REASONING), tools = setOf(BuiltInTools.Search)))
        val expected = buildChatGptPlanBody(encoder.buildRequestBody(plan, messages, params, true))
        assertEquals(expected, buildChatGptPlanRequestBody(encoder, plan, messages, params))
    }

    @Test fun regularApiImageToolSettingsRemainUntouched() {
        val model = Model(modelId = "api-model", tools = setOf(BuiltInTools.ImageGeneration))
        val body = encoder.buildRequestBody(ProviderSetting.OpenAI(), messages, TextGenerationParams(model), true)
        assertEquals("image_generation", body["tools"]!!.jsonArray.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(setOf(BuiltInTools.ImageGeneration), model.tools)
    }

    @Test fun customBodiesCannotReintroduceUnsupportedNativeTools() {
        val unsupported = Json.parseToJsonElement("""[{"type":"image_generation"}]""")
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-6.1-sol"),
            customBody = listOf(CustomBody("tools", unsupported)),
        )
        val body = buildChatGptPlanRequestBody(encoder, plan, messages, params)
        assertFalse(body.containsKey("tools"))
        assertEquals(unsupported, params.customBody.single().value)
    }

    @Test fun explicitUnsupportedSchemasAndUnexpectedNativeCallsStillFailClosed() {
        for (type in listOf("image_generation", "mcp", "code_interpreter", "unknown_tool")) {
            assertThrows(IllegalArgumentException::class.java) {
                buildChatGptPlanBody(Json.parseToJsonElement("""{"input":[],"tools":[{"type":"$type"}]}""").jsonObject)
            }
        }
        for (type in listOf("image_generation_call", "unknown_tool_call")) {
            val decoder = ChatGptPlanDecoder(setOf("orbis_image_workshop"))
            assertThrows(IllegalArgumentException::class.java) {
                decoder.accept(SseEvent(data = """{"type":"response.output_item.added","item":{"type":"$type","id":"unexpected"}}"""))
            }
            assertThrows(IllegalStateException::class.java) { decoder.onClosed() }
        }
    }
}
