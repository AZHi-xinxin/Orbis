package me.rerere.ai.provider.providers

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutDeletedToolRecordData
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Offline actual adapter serialization: never call a provider or open a file/network image. */
class DeletedToolRecordWireTest {
    private val client = OkHttpClient()
    private val params = TextGenerationParams(model = Model(modelId = "test-model"))
    private val protocols = listOf("chat", "responses", "claude", "google")
    private val now = LocalDateTime(2026, 9, 24, 21, 30)
    private val history = listOf(UIMessage.system("main"), UIMessage.user("question"))
    private val removed = tool("removed_secret_call", "removed_secret_result").copy(
        output = listOf(UIMessagePart.Text("removed_secret_result"),
            UIMessagePart.Image("file:///must-never-open-deleted-attachment.png")),
    )
    private val remaining = tool("remaining_call", "remaining_result")

    private fun tool(id: String, receipt: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = "tool_$id", input = "{\"arg\":\"input_$id\"}",
        output = listOf(UIMessagePart.Text(receipt)),
    )

    private fun withUndo(message: UIMessage, tools: List<UIMessagePart.Tool> = listOf(removed)) = message.copy(
        deletedToolRecords = tools.mapIndexed { index, tool -> DeletedToolRecord(tool, index, now) },
        toolRecordRevision = 3,
        toolRecordsUpdatedAt = now,
    )

    @Test fun strippingUndoMetadataKeepsOriginalPartsAndDoesNotMutateTheStoredMessage() {
        val message = withUndo(UIMessage.assistant("spoken prose"))
        val stripped = message.withoutDeletedToolRecordData()
        assertSame(message.parts, stripped.parts)
        assertEquals(message.id, stripped.id)
        assertTrue(stripped.deletedToolRecords.isEmpty())
        assertEquals(0L, stripped.toolRecordRevision)
        assertNull(stripped.toolRecordsUpdatedAt)
        assertEquals(1, message.deletedToolRecords.size)
        assertEquals(3L, message.toolRecordRevision)
        assertSame(stripped, stripped.withoutDeletedToolRecordData())
    }

    @Test fun localUndoPayloadNeverAppearsInAnyProviderWireBodyEvenWithoutCentralStripping() {
        val active = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("before"), remaining, UIMessagePart.Text("after")))
        val message = withUndo(active)
        protocols.forEach { protocol ->
            val baseline = request(protocol, history + active)
            val actual = request(protocol, history + message)
            assertEquals(protocol, baseline, actual)
            val wire = actual.toString()
            assertFalse(protocol, wire.contains("removed_secret"))
            assertFalse(protocol, wire.contains("must-never-open"))
            assertFalse(protocol, wire.contains("deletedToolRecords"))
            assertFalse(protocol, wire.contains("toolRecordRevision"))
            assertFalse(protocol, wire.contains("toolRecordsUpdatedAt"))
            assertTrue(protocol, wire.contains("remaining_call"))
            assertTrue(protocol, wire.contains("remaining_result"))
        }
    }

    @Test fun emptyAssistantAfterDeletingItsOnlyToolIsNotUploadedAsAnInvalidEmptyTurn() {
        val empty = withUndo(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        assertFalse(empty.isValidToUpload())
        val next = UIMessage.user("next human turn")
        protocols.forEach { protocol ->
            assertEquals(protocol, request(protocol, history + next),
                request(protocol, history + empty + next))
        }
    }

    @Test fun deletingOneParallelToolLeavesExactlyOneCompleteCallAndReceiptPair() {
        val message = withUndo(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(remaining)))
        protocols.forEach { protocol ->
            val array = contentArray(protocol, request(protocol, history + message))
            assertEquals(protocol, listOf("remaining_call"), callIds(protocol, array))
            assertEquals(protocol, listOf("remaining_call"), receiptIds(protocol, array))
        }
    }

    @Test fun deletingSeveralToolsPreservesAllInterleavedProseAndRetainedCalls() {
        val second = tool("second_remaining", "second_receipt")
        val active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("first prose"), remaining,
            UIMessagePart.Text("middle prose"), second, UIMessagePart.Text("last prose"),
        ))
        val tombstones = listOf(removed, tool("other_removed", "other_secret"))
        protocols.forEach { protocol ->
            val actual = request(protocol, history + withUndo(active, tombstones))
            assertEquals(protocol, request(protocol, history + active), actual)
            val array = contentArray(protocol, actual)
            assertEquals(protocol, listOf("remaining_call", "second_remaining"), callIds(protocol, array))
            assertEquals(protocol, listOf("remaining_call", "second_remaining"), receiptIds(protocol, array))
            val wire = actual.toString()
            assertTrue(protocol, wire.indexOf("first prose") < wire.indexOf("middle prose"))
            assertTrue(protocol, wire.indexOf("middle prose") < wire.indexOf("last prose"))
        }
    }

    @Test fun restoringOneRecordSendsOnlyThatRecordAndNotStillDeletedSiblings() {
        val restored = tool("restored_call", "restored_receipt")
        val active = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(restored, remaining, UIMessagePart.Text("final prose")))
        val message = withUndo(active)
        protocols.forEach { protocol ->
            val actual = request(protocol, history + message)
            val array = contentArray(protocol, actual)
            assertEquals(protocol, listOf("restored_call", "remaining_call"), callIds(protocol, array))
            assertEquals(protocol, listOf("restored_call", "remaining_call"), receiptIds(protocol, array))
            assertFalse(protocol, actual.toString().contains("removed_secret"))
        }
    }

    private fun request(protocol: String, messages: List<UIMessage>): JsonObject = when (protocol) {
        "chat" -> {
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
                "buildMessages", List::class.java, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, List::class.java,
            ).apply { isAccessible = true }
            val array = method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages,
                true, false, listOf(Modality.TEXT, Modality.IMAGE)) as JsonArray
            buildJsonObject { put("messages", array) }
        }
        "responses" -> ResponseAPI(client).buildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"), messages, params, false,
        )
        "claude" -> ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest", ProviderSetting.Claude::class.java, List::class.java,
            TextGenerationParams::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(ClaudeProvider(client),
            ProviderSetting.Claude(promptCaching = false), messages, params, false) as JsonObject
        "google" -> GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }.invoke(GoogleProvider(client), messages, params) as JsonObject
        else -> error("unknown protocol")
    }

    private fun contentArray(protocol: String, body: JsonObject): JsonArray = body[when (protocol) {
        "responses" -> "input"
        "google" -> "contents"
        else -> "messages"
    }]!!.jsonArray

    private fun callIds(protocol: String, array: JsonArray): List<String> = when (protocol) {
        "chat" -> array.flatMap { item -> item.jsonObject["tool_calls"]?.jsonArray.orEmpty() }
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        "responses" -> array.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
            .map { it.jsonObject["call_id"]!!.jsonPrimitive.content }
        "claude" -> array.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        "google" -> array.flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["functionCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
        else -> error("unknown protocol")
    }

    private fun receiptIds(protocol: String, array: JsonArray): List<String> = when (protocol) {
        "chat" -> array.mapNotNull { it.jsonObject["tool_call_id"]?.jsonPrimitive?.content }
        "responses" -> array.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
            .map { it.jsonObject["call_id"]!!.jsonPrimitive.content }
        "claude" -> array.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" }
            .map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content }
        "google" -> array.flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["functionResponse"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
        else -> error("unknown protocol")
    }
}
