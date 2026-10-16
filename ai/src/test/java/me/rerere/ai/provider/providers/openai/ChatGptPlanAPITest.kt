package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.stream.SseEvent
import org.junit.Assert.*
import org.junit.Test

class ChatGptPlanAPITest {
    @Test fun mandatoryRequestConstraintsWinOverInput() {
        val body = buildChatGptPlanBody(Json.parseToJsonElement("""{"model":"account-model","store":true,"stream":false,"temperature":0.8,"top_p":1,"max_output_tokens":99,"previous_response_id":"old","background":true,"metadata":{"secret":"hidden"},"input":[{"type":"message","role":"system","content":"role and memory"}]}""").jsonObject)
        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        listOf("temperature", "top_p", "max_output_tokens", "previous_response_id", "background", "metadata").forEach { assertFalse(body.containsKey(it)) }
        assertEquals("developer", body["input"]?.jsonArray?.first()?.jsonObject?.get("role")?.jsonPrimitive?.content)
    }
    @Test fun localToolsAndHistoryUseOneNamespaceWithoutHostedMcp() {
        val body = buildChatGptPlanBody(Json.parseToJsonElement("""{"model":"x","input":[{"type":"function_call","name":"memory_read","call_id":"c","arguments":"{}"},{"type":"function_call_output","call_id":"c","output":"hello"}],"tools":[{"type":"function","name":"memory_read","parameters":{"type":"object"}},{"type":"web_search"}]}""").jsonObject)
        val tools = body["tools"]!!.jsonArray
        assertEquals("namespace", tools.first().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("orbis", tools.first().jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("orbis", body["input"]!!.jsonArray.first().jsonObject["namespace"]?.jsonPrimitive?.content)
        assertEquals("c", body["input"]!!.jsonArray.last().jsonObject["call_id"]?.jsonPrimitive?.content)
    }
    @Test fun hostedImageToolsRejectedRatherThanSilentlySwitchedToPaidApi() {
        assertThrows(IllegalArgumentException::class.java) { buildChatGptPlanBody(Json.parseToJsonElement("""{"input":[],"tools":[{"type":"image_generation"}]}""").jsonObject) }
    }
    private fun event(body: String) = SseEvent(data = body)
    @Test fun partialTextThenEofOrDoneIsNotSuccessful() {
        val decoder = ChatGptPlanDecoder()
        decoder.accept(event("""{"type":"response.output_text.delta","item_id":"i","delta":"partial"}"""))
        assertThrows(IllegalStateException::class.java) { decoder.onClosed() }
        assertThrows(IllegalStateException::class.java) { decoder.accept(event("[DONE]")) }
    }
    @Test fun onlyExplicitCompletedSucceeds() {
        val decoder = ChatGptPlanDecoder()
        assertTrue(decoder.accept(event("""{"type":"response.completed","response":{"status":"completed","id":"response"}}""")).completed)
        assertTrue(decoder.onClosed().isEmpty())
    }
    @Test fun misleadingTerminalSseHeaderCannotCompletePartialJsonContent() {
        for (header in listOf("response.completed", "response.incomplete")) {
            val decoder = ChatGptPlanDecoder()
            assertThrows(IllegalStateException::class.java) {
                decoder.accept(SseEvent(event = header,
                    data = """{"type":"response.output_text.delta","item_id":"i","delta":"partial"}"""))
            }
            assertThrows(IllegalStateException::class.java) { decoder.onClosed() }
        }
    }
    @Test fun incompleteAndUsageFailureNeverCountAsSuccess() {
        assertThrows(IllegalStateException::class.java) { ChatGptPlanDecoder().accept(event("""{"type":"response.incomplete"}""")) }
        val error = assertThrows(IllegalStateException::class.java) { ChatGptPlanDecoder().accept(event("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"private detail"}}}""")) }
        assertEquals(me.rerere.ai.provider.chatgpt.ChatGptFailureKind.LIMIT, (error as me.rerere.ai.provider.chatgpt.ChatGptPlanFailure).kind)
    }
    @Test fun terminalSnapshotCannotBypassNamespaceAndDeclaredFunctionChecks() {
        listOf("\"elsewhere\"", "null").forEach { namespace ->
            val decoder = ChatGptPlanDecoder(setOf("memory_read"))
            assertThrows(IllegalArgumentException::class.java) { decoder.accept(event("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","namespace":$namespace,"name":"memory_read","call_id":"c","arguments":"{}"}]}}""")) }
            assertThrows(IllegalStateException::class.java) { decoder.onClosed() }
        }
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanDecoder(emptySet()).accept(event("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","namespace":"orbis","name":"memory_read"}]}}""")) }
    }
    @Test fun validDeclaredToolNamespaceIsAcceptedAtAddedAndTerminalBoundaries() {
        val decoder = ChatGptPlanDecoder(setOf("memory_read"))
        decoder.accept(event("""{"type":"response.output_item.added","item":{"type":"function_call","id":"i","call_id":"c","namespace":"orbis","name":"memory_read","arguments":"{}"}}"""))
        assertTrue(decoder.accept(event("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","id":"i","namespace":"orbis","name":"memory_read"}]}}""")).completed)
    }
    @Test fun completedMarkerWithFailedStatusDoesNotCommitPartialTools() {
        assertThrows(IllegalStateException::class.java) { ChatGptPlanDecoder().accept(event("""{"type":"response.completed","response":{"status":"incomplete"}}""")) }
        assertThrows(IllegalStateException::class.java) { ChatGptPlanDecoder().accept(event("""{"type":"response.completed","response":{"status":"completed","error":{"code":"bad"}}}""")) }
    }
}
