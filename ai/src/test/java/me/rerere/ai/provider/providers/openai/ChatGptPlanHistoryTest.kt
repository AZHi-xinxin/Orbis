package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ChatGptPlanHistoryTest {
    private val encoder = ResponseAPI(OkHttpClient())
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "test-account")

    @Test fun switchingFromAnotherProviderDoesNotSendVisibleThinkingAsNativeReasoning() {
        val thought = UIMessagePart.Reasoning("Synthetic earlier thinking")
        val call = UIMessagePart.Tool(toolCallId = "call_123", toolName = "diagnostic_echo",
            input = "{\"value\":7}", output = listOf(UIMessagePart.Text("7")))
        val messages = listOf(UIMessage.user("Remember seven"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(thought, call, UIMessagePart.Text("Seven."))),
            UIMessage.user("Continue"))
        val before = encoder.buildMessages(messages)
        assertTrue(before.any { it.jsonObject["type"] == JsonPrimitive("reasoning") })
        val body = buildChatGptPlanRequestBody(encoder, plan, messages,
            TextGenerationParams(Model(modelId = "gpt-6-astra", abilities = listOf(ModelAbility.TOOL))))
        val input = body.getValue("input").jsonArray
        assertFalse(input.any { it.jsonObject["type"] == JsonPrimitive("reasoning") })
        assertEquals(before.filterNot { it.jsonObject["type"] == JsonPrimitive("reasoning") }.map {
            if (it.jsonObject["type"] == JsonPrimitive("function_call"))
                JsonObject(it.jsonObject + ("namespace" to JsonPrimitive("orbis"))) else it
        }, input.toList())
        assertEquals(before, encoder.buildMessages(messages))
        assertEquals("Synthetic earlier thinking", thought.reasoning)
        assertEquals("{\"value\":7}", call.input)
    }

    @Test fun nativeEncryptedReasoningSurvivesIncludingMetadataOnlyToolContinuation() {
        val reasoning = UIMessagePart.Reasoning("", metadata = OpenAIReasoningMetadata(
            reasoningId = "rs_native", encryptedContent = "opaque-native-state").toMetadata())
        val messages = listOf(UIMessage.user("Continue"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning)))
        val body = buildChatGptPlanRequestBody(encoder, plan, messages,
            TextGenerationParams(Model(modelId = "gpt-6-astra")))
        assertEquals(encoder.buildMessages(messages), body["input"])
        assertTrue(body["input"].toString().contains("opaque-native-state"))
    }

    @Test fun importedSummaryAndIdWithoutEncryptedStateAreNotReplayedAsStoredItems() {
        val base = Json.parseToJsonElement("""{"input":[
            {"type":"reasoning","summary":[{"type":"summary_text","text":"visible"}]},
            {"type":"reasoning","id":"rs_old","summary":[]},
            {"type":"reasoning","id":"rs_blank","encrypted_content":" ","summary":[]},
            {"role":"assistant","content":"Visible answer"},
            {"role":"user","content":"Continue"}
        ]}""").jsonObject
        val result = buildChatGptPlanBody(base)["input"]!!.jsonArray
        assertEquals(base["input"]!!.jsonArray.takeLast(2), result.toList())
        assertEquals(5, base["input"]!!.jsonArray.size)
    }
}
