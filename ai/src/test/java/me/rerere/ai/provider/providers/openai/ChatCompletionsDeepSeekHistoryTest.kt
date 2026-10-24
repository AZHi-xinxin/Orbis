package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Synthetic final wire JSON; never calls an upstream, runs a tool, or rewrites saved messages. */
class ChatCompletionsDeepSeekHistoryTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private fun request(messages: List<UIMessage>, stream: Boolean = false,
        host: String = "api.deepseek.com", modelId: String = "deepseek-flash",
        tools: Boolean = true, history: Boolean = true,
        customBody: List<CustomBody> = emptyList()): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val params = TextGenerationParams(
            Model(modelId = modelId, abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
            tools = if (tools) listOf(Tool("compact", "synthetic", execute = { error("must not execute") })) else emptyList(),
            customBody = customBody,
        )
        return method.invoke(api, messages, params,
            ProviderSetting.OpenAI(baseUrl = "https://$host/v1", includeHistoryReasoning = history), stream) as JsonObject
    }
    private fun assistantMessages(body: JsonObject) = body.getValue("messages").jsonArray
        .map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }

    @Test fun `compaction summary has an explicit empty reasoning field without fabricating thoughts`() {
        val summary = UIMessage.assistant("Synthetic summary, unchanged.")
        listOf(false, true).forEach { stream ->
            val body = request(listOf(summary, UIMessage.user("continue")), stream)
            val sent = assistantMessages(body).single()
            assertEquals(JsonPrimitive(""), sent["reasoning_content"])
            assertEquals(JsonPrimitive(summary.toText()), sent["content"])
            assertEquals(listOf(UIMessagePart.Text("Synthetic summary, unchanged.")), summary.parts)
        }
    }

    @Test fun `named DeepSeek relay preserves all original reasoning and tool results`() {
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("original reasoning"), UIMessagePart.Text("before tool"),
            UIMessagePart.Tool("call_synthetic", "compact", "{}", output = listOf(UIMessagePart.Text("saved result"))),
            UIMessagePart.Text("after tool without new reasoning"),
        ))
        val original = source.parts.toList()
        listOf(false, true).forEach { stream ->
            val body = request(listOf(UIMessage.assistant("summary"), source), stream,
                host = "synthetic-st.invalid", modelId = "deepseek-v4-flash-vision-exp")
            val sent = assistantMessages(body)
            assertEquals(listOf("", "original reasoning", ""), sent.map { it["reasoning_content"]?.jsonPrimitive?.content })
            val result = body.getValue("messages").jsonArray.map { it.jsonObject }
                .single { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals("saved result", result.getValue("content").jsonPrimitive.content)
            assertEquals("call_synthetic", result.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals(original, source.parts)
        }
    }

    @Test fun `empty history fields are scoped and never override opting out`() {
        val messages = listOf(UIMessage.assistant("old answer"))
        listOf(false, true).forEach { stream ->
            listOf(
                request(messages, stream, host = "generic.invalid", modelId = "generic-model"),
                request(messages, stream, tools = false),
                request(messages, stream, history = false),
                request(messages, stream, host = "openrouter.ai", modelId = "deepseek/deepseek-flash"),
            ).forEach { assertFalse(assistantMessages(it).single().containsKey("reasoning_content")) }
        }
    }

    @Test fun `final custom model and tools determine whether missing fields are added`() {
        val messages = listOf(UIMessage.assistant("summary"))
        listOf(false, true).forEach { stream ->
            val noTools = request(messages, stream, customBody = listOf(CustomBody("tools", JsonArray(emptyList()))))
            assertFalse(assistantMessages(noTools).single().containsKey("reasoning_content"))
            val otherModel = request(messages, stream, host = "synthetic-st.invalid",
                customBody = listOf(CustomBody("model", JsonPrimitive("generic-model"))))
            assertFalse(assistantMessages(otherModel).single().containsKey("reasoning_content"))
            val deepSeekModel = request(messages, stream, host = "synthetic-st.invalid", modelId = "generic-model",
                customBody = listOf(CustomBody("model", JsonPrimitive("deepseek/deepseek-flash"))))
            assertEquals(JsonPrimitive(""), assistantMessages(deepSeekModel).single()["reasoning_content"])
        }
    }

    @Test fun `explicit reasoning fields and non assistant messages are never overwritten`() {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", "system instruction") })
            add(buildJsonObject { put("role", "user"); put("content", "synthetic request") })
            add(buildJsonObject { put("role", "assistant"); put("content", "answer"); put("reasoning_content", "original") })
            add(buildJsonObject { put("role", "assistant"); put("content", "explicit null"); put("reasoning_content", JsonNull) })
            add(buildJsonObject { put("role", "assistant"); put("content", "details"); put("reasoning_details", buildJsonArray { add(JsonPrimitive("opaque")) }) })
            add(buildJsonObject { put("role", "tool"); put("tool_call_id", "synthetic"); put("content", "original result") })
        }
        listOf(false, true).forEach { stream ->
            val body = request(emptyList(), stream, customBody = listOf(CustomBody("messages", messages)))
            assertEquals(messages, body["messages"])
        }
    }
}
