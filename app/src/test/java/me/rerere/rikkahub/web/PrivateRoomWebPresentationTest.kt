package me.rerere.rikkahub.web

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.privateroom.PRIVATE_ROOM_CONTENT_HIDDEN
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ConversationNodeUpdateEvent
import me.rerere.rikkahub.web.dto.ConversationSnapshotEvent
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.dto.webPresentationError
import me.rerere.rikkahub.web.dto.requireWebEditableMessage
import me.rerere.rikkahub.web.routes.contextBoundaries
import me.rerere.rikkahub.web.routes.singleNodeDiffOrNull
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PrivateRoomWebPresentationTest {
    private val snapshotJson = Json(JsonInstant) { encodeDefaults = true }
    private val secret = "SYNTHETIC_PRIVATE_WEB_CANARY"
    private val publicReply = "已保存，可以继续聊天。"
    private fun privateMessage(name: String = "orbis_private_room_read") = UIMessage.assistant(publicReply).copy(
        parts = listOf(UIMessagePart.Text(publicReply),
            UIMessagePart.Tool("private-call", name, "{\"body\":\"$secret\"}", listOf(UIMessagePart.Text(secret),
                UIMessagePart.Document("file:///upload/$secret", secret))), UIMessagePart.Reasoning(secret)), translation = secret)
    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })
    private fun pendingPrivateMessage() = UIMessage.assistant(publicReply).copy(privateRoomPendingPresentation = true,
        parts = listOf(UIMessagePart.Text(publicReply),
            UIMessagePart.Tool("partial-call", "orbis_private_room_r", secret, emptyList())))

    @Test fun allPrivateOperationsHideDetailsButKeepPublicTextWithoutChangingHistory() {
        for (suffix in listOf("visit", "list", "read", "write", "requests", "decide", "revoke")) {
            val message = privateMessage("orbis_private_room_$suffix")
            val before = snapshotJson.encodeToString(message)
            val dto = message.toDto()
            assertFalse(JsonInstant.encodeToString(dto).contains(secret))
            assertEquals(listOf(UIMessagePart.Text(publicReply)), dto.parts)
            assertEquals(message.id.toString(), dto.id)
            assertNull(dto.translation)
            assertEquals(before, snapshotJson.encodeToString(message))
        }
    }

    @Test fun restAndSseSnapshotProtectToolDetailsAcrossEveryBranchAndKeepPublicSuggestions() {
        val private = privateMessage()
        val source = conversation().copy(messageNodes = listOf(MessageNode(messages =
            listOf(UIMessage.assistant("public branch"), private), selectIndex = 0)), chatSuggestions = listOf("public suggestion"))
        val dto = source.toDto()
        assertFalse(JsonInstant.encodeToString(dto).contains(secret))
        assertFalse(JsonInstant.encodeToString(ConversationSnapshotEvent(seq = 1, conversation = dto)).contains(secret))
        assertEquals("public branch", (dto.messages.single().messages.first().parts.single() as UIMessagePart.Text).text)
        assertEquals(listOf("public suggestion"), dto.chatSuggestions)
        assertEquals(source.chatSuggestions, dto.chatSuggestions)
        assertSame(private, source.messageNodes.single().messages[1])
        assertEquals(publicReply, source.messageNodes.single().messages[1].parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test fun sseIncrementalDiffAlsoContainsOnlySafeProjection() {
        val initial = conversation(UIMessage.user("public request"))
        val changed = initial.copy(messageNodes = initial.messageNodes + privateMessage().toMessageNode())
        val diff = checkNotNull(initial.toDto().singleNodeDiffOrNull(changed.toDto()))
        val encoded = JsonInstant.encodeToString(ConversationNodeUpdateEvent(seq = 2,
            conversationId = changed.id.toString(), nodeId = diff.node.id, nodeIndex = diff.nodeIndex,
            node = diff.node, updateAt = changed.updateAt.toEpochMilli(), isGenerating = true))
        assertFalse(encoded.contains(secret))
        assertTrue(encoded.contains(publicReply))
    }

    @Test fun pendingBeforeAnyToolKeepsPriorReasoningButPrivateContinuationsHideReasoning() {
        val prior = UIMessage.assistant(publicReply).copy(parts = listOf(UIMessagePart.Text(publicReply),
            UIMessagePart.Reasoning("public prior reasoning")), privateRoomPendingPresentation = true)
        assertEquals(prior.parts, prior.toDto().parts)
        conversation(prior).requireWebEditableMessage(prior.id)
        val continuation = UIMessage.assistant(publicReply).copy(parts = listOf(UIMessagePart.Text(publicReply),
            UIMessagePart.Reasoning(secret)), privateRoomContentHidden = true)
        assertFalse(JsonInstant.encodeToString(continuation.toDto()).contains(secret))
        assertEquals(listOf(UIMessagePart.Text(publicReply)), continuation.toDto().parts)
    }

    @Suppress("DEPRECATION")
    @Test fun legacyPartsAndDeletedToolUndoHistoryKeepPublicTextWithoutExposingPrivateDetails() {
        val source = privateMessage()
        val variants = listOf(
            source.copy(parts = listOf(UIMessagePart.Text(publicReply), UIMessagePart.ToolCall("old", "orbis_private_room_write", secret))),
            source.copy(parts = listOf(UIMessagePart.ToolResult("old", "orbis_private_room_read", JsonPrimitive(secret), JsonPrimitive(secret)))),
            UIMessage.assistant(publicReply).copy(deletedToolRecords = listOf(DeletedToolRecord(source.getTools().single(), 0, source.createdAt))),
        )
        variants.forEach { assertFalse(JsonInstant.encodeToString(it.toDto()).contains(secret)) }
        assertEquals(publicReply, (variants.first().toDto().parts.single() as UIMessagePart.Text).text)
        assertEquals(PRIVATE_ROOM_CONTENT_HIDDEN, (variants[1].toDto().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun publicAndUnrelatedMcpToolsRemainVisible() {
        val source = privateMessage("mcp__other__orbis_private_room_read")
        assertEquals(source.parts, source.toDto().parts)
        val public = conversation(UIMessage.assistant("public")).copy(chatSuggestions = listOf("public suggestion"))
        assertEquals(public.chatSuggestions, public.toDto().chatSuggestions)
    }

    @Test fun manualContextBoundariesShowPublicTextWithoutPrivateToolDetails() {
        val source = conversation(UIMessage.user("public"), privateMessage(), UIMessage.assistant("other"))
        val rows = contextBoundaries(source, 2)
        assertFalse(JsonInstant.encodeToString(rows).contains(secret))
        assertEquals(publicReply, rows.first { it.number == 2 }.text)
    }

    @Test fun privateErrorsNeverEchoProviderBodyUrlOrCause() {
        val failure = IllegalStateException("https://$secret token=$secret", RuntimeException(secret))
        assertFalse(conversation(privateMessage()).webPresentationError(failure).contains(secret))
        assertFalse(conversation(pendingPrivateMessage())
            .webPresentationError(failure).contains(secret))
        assertEquals("public error", conversation().webPresentationError(IllegalStateException("public error")))
    }

    @Test fun textOnlyProjectionCannotOverwritePrivatePartsButPublicMessagesRemainEditable() {
        for (message in listOf(privateMessage(), pendingPrivateMessage(),
            UIMessage.assistant(secret).copy(privateRoomContentHidden = true))) {
            val public = UIMessage.user("public")
            val source = conversation(public, message)
            val before = snapshotJson.encodeToString(source)
            try {
                source.requireWebEditableMessage(message.id)
                fail("Private source must not accept its display-only text as a replacement")
            } catch (expected: ConflictException) {
                assertFalse(expected.message.orEmpty().contains(secret))
            }
            source.requireWebEditableMessage(public.id)
            assertEquals(before, snapshotJson.encodeToString(source))
        }
    }

    @Test fun textIsPublicByDefinitionEvenWhenItsContentsMatchAPrivateRecord() {
        val message = privateMessage().copy(parts = listOf(UIMessagePart.Text(secret)) + privateMessage().parts.drop(1))
        assertEquals(listOf(UIMessagePart.Text(secret)), message.toDto().parts)
    }

    @Test fun reasoningBeforeTheFirstPrivateToolStaysVisibleAndOnlyLaterReasoningIsHidden() {
        val before = UIMessagePart.Reasoning("public before private tool")
        val source = privateMessage().let { it.copy(parts = listOf(before) + it.parts) }
        val originalParts = source.parts.toList()
        val dto = source.toDto()
        assertEquals(listOf(before, UIMessagePart.Text(publicReply)), dto.parts)
        assertFalse(JsonInstant.encodeToString(dto).contains(secret))
        assertEquals(originalParts, source.parts)
        assertTrue(source.parts.filterIsInstance<UIMessagePart.Reasoning>().any { it.reasoning == secret })
    }

    @Test fun ordinaryToolRecordsStayVisibleEvenAfterAPrivateToolInTheSameTurn() {
        val ordinary = UIMessagePart.Tool("public-call", "public_local_tool", "{\"value\":\"public\"}",
            listOf(UIMessagePart.Text("public tool result")))
        val source = privateMessage().let { it.copy(parts = it.parts + ordinary) }
        val dto = source.toDto()
        assertTrue(dto.parts.contains(ordinary))
        assertEquals(1, dto.parts.filterIsInstance<UIMessagePart.Tool>().size)
        assertFalse(JsonInstant.encodeToString(dto).contains(secret))
        assertTrue(JsonInstant.encodeToString(dto).contains("public tool result"))
    }
}
