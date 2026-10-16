package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GeminiChatCompletionsSignatureTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val model = Model(modelId = "gemini-3-flash", abilities = listOf(ModelAbility.TOOL))

    private fun parse(raw: JsonObject): UIMessage = ChatCompletionsAPI::class.java
        .getDeclaredMethod("parseMessage", JsonObject::class.java).apply { isAccessible = true }
        .invoke(api, raw) as UIMessage

    private fun calls(message: UIMessage, destinationModel: String = model.modelId,
        setting: ProviderSetting.OpenAI = ProviderSetting.OpenAI()): JsonArray {
        // Exercise persistence as well as response -> outgoing request; do not call a real tool/API.
        val stored = json.decodeFromString<UIMessage>(json.encodeToString(message))
        val completed = stored.copy(parts = stored.parts.map {
            if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("synthetic result"))) else it
        })
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val request = method.invoke(api, listOf(UIMessage.user("synthetic request"), completed),
            TextGenerationParams(model = model.copy(modelId = destinationModel)), setting, false) as JsonObject
        return request["messages"]!!.jsonArray.map { it.jsonObject }
            .single { "tool_calls" in it }["tool_calls"]!!.jsonArray
    }

    private fun extra(signature: JsonElement) = buildJsonObject {
        put("google", buildJsonObject { put("thought_signature", signature) })
    }

    private fun call(index: Int, name: String? = "read", args: String? = "{}", signature: JsonElement? = null,
        includeId: Boolean = true) = buildJsonObject {
        put("index", index)
        if (includeId) put("id", "call-$index")
        put("type", "function")
        if (name != null || args != null) put("function", buildJsonObject {
            name?.let { put("name", it) }; args?.let { put("arguments", it) }
        })
        signature?.let { put("extra_content", extra(it)) }
    }

    @Test
    fun `nonstream function signatures survive persistence and stay on their own call`() {
        val message = parse(buildJsonObject {
            put("role", "assistant"); put("content", JsonNull)
            put("tool_calls", buildJsonArray {
                add(call(0, signature = JsonPrimitive("opaque-signature-A==")))
                add(call(1))
            })
        })
        val outgoing = calls(message)
        assertEquals(extra(JsonPrimitive("opaque-signature-A==")), outgoing[0].jsonObject["extra_content"])
        assertFalse(outgoing[1].jsonObject.containsKey("extra_content"))
    }

    @Test
    fun `late metadata only stream chunks keep parallel call signatures and do not duplicate calls`() {
        val decoder = ChatCompletionsStreamDecoder()
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("synthetic request"))
        fun feed(vararg tools: JsonObject) {
            val event = buildJsonObject {
                put("id", "response-synthetic"); put("model", model.modelId)
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("delta", buildJsonObject { put("tool_calls", JsonArray(tools.toList())) })
                }) })
            }
            decoder.accept(SseEvent(data = event.toString())).chunks.forEach { messages = handler.handle(messages, it) }
        }
        feed(call(0, args = "{"), call(1, args = "{"))
        feed(call(1, name = null, args = "}", includeId = false), call(0, name = null, args = "}", includeId = false))
        feed(call(1, null, null, JsonPrimitive("signature-B"), includeId = false))
        feed(call(0, null, null, JsonPrimitive("signature-A"), includeId = false))
        feed(call(0, null, null, includeId = false))
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }
        val outgoing = calls(messages.last())
        assertEquals(2, outgoing.size)
        assertEquals("call-0", outgoing[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(extra(JsonPrimitive("signature-A")), outgoing[0].jsonObject["extra_content"])
        assertEquals(extra(JsonPrimitive("signature-B")), outgoing[1].jsonObject["extra_content"])
        assertEquals("{}", outgoing[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `signature delivered with initial arguments is retained through later argument chunks`() {
        val decoder = ChatCompletionsStreamDecoder()
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("synthetic request"))
        listOf(call(0, args = "{", signature = JsonPrimitive("early-signature")),
            call(0, name = null, args = "}", includeId = false)).forEach { tool ->
            val event = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(tool) }) })
            }) }) }
            decoder.accept(SseEvent(data = event.toString())).chunks.forEach { messages = handler.handle(messages, it) }
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }
        assertEquals(extra(JsonPrimitive("early-signature")), calls(messages.last()).single().jsonObject["extra_content"])
    }

    @Test
    fun `unsigned old calls or malformed signatures do not acquire fabricated signatures`() {
        for (signature in listOf(null, JsonNull, JsonPrimitive(42), JsonPrimitive(""))) {
            val message = parse(buildJsonObject {
                put("role", "assistant"); put("tool_calls", buildJsonArray { add(call(0, signature = signature)) })
            })
            assertFalse(calls(message).single().jsonObject.containsKey("extra_content"))
        }
    }

    @Test
    fun `native Google metadata is not relabeled as a compatible protocol signature`() {
        val message = parse(buildJsonObject {
            put("role", "assistant"); put("tool_calls", buildJsonArray { add(call(0)) })
        }).let { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.copy(metadata = GoogleThoughtMetadata("native-signature").toMetadata()) else it
        }) }
        assertFalse(calls(message).single().jsonObject.containsKey("extra_content"))
    }

    @Test
    fun `model switch omits Gemini extension without erasing saved signature`() {
        val message = parse(buildJsonObject {
            put("role", "assistant"); put("tool_calls", buildJsonArray {
                add(call(0, signature = JsonPrimitive("saved-signature")))
            })
        })
        assertFalse(calls(message, "other-provider-model").single().jsonObject.containsKey("extra_content"))
        assertEquals(extra(JsonPrimitive("saved-signature")), calls(message).single().jsonObject["extra_content"])
        assertEquals(extra(JsonPrimitive("saved-signature")), calls(message, "custom-model-alias",
            ProviderSetting.OpenAI(baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"))
            .single().jsonObject["extra_content"])
    }
}
