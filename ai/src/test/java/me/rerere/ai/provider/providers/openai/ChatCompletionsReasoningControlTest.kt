package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Final request JSON, including customBody, for both streaming modes. No network or secrets. */
class ChatCompletionsReasoningControlTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun request(
        level: ReasoningLevel,
        stream: Boolean,
        host: String = "synthetic-st.invalid",
        modelId: String = "assistant-public-alias",
        customBody: List<CustomBody> = emptyList(),
        reasoningCapable: Boolean = true,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val params = TextGenerationParams(
            model = Model(modelId = modelId,
                abilities = if (reasoningCapable) listOf(ModelAbility.REASONING) else emptyList()),
            reasoningLevel = level, customBody = customBody,
        )
        val body = method.invoke(api, listOf(UIMessage.user("synthetic input")), params,
            ProviderSetting.OpenAI(baseUrl = "https://$host/v1"), stream) as JsonObject
        // Use the same final JSON serialization as the transport, not a control-only helper.
        return json.parseToJsonElement(json.encodeToString(body)).jsonObject.also {
            assertEquals(stream.toString(), it.getValue("stream").jsonPrimitive.content)
        }
    }

    @Test fun `generic OFF is explicit none rather than low in both request modes`() {
        listOf(false, true).forEach { stream ->
            val body = request(ReasoningLevel.OFF, stream)
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertFalse(body.containsKey("thinking"))
            assertFalse(body.containsKey("enable_thinking"))
        }
    }

    @Test fun `generic AUTO stays absent and enabled levels stay distinct`() {
        listOf(false, true).forEach { stream ->
            assertFalse(request(ReasoningLevel.AUTO, stream).containsKey("reasoning_effort"))
            ReasoningLevel.entries.filter { it.isEnabled && it != ReasoningLevel.AUTO }.forEach { level ->
                assertEquals(level.effort, request(level, stream).getValue("reasoning_effort").jsonPrimitive.content)
            }
        }
    }

    @Test fun `model name cannot turn an unknown relay into a native DeepSeek endpoint`() {
        listOf(false, true).forEach { stream ->
            val body = request(ReasoningLevel.OFF, stream, modelId = "deepseek-v4-flash-vision-exp")
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertFalse(body.containsKey("thinking"))
        }
    }

    @Test fun `official DeepSeek OFF uses native switch and AUTO is not OFF`() {
        listOf(false, true).forEach { stream ->
            val off = request(ReasoningLevel.OFF, stream, host = "api.deepseek.com")
            assertEquals("disabled", off.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse(off.containsKey("reasoning_effort"))
            val auto = request(ReasoningLevel.AUTO, stream, host = "api.deepseek.com")
            assertEquals("enabled", auto.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse(auto.containsKey("reasoning_effort"))
        }
    }

    @Test fun `known relay dialect still wins over DeepSeek model name`() {
        listOf(false, true).forEach { stream ->
            val body = request(ReasoningLevel.OFF, stream, host = "openrouter.ai", modelId = "deepseek/deepseek-v4-flash")
            assertEquals("none", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertFalse(body.containsKey("reasoning_effort"))
            assertFalse(body.containsKey("thinking"))
        }
    }

    @Test fun `custom body retains final precedence and native fields are not rewritten`() {
        listOf(false, true).forEach { stream ->
            val thinking = buildJsonObject { put("type", "enabled"); put("custom_marker", "synthetic") }
            val body = request(ReasoningLevel.OFF, stream, customBody = listOf(
                CustomBody("reasoning_effort", JsonPrimitive("high")), CustomBody("thinking", thinking),
            ))
            assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals(thinking, body["thinking"])
            val nativeOverride = request(ReasoningLevel.OFF, stream, host = "api.deepseek.com",
                customBody = listOf(CustomBody("thinking", thinking)))
            assertEquals(thinking, nativeOverride["thinking"])
        }
    }

    @Test fun `non reasoning model receives no new control`() {
        listOf(false, true).forEach { stream ->
            val body = request(ReasoningLevel.OFF, stream, reasoningCapable = false)
            assertFalse(body.containsKey("reasoning_effort"))
            assertFalse(body.containsKey("thinking"))
        }
    }
}
