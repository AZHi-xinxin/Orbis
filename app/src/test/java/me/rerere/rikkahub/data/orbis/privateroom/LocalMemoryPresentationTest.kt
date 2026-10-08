package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationContextSelection
import me.rerere.rikkahub.data.ai.PrivateRoomGenerationPresentation
import me.rerere.rikkahub.data.db.fts.extractFtsText
import me.rerere.rikkahub.data.favorite.favoriteSnapshot
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.web.dto.toDto
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Display boundaries only: local-memory calls must not inherit private-room execution semantics. */
class LocalMemoryPresentationTest {
    private val secret = "SYNTHETIC_LOCAL_MEMORY_BODY_SUMMARY_VERSION"
    private val publicReply = "已保存这条便签，接下来继续聊天。"
    private val json = Json { encodeDefaults = true }
    private fun tool(name: String = "orbis_memory") = UIMessagePart.Tool(
        "synthetic-memory-call", name, "{\"body\":\"$secret\"}", listOf(UIMessagePart.Text(secret)))
    private fun source(part: UIMessagePart = tool()): UIMessage = UIMessage.assistant(publicReply).copy(
        parts = listOf(part, UIMessagePart.Reasoning(secret), UIMessagePart.Text(publicReply)), translation = secret)

    @Test fun exactMemoryToolHidesArgumentsResultsAndSubsequentThinkingButKeepsPublicReplyAndRawContext() {
        val raw = source()
        val before = json.encodeToString(raw)
        val safe = raw.privateRoomSafePresentation()
        assertEquals(publicReply, safe.toText())
        assertEquals(listOf(UIMessagePart.Text(publicReply)), safe.parts)
        assertFalse(json.encodeToString(safe).contains(secret))
        assertFalse(safe.hasPrivateRoomToolContent())
        assertEquals(safe, safe.privateRoomSafePresentation())
        assertEquals(before, json.encodeToString(raw))
        assertTrue(GenerationContextSelection(listOf(raw), 0).requestMessages().single().getTools().single().input.contains(secret))
    }

    @Test fun streamedNamesAndMalformedArgumentsAreProtectedWithoutParsingOrPersistentFlags() {
        for (name in listOf("", "o", "orbis_", "orbis_m", "orbis_memor", "orbis_memory")) {
            val raw = source(tool(name).copy(input = "{\"body\":\"$secret", output = emptyList()))
            assertFalse(raw.privateRoomContentHidden)
            assertFalse(raw.privateRoomPendingPresentation)
            assertTrue(raw.hasPrivateRoomToolContent())
            assertFalse(json.encodeToString(raw.privateRoomSafePresentation()).contains(secret))
            assertEquals(publicReply, raw.privateRoomSafePresentation().toText())
        }
        val denied = source(tool().copy(approvalState = ToolApprovalState.Denied(secret)))
        assertFalse(json.encodeToString(denied.privateRoomSafePresentation()).contains(secret))
    }

    @Test fun unrelatedMcpNamesSuffixesAndLiteralProseNeverBecomeMemoryOperations() {
        for (name in listOf("mcp__brain__orbis_memory", "orbis_memory_read", "Orbis_Memory", "orbis_memories", "search_web")) {
            val raw = source(tool(name))
            assertFalse(isLocalMemoryToolName(name))
            assertFalse(raw.hasPrivateRoomToolContent())
            assertSame(raw, raw.privateRoomSafePresentation())
        }
        val ordinary = UIMessage.assistant("orbis_memory is only a name here")
        assertSame(ordinary, ordinary.privateRoomSafePresentation())
    }

    @Suppress("DEPRECATION")
    @Test fun legacyPartsAndDeletedRecordsUseMemoryNoticeWithoutRevealingUndoVersions() {
        for (part in listOf(tool(), UIMessagePart.ToolCall("legacy", "orbis_memory", secret),
            UIMessagePart.ToolResult("legacy", "orbis_memory", JsonPrimitive(secret), JsonPrimitive(secret)))) {
            val raw = UIMessage.assistant("").copy(parts = listOf(part))
            assertEquals(LOCAL_MEMORY_CONTENT_HIDDEN, raw.privateRoomSafePresentation().toText())
            assertFalse(json.encodeToString(raw.privateRoomSafePresentation()).contains(secret))
        }
        val raw = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Reasoning(secret)))
        val deleted = raw.copy(deletedToolRecords = listOf(DeletedToolRecord(tool(), 0, raw.createdAt)))
        val safe = deleted.privateRoomSafePresentation()
        assertEquals(LOCAL_MEMORY_CONTENT_HIDDEN, safe.toText())
        assertTrue(safe.deletedToolRecords.isEmpty())
        assertEquals(1, deleted.deletedToolRecords.size)
        assertFalse(json.encodeToString(safe).contains(secret))
        assertNull(deleted.privateRoomAutomaticSpeechText())
    }

    @Test fun memoryDisplayClassificationNeverTriggersRoomGuardOrBlocksOrdinaryAndStTools() {
        assertFalse(isPrivateRoomToolName("orbis_memory"))
        assertFalse(tool().isPrivateRoomToolPart())
        val ordinary = tool("normal_tool").copy(input = "public args", output = emptyList())
        val st = tool("mcp__stbrain__hold").copy(input = "public args", output = emptyList())
        val raw = source().copy(parts = listOf(tool(), ordinary, st, UIMessagePart.Text(publicReply)))
        val guard = PrivateRoomGenerationPresentation(enabled = true)
        val classified = guard.classify(listOf(raw), completed = true)
        assertFalse(guard.hasPrivateOperation)
        assertFalse(classified.single().privateRoomContentHidden)
        assertFalse(classified.single().privateRoomPendingPresentation)
        assertEquals(raw.parts, guard.protectOutstandingTools(classified).single().parts)
        assertSame(ordinary, guard.protectTool(ordinary))
        assertSame(st, guard.protectTool(st))
        assertEquals(listOf(ordinary, st), classified.single().privateRoomSafePresentation().getTools())
        // Existing private-room guard still isolates actual private-room execution.
        guard.classify(listOf(source(tool("orbis_private_room_read"))), completed = true)
        assertTrue(guard.hasPrivateOperation)
        assertTrue(guard.protectTool(ordinary).approvalState is ToolApprovalState.Denied)
    }

    @Test fun earlierThinkingAndOrdinaryToolsRemainWhileMemoryThinkingAndTaggedThoughtsAreHidden() {
        val before = UIMessagePart.Reasoning("ordinary thought before memory")
        val ordinary = tool("normal_tool").copy(input = "public args", output = emptyList())
        val raw = source().copy(parts = listOf(before, tool(), UIMessagePart.Text("<think>$secret</think>$publicReply"), ordinary))
        val safe = raw.privateRoomSafePresentation()
        assertSame(before, safe.parts.first())
        assertEquals(listOf(before), safe.parts.filterIsInstance<UIMessagePart.Reasoning>())
        assertEquals(listOf(ordinary), safe.getTools())
        // toText() inserts separators for the intentionally retained reasoning/tool parts.
        assertEquals(listOf(publicReply), safe.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertFalse(json.encodeToString(safe).contains(secret))
    }

    @Test fun webBranchesFavoritesAndSecondaryOutputsShareTheSameHumanBoundary() {
        val raw = source()
        val node = MessageNode(messages = listOf(UIMessage.assistant("other public branch"), raw), selectIndex = 1)
        assertFalse(json.encodeToString(raw.toDto()).contains(secret))
        assertFalse(json.encodeToString(node.privateRoomSafePresentation()).contains(secret))
        assertEquals(1, node.privateRoomSafePresentation().selectIndex)
        val favorite = NodeFavoriteTarget(Uuid.random(), "public title", node.id, node).favoriteSnapshot()
        assertFalse(json.encodeToString(favorite).contains(secret))
        assertEquals(publicReply, raw.extractFtsText())
        assertEquals(publicReply, raw.privateRoomAutomaticSpeechText())
        assertFalse(privateRoomPublicSummaryInput(listOf(raw)).contains(secret))
        assertEquals(publicReply, raw.toMessageNode().privateRoomSafePresentation().currentMessage.toText())
    }

    @Test fun standalonePublicPartsNeverProduceAPlaceholderInSummaryOrSpeechInputs() {
        val parts = listOf(tool(), UIMessagePart.Text("<think>$secret</think>"))
        assertEquals(LOCAL_MEMORY_CONTENT_HIDDEN,
            (privateRoomPublicParts(parts).single() as UIMessagePart.Text).text)
        assertTrue(privateRoomPublicParts(parts, includePlaceholder = false)
            .filterIsInstance<UIMessagePart.Text>().all { it.text.isBlank() })
        assertEquals(PRIVATE_ROOM_CONTENT_HIDDEN,
            (privateRoomPublicParts(listOf(tool("orbis_private_room_read"))).single() as UIMessagePart.Text).text)
    }
}
