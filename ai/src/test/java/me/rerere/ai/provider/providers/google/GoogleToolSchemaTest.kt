package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleToolSchemaTest {
    private fun requestSchema(properties: JsonObject, required: List<String>? = null): JsonObject {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val body = method.invoke(provider, listOf(UIMessage.user("synthetic request")), TextGenerationParams(
            model = Model(modelId = "gemini-3-flash", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(Tool(name = "synthetic_tool", description = "Synthetic tool", parameters = {
                InputSchema.Obj(properties, required)
            }, execute = { error("Request serialization must not execute a tool") })),
        )) as JsonObject
        return body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single()
            .jsonObject["parameters"]!!.jsonObject
    }

    private fun objectOf(vararg entries: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(entries.toMap()))
    }

    private fun uniqueArray() = buildJsonObject {
        put("type", "array"); put("minItems", 1); put("maxItems", 7); put("uniqueItems", true)
        put("items", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 7) })
    }

    @Test
    fun `request removes uniqueItems at both depths reported by Gemini without mutating source`() {
        val source = buildJsonObject {
            put("record_ids", uniqueArray())
            put("entry", objectOf("weekdays" to uniqueArray()))
        }
        val original = source.toString()
        val schema = requestSchema(source, listOf("entry"))
        val properties = schema["properties"]!!.jsonObject
        val arrays = listOf(properties["record_ids"]!!.jsonObject,
            properties["entry"]!!.jsonObject["properties"]!!.jsonObject["weekdays"]!!.jsonObject)
        arrays.forEach { array ->
            assertFalse(array.containsKey("uniqueItems"))
            assertEquals(JsonPrimitive(1), array["minItems"])
            assertEquals(JsonPrimitive(7), array["maxItems"])
            assertEquals(uniqueArray()["items"], array["items"])
        }
        assertEquals(JsonArray(listOf(JsonPrimitive("entry"))), schema["required"])
        assertEquals(original, source.toString())
    }

    @Test
    fun `sanitizes array items and anyOf without deleting keyword-shaped parameter names`() {
        val source = buildJsonObject {
            put("uniqueItems", buildJsonObject { put("type", "boolean") })
            put("format", buildJsonObject { put("type", "string") })
            put("entries", buildJsonObject {
                put("type", "array")
                put("items", objectOf("enum" to uniqueArray()))
            })
            put("choice", buildJsonObject { put("anyOf", buildJsonArray {
                add(objectOf("weekdays" to uniqueArray()))
                add(uniqueArray())
            }) })
        }
        val properties = requestSchema(source, listOf("uniqueItems", "format"))["properties"]!!.jsonObject
        assertEquals(source["uniqueItems"], properties["uniqueItems"])
        assertEquals(source["format"], properties["format"])
        val item = properties["entries"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(item.containsKey("enum"))
        assertFalse(item["enum"]!!.jsonObject.containsKey("uniqueItems"))
        val alternatives = properties["choice"]!!.jsonObject["anyOf"]!!.jsonArray
        assertFalse(alternatives[1].jsonObject.containsKey("uniqueItems"))
        assertFalse(alternatives[0].jsonObject["properties"]!!.jsonObject["weekdays"]!!.jsonObject.containsKey("uniqueItems"))
    }

    @Test
    fun `example and default are data not schemas and legacy filtering remains in schema positions`() {
        val literal = json.parseToJsonElement("""{"uniqueItems":true,"format":"literal","enum":["original"]}""")
        val source = buildJsonObject { put("value", buildJsonObject {
            put("type", "object"); put("default", literal); put("example", literal)
            put("additionalProperties", false)
            put("properties", buildJsonObject { put("field", buildJsonObject {
                put("type", "string"); put("format", "uuid"); put("const", "legacy")
                put("enum", buildJsonArray { add("legacy") })
            }) })
        }) }
        val value = requestSchema(source)["properties"]!!.jsonObject["value"]!!.jsonObject
        assertEquals(literal, value["default"])
        assertEquals(literal, value["example"])
        assertFalse(value.containsKey("additionalProperties"))
        assertEquals(buildJsonObject { put("type", "string") }, value["properties"]!!.jsonObject["field"])
    }
}
