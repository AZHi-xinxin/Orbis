package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Current GLM request contract, with synthetic text only. No socket, model call, or key. */
class ChatCompletionsSiliconFlowRequestTest {
    private fun request(level: ReasoningLevel, maxTokens: Int = 4096, stream: Boolean = true): JsonObject {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(api, listOf(UIMessage.system("Synthetic group rule"), UIMessage.user("Hello"),
            UIMessage.assistant("Hello"), UIMessage.user("Another participant: hello"), UIMessage.user("Your turn")),
            TextGenerationParams(model = Model(modelId = "zai-org/GLM-4.5V", abilities = listOf(ModelAbility.REASONING)),
                reasoningLevel = level, maxTokens = maxTokens),
            ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn/v1"), stream) as JsonObject
    }

    @Test fun autoThinkingAndStreamingPreserveSelectedModelAndUsageFlag() {
        val body = request(ReasoningLevel.AUTO)
        assertEquals("zai-org/GLM-4.5V", body["model"]?.jsonPrimitive?.content)
        assertEquals("true", body["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content)
        assertEquals("4096", body["max_tokens"]?.jsonPrimitive?.content)
    }
    @Test fun omittedOptionalSamplingAndToolFieldsAreNotSerializedAsNullOrInvented() {
        val body = request(ReasoningLevel.AUTO)
        listOf("temperature", "top_p", "thinking_budget", "reasoning_effort", "tools", "tool_choice").forEach {
            assertFalse("unexpected $it", body.containsKey(it))
        }
    }
    @Test fun thinkingOffAndNonStreamingAreExplicitWithoutStreamOptions() {
        val body = request(ReasoningLevel.OFF, stream = false)
        assertEquals("false", body["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals("false", body["stream"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("stream_options"))
    }
    @Test fun textGroupHistoryKeepsRolesAndSeparateParticipantMessages() {
        val messages = request(ReasoningLevel.AUTO)["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user", "user"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Your turn", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(5, messages.size)
    }
    @Test fun smallSyntheticBudgetDoesNotChangeOtherRequestOptions() {
        val original = request(ReasoningLevel.AUTO)
        val small = request(ReasoningLevel.AUTO, maxTokens = 128)
        assertEquals(original.filterKeys { it != "max_tokens" }, small.filterKeys { it != "max_tokens" })
        assertEquals("128", small["max_tokens"]?.jsonPrimitive?.content)
    }
}
