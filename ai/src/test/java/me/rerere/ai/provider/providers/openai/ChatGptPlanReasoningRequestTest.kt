package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** No account, network, tool execution or stored assistant settings are involved. */
class ChatGptPlanReasoningRequestTest {
    private val encoder = ResponseAPI(OkHttpClient())
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-test-account")
    private val messages = listOf(UIMessage.user("Reply with a short text answer."))
    private val restricted = setOf("gpt-6.1-sol", "gpt-6-astra")
    private val known = restricted + setOf("gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")

    @Test fun allSevenModelsAndAllSevenLevelsUseTheProductionBuilder() {
        for (slug in known) {
            // Models saved by earlier releases have TOOL only: the repair must work without reimport.
            val model = Model(modelId = slug, abilities = listOf(ModelAbility.TOOL))
            for (level in ReasoningLevel.entries) {
                val params = TextGenerationParams(model = model, reasoningLevel = level)
                val body = buildChatGptPlanRequestBody(encoder, plan, messages, params)
                val reasoning = body["reasoning"]!!.jsonObject
                val expectedEffort = when {
                    level == ReasoningLevel.AUTO -> null
                    level == ReasoningLevel.OFF && slug in restricted -> "low"
                    else -> level.effort
                }
                assertEquals("$slug $level", expectedEffort, reasoning["effort"]?.jsonPrimitive?.content)
                assertEquals(slug, body["model"]?.jsonPrimitive?.content)
                assertEquals(encoder.buildMessages(messages), body["input"])
                assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
                assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
                assertSame(model, params.model)
                assertEquals(listOf(ModelAbility.TOOL), model.abilities)
                assertEquals(level, params.reasoningLevel)
            }
        }
    }

    @Test fun unknownPlanModelsRetainExplicitCapabilitiesAndLevels() {
        for (abilities in listOf(emptyList(), listOf(ModelAbility.REASONING))) {
            for (level in ReasoningLevel.entries) {
                val params = TextGenerationParams(Model(modelId = "unknown-slug", abilities = abilities), reasoningLevel = level)
                val expected = buildChatGptPlanBody(encoder.buildRequestBody(plan, messages, params, true))
                assertEquals(expected, buildChatGptPlanRequestBody(encoder, plan, messages, params))
            }
        }
    }

    @Test fun ordinaryApiDoesNotReceivePlanModelProjection() {
        val api = ProviderSetting.OpenAI()
        for (slug in known) {
            val model = Model(modelId = slug, abilities = listOf(ModelAbility.TOOL))
            val legacy = encoder.buildRequestBody(api, messages, TextGenerationParams(model, reasoningLevel = ReasoningLevel.HIGH), true)
            assertFalse(legacy.containsKey("reasoning"))
            val explicit = encoder.buildRequestBody(api, messages, TextGenerationParams(
                model.copy(abilities = model.abilities + ModelAbility.REASONING), reasoningLevel = ReasoningLevel.OFF), true)
            assertEquals("none", explicit["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
        }
    }
}
