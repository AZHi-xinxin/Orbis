package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Offline production request checks: no account/token access, transport or tool execution. */
class ChatGptPlanToolSchemaTest {
    private val encoder = ResponseAPI(OkHttpClient())
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-schema-test")
    private val messages = listOf(UIMessage.user("Create a new text item; omit its id and revision."))

    @Test fun planKeepsOptionalArgumentsOptionalWithoutNullOrPlaceholderConversion() {
        val schema = InputSchema.Obj(
            properties = Json.parseToJsonElement("""{
                "title":{"type":"string"},"kind":{"type":"string"},
                "id":{"type":"string"},"expected_revision":{"type":"integer","minimum":0}
            }""").jsonObject,
            required = listOf("title", "kind"),
        )
        val tool = Tool("orbis_gallery_begin", "New item: omit id and revision.", { schema },
            execute = { error("request construction must not execute tools") })
        for (slug in listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
            "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            val params = TextGenerationParams(
                model = Model(modelId = slug, abilities = listOf(ModelAbility.TOOL), tools = setOf(BuiltInTools.Search)),
                tools = listOf(tool),
            )
            val generic = encoder.buildRequestBody(plan, messages, params, true)["tools"]!!.jsonArray.first().jsonObject
            val body = buildChatGptPlanRequestBody(encoder, plan, messages, params)
            val tools = body["tools"]!!.jsonArray
            assertEquals("orbis", tools.first().jsonObject["name"]!!.jsonPrimitive.content)
            val function = tools.first().jsonObject["tools"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(false), function["strict"])
            assertEquals(generic["parameters"], function["parameters"])
            assertEquals(listOf("title", "kind"), function["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(schema.properties, function["parameters"]!!.jsonObject["properties"])
            assertEquals("web_search", tools.last().jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(tools.last().jsonObject.containsKey("strict"))
            assertFalse(generic.containsKey("strict"))
            assertEquals(schema, tool.parameters())
        }
    }

    @Test fun projectionPreservesReferenceSchemasAndOriginalFunctionArguments() {
        val base = Json.parseToJsonElement("""{
            "input":[{"type":"function_call","call_id":"call1","name":"custom_edit",
                "arguments":"{\"id\":\"\",\"expected_revision\":0}"}],
            "tools":[{"type":"function","name":"custom_edit","parameters":{
                "type":"object","properties":{"target":{"${'$'}ref":"#/${'$'}defs/Target"}},
                "required":["target"],"${'$'}defs":{"Target":{"type":"object",
                    "properties":{"id":{"type":"string"},"note":{"type":"string"}},"required":["id"]}}
            }}]
        }""").jsonObject
        val projected = buildChatGptPlanBody(base)
        val function = projected["tools"]!!.jsonArray.single().jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals(base["tools"]!!.jsonArray.single().jsonObject["parameters"], function["parameters"])
        assertEquals(JsonPrimitive(false), function["strict"])
        assertEquals(base["input"]!!.jsonArray.single().jsonObject["arguments"],
            projected["input"]!!.jsonArray.single().jsonObject["arguments"])
        assertFalse(base["tools"]!!.jsonArray.single().jsonObject.containsKey("strict"))
    }

    @Test fun actionSpecificHelpFieldsRemainOptionalAndAreNotFilledIn() {
        val schema = InputSchema.Obj(Json.parseToJsonElement("""{
            "topic":{"type":"string"},"chapter":{"type":"string"},"query":{"type":"string"},
            "offset":{"type":"integer"},"limit":{"type":"integer"}
        }""").jsonObject, required = listOf("topic"))
        val tool = Tool("orbis_help", "Only chapters accepts query; guide accepts chapter.", { schema },
            execute = { error("request construction must not execute tools") })
        val body = buildChatGptPlanRequestBody(encoder, plan, messages,
            TextGenerationParams(Model(modelId = "gpt-5.6-sol", abilities = listOf(ModelAbility.TOOL)), tools = listOf(tool)))
        val function = body["tools"]!!.jsonArray.single().jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false), function["strict"])
        assertEquals(listOf(JsonPrimitive("topic")), function["parameters"]!!.jsonObject["required"]!!.jsonArray.toList())
        assertEquals(schema.properties, function["parameters"]!!.jsonObject["properties"])
    }
}
