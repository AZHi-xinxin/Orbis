package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Uses the real request serializer; all material is synthetic and no network call is made. */
class GenerationSnapshotWireTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
        "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
        ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    private fun wire(messages: List<UIMessage>, reasoning: Boolean = true): JsonObject = method.invoke(
        api, messages,
        TextGenerationParams(
            model = Model(modelId = "deepseek-chat", abilities = listOf(ModelAbility.REASONING)),
            maxTokens = 4096, reasoningLevel = ReasoningLevel.HIGH,
        ),
        ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:1/v1", includeHistoryReasoning = reasoning),
        false,
    ) as JsonObject

    private fun call(id: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = "synthetic_read", input = """{"query":"$id"}""",
    )

    private fun completed(call: UIMessagePart.Tool) = call.copy(
        output = listOf(UIMessagePart.Text("synthetic-result-${call.toolCallId}")),
    )

    private fun initial() = listOf(UIMessage.system("stable synthetic identity"), UIMessage.user("first question"))

    private fun assertPrefix(before: JsonObject, after: JsonObject) {
        val previous = before.getValue("messages").jsonArray
        val next = after.getValue("messages").jsonArray
        assertTrue(next.size > previous.size)
        // Compare serialized JSON, not just UIMessage identity or list length.
        assertEquals(previous.toString(), JsonArray(next.take(previous.size)).toString())
        assertEquals(before.filterKeys { it != "messages" }, after.filterKeys { it != "messages" })
    }

    @Test fun consecutiveToolOnlyResponsesKeepEveryPreviouslySerializedMessage() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        var previous = wire(snapshot.input { initial() })
        repeat(4) { index ->
            val tool = call("call-$index")
            snapshot.appendCompletedResponse(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)), listOf(completed(tool)),
            )
            val current = wire(snapshot.input { error("initial transformation must run once") })
            assertPrefix(previous, current)
            val serialized = current.getValue("messages").jsonArray
            assertEquals(1, serialized[serialized.size - 2].jsonObject.getValue("tool_calls").jsonArray.size)
            assertEquals("call-$index", serialized.last().jsonObject["tool_call_id"]?.jsonPrimitive?.content)
            previous = current
        }
    }

    @Test fun legacyMergedToolOnlyBubbleReproducesTheChangedEarlierArray() {
        val first = completed(call("call-one"))
        val second = completed(call("call-two"))
        val earlier = wire(initial() + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first)))
            .getValue("messages").jsonArray
        val merged = wire(initial() + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, second)))
            .getValue("messages").jsonArray
        assertNotEquals(earlier.toString(), JsonArray(merged.take(earlier.size)).toString())
        assertEquals(1, earlier[2].jsonObject.getValue("tool_calls").jsonArray.size)
        assertEquals(2, merged[2].jsonObject.getValue("tool_calls").jsonArray.size)
    }

    @Test fun reasoningAndParallelResultsKeepOrderAcrossAutomaticSteps() = runBlocking {
        for (includeReasoning in listOf(true, false)) {
            val snapshot = GenerationInputSnapshot()
            var previous = wire(snapshot.input { initial() }, includeReasoning)
            repeat(3) { index ->
                val a = call("a-$index")
                val b = call("b-$index")
                snapshot.appendCompletedResponse(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "synthetic reasoning $index"),
                    UIMessagePart.Text("synthetic step $index"), a, b,
                )), listOf(completed(b), completed(a)))
                val current = wire(snapshot.input { error("unexpected retransform") }, includeReasoning)
                assertPrefix(previous, current)
                val frames = current.getValue("messages").jsonArray
                assertEquals(listOf("a-$index", "b-$index"), frames.takeLast(2).map {
                    it.jsonObject.getValue("tool_call_id").jsonPrimitive.content
                })
                previous = current
            }
        }
    }

    @Test fun newHumanInvocationUsesFreshInputAndDoesNotCarryPreviousSnapshot() = runBlocking {
        val first = GenerationInputSnapshot()
        first.input { initial() }
        val tool = call("old-call")
        first.appendCompletedResponse(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)), listOf(completed(tool)))
        val next = GenerationInputSnapshot()
        val newBody = wire(next.input {
            listOf(UIMessage.system("new synthetic device state"), UIMessage.user("new topic"))
        })
        assertEquals(2, newBody.getValue("messages").jsonArray.size)
        assertTrue(newBody.toString().contains("new synthetic device state"))
        assertFalse(newBody.toString().contains("old-call"))
    }
}
