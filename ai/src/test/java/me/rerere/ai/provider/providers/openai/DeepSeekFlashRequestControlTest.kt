package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
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
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Registry -> real request builder -> final JSON. No network, keys or user settings. */
class DeepSeekFlashRequestControlTest {
    private enum class Protocol { CHAT_COMPLETIONS, RESPONSES }
    private val chat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val responses = ResponseAPI(OkHttpClient())

    private fun discovered(id: String = "deepseek-flash") = Model(
        modelId = id,
        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
        abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
    )

    private fun request(protocol: Protocol, stream: Boolean, level: ReasoningLevel,
        host: String = "api.deepseek.com", model: Model = discovered(),
        customBody: List<CustomBody> = emptyList()): JsonObject {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://$host/v1")
        val params = TextGenerationParams(model = model, reasoningLevel = level, customBody = customBody)
        val messages = listOf(UIMessage.user("synthetic input"))
        val body = when (protocol) {
            Protocol.CHAT_COMPLETIONS -> ChatCompletionsAPI::class.java.getDeclaredMethod(
                "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
                ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(chat, messages, params, provider, stream) as JsonObject
            Protocol.RESPONSES -> responses.buildRequestBody(provider, messages, params, stream)
        }
        return json.parseToJsonElement(json.encodeToString(body)).jsonObject.also {
            assertEquals(stream.toString(), it.getValue("stream").jsonPrimitive.content)
            assertEquals(model.modelId, it.getValue("model").jsonPrimitive.content)
        }
    }

    private fun everyMode(check: (Protocol, Boolean) -> Unit) {
        Protocol.entries.forEach { protocol -> listOf(false, true).forEach { check(protocol, it) } }
    }

    private fun assertOff(protocol: Protocol, body: JsonObject) {
        when (protocol) {
            Protocol.CHAT_COMPLETIONS -> {
                assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
                assertFalse(body.containsKey("reasoning_effort"))
                assertFalse(body.containsKey("reasoning"))
            }
            Protocol.RESPONSES -> {
                assertEquals("none", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
                assertFalse(body.containsKey("thinking"))
                assertFalse(body.containsKey("reasoning_effort"))
            }
        }
    }

    @Test fun `current official ID discovered from registry can disable both protocols`() = everyMode { protocol, stream ->
        assertOff(protocol, request(protocol, stream, ReasoningLevel.OFF))
    }

    @Test fun `AUTO preserves each protocol default instead of becoming OFF`() = everyMode { protocol, stream ->
        val body = request(protocol, stream, ReasoningLevel.AUTO)
        when (protocol) {
            Protocol.CHAT_COMPLETIONS -> {
                assertEquals("enabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
                assertFalse(body.containsKey("reasoning_effort"))
            }
            Protocol.RESPONSES -> assertFalse(body.getValue("reasoning").jsonObject.containsKey("effort"))
        }
    }

    @Test fun `enabled effort levels retain the existing native protocol mapping`() = everyMode { protocol, stream ->
        ReasoningLevel.entries.filter { it.isEnabled && it != ReasoningLevel.AUTO }.forEach { level ->
            val body = request(protocol, stream, level)
            when (protocol) {
                Protocol.CHAT_COMPLETIONS -> {
                    assertEquals("enabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
                    val expected = if (level == ReasoningLevel.MEDIUM) "high" else level.effort
                    assertEquals(expected, body.getValue("reasoning_effort").jsonPrimitive.content)
                }
                Protocol.RESPONSES -> assertEquals(level.effort,
                    body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            }
        }
    }

    @Test fun `explicit custom capabilities are not overwritten at request time`() = everyMode { protocol, stream ->
        val model = discovered().copy(abilities = listOf(ModelAbility.TOOL))
        val body = request(protocol, stream, ReasoningLevel.OFF, model = model)
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("reasoning"))
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }

    @Test fun `advanced custom body still overrides the visible OFF setting`() = everyMode { protocol, stream ->
        val custom = when (protocol) {
            Protocol.CHAT_COMPLETIONS -> CustomBody("thinking", buildJsonObject { put("type", "enabled") })
            Protocol.RESPONSES -> CustomBody("reasoning", buildJsonObject { put("effort", "high") })
        }
        val body = request(protocol, stream, ReasoningLevel.OFF, customBody = listOf(custom))
        when (protocol) {
            Protocol.CHAT_COMPLETIONS -> assertEquals("enabled",
                body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            Protocol.RESPONSES -> assertEquals("high",
                body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        }
    }

    @Test fun `old explicit DeepSeek IDs keep their existing OFF behavior`() = everyMode { protocol, stream ->
        listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro", "deepseek-reasoner")
            .forEach { id -> assertOff(protocol, request(protocol, stream, ReasoningLevel.OFF, model = discovered(id))) }
    }

    @Test fun `official model name does not choose the dialect for custom relay hosts`() {
        listOf(false, true).forEach { stream ->
            listOf("synthetic-relay.invalid", "api.deepseek.com.example.invalid").forEach { host ->
                val body = request(Protocol.CHAT_COMPLETIONS, stream, ReasoningLevel.OFF, host = host)
                assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
                assertFalse(body.containsKey("thinking"))
                assertFalse(request(Protocol.CHAT_COMPLETIONS, stream, ReasoningLevel.AUTO, host = host)
                    .containsKey("reasoning_effort"))
            }
            val router = request(Protocol.CHAT_COMPLETIONS, stream, ReasoningLevel.OFF, host = "openrouter.ai")
            assertEquals("none", router.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertFalse(router.containsKey("thinking"))
            assertFalse(router.containsKey("reasoning_effort"))
        }
    }
}
