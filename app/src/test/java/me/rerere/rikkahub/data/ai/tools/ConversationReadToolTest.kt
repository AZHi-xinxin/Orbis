package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ConsultationConversationBinding
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationReadToolTest {
    private val owner = Uuid.random()
    private fun args(id: Uuid, offset: Int = 0, limit: Int = 20, textOffset: Int = 0) = buildJsonObject {
        put("conversation_id", id.toString()); put("offset", offset); put("limit", limit); put("text_offset", textOffset)
    }
    private fun parse(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    @Test fun currentBranchPagesWithoutExecutingOrReturningToolAndReasoningParts() = runTest {
        val secret = "PRIVATE-TOOL-CANARY"
        val reply = UIMessage.assistant("public reply").copy(parts = listOf(UIMessagePart.Text("public reply"),
            UIMessagePart.Reasoning(secret), UIMessagePart.Tool("call", "orbis_private_room_read", secret, listOf(UIMessagePart.Text(secret)))))
        val current = Conversation(assistantId = owner, messageNodes = listOf(
            UIMessage.system("system").toMessageNode(), UIMessage.user("question").toMessageNode(), reply.toMessageNode(),
            UIMessage.assistant("recalled").copy(orbisRecalled = true).toMessageNode()))
        val tool = createConversationReadTool(owner) { current }
        val first = parse(tool.execute(args(current.id, limit = 1)))
        assertEquals(2, first.getValue("total").jsonPrimitive.int)
        assertTrue(first.getValue("has_more").jsonPrimitive.boolean)
        assertEquals("question", first.getValue("messages").jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val second = parse(tool.execute(args(current.id, offset = 1)))
        assertEquals("public reply", second.getValue("messages").jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.trimEnd())
        assertFalse(second.toString().contains(secret)); assertFalse(second.toString().contains("recalled"))
        assertFalse(second.getValue("has_more").jsonPrimitive.boolean)
        assertTrue(current.currentMessages[2].parts.toString().contains(secret))
    }

    @Test fun crossAssistantRequiresExplicitScopeAndNeverReadsForeignConsultation() = runTest {
        var current = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("foreign").toMessageNode()))
        val own = createConversationReadTool(owner) { current }
        val global = createConversationReadTool(owner, allAssistants = true) { current }
        assertEquals("not_found", parse(own.execute(args(current.id)))["status"]!!.jsonPrimitive.content)
        assertEquals("ok", parse(global.execute(args(current.id)))["status"]!!.jsonPrimitive.content)
        current = current.copy(consultation = ConsultationConversationBinding("synthetic", "subject"))
        assertEquals("not_found", parse(global.execute(args(current.id)))["status"]!!.jsonPrimitive.content)
        current = current.copy(assistantId = owner)
        assertEquals("ok", parse(own.execute(args(current.id)))["status"]!!.jsonPrimitive.content)
    }

    @Test fun longMessageCanBeReadCompletelyAndSwitchOffDuringReadDoesNotReturnIt() = runTest {
        val text = "a".repeat(11999) + "🌟" + "后半段".repeat(5000)
        val current = Conversation(assistantId = owner, messageNodes = listOf(UIMessage.user(text).toMessageNode()))
        var enabled = true
        val tool = createConversationReadTool(owner, enabled = { enabled }) { current }
        var offset = 0
        val collected = StringBuilder()
        do {
            val part = parse(tool.execute(args(current.id, limit = 1, textOffset = offset)))["messages"]!!.jsonArray.single().jsonObject
            collected.append(part["text"]!!.jsonPrimitive.content)
            offset = part["next_text_offset"]!!.jsonPrimitive.int
        } while (part["text_has_more"]!!.jsonPrimitive.boolean)
        assertEquals(text, collected.toString())
        val revoked = createConversationReadTool(owner, enabled = { enabled }) { enabled = false; current }
        assertTrue(runCatching { revoked.execute(args(current.id)) }.isFailure)
        assertFalse(revoked.isApprovalCurrent())
    }
}
