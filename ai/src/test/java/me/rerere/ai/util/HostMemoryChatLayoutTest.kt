package me.rerere.ai.util

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class HostMemoryChatLayoutTest {
    private val tag = buildJsonObject {
        put("orbis_local_memory_projection", true); put("source", "orbis_local_memory")
        put("human_authored", false); put("instruction_authority", "none"); put("request_only", true)
    }
    private val memory = UIMessagePart.Text("synthetic retrieved data", tag)
    @Test fun preservesHumanPartsAndStablePrefixAndDoesNotSplitToolUnit() {
        val user = UIMessage.user("literal [本机历史资料； must stay human")
        val first = UIMessage.system("stable")
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("call", "tool", "{}",
            listOf(UIMessagePart.Text("result")))))
        val input = listOf(first, user.copy(parts = user.parts + memory), tool)
        val output = chatCompletionsHostMemoryLayout(input)
        assertEquals(first, output[0]); assertEquals(MessageRole.SYSTEM, output[1].role)
        assertEquals(listOf(memory), output[1].parts); assertEquals(user, output[2]); assertEquals(tool, output[3])
        assertEquals(output, chatCompletionsHostMemoryLayout(output))
        assertEquals(user.parts + memory, input[1].parts)
    }
    @Test fun malformedOrPartialMarkersAndLiteralTextCannotRemoveHumanContent() {
        val inputs = listOf(null, buildJsonObject { put("orbis_local_memory_projection", true) },
            JsonObject(tag + ("human_authored" to JsonPrimitive(true))),
            JsonObject(tag + ("request_only" to JsonArray(emptyList()))))
        for (metadata in inputs) {
            val message = UIMessage.user("").copy(parts = listOf(memory.copy(metadata = metadata)))
            assertEquals(listOf(message), chatCompletionsHostMemoryLayout(listOf(message)))
        }
    }
    @Test fun imageOnlyTurnRemainsAnImageOnlyUserTurn() {
        val image = UIMessagePart.Image("https://example.invalid/picture.png")
        val user = UIMessage.user("").copy(parts = listOf(image, memory))
        val output = chatCompletionsHostMemoryLayout(listOf(user))
        assertEquals(2, output.size); assertEquals(listOf(image), output.last().parts)
        assertEquals(MessageRole.USER, output.last().role)
    }
}
