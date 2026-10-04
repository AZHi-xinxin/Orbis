package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomGenerationPresentationTest {
    @Test fun availablePrivateToolsDoNotHideNormalStreamingReasoningOrPublicReply() {
        val guard = PrivateRoomGenerationPresentation(true)
        val raw = UIMessage.assistant("ordinary response").copy(parts = listOf(
            UIMessagePart.Reasoning("ordinary ongoing thought"), UIMessagePart.Text("ordinary response")))
        val pending = guard.classify(listOf(raw), false).single()
        assertFalse(pending.privateRoomPendingPresentation)
        assertFalse(pending.privateRoomContentHidden)
        assertSame(raw, pending.privateRoomSafePresentation())
        assertTrue(pending.privateRoomSafePresentation().toText().contains("ordinary response"))
        assertEquals(raw.parts, pending.parts)
        val completed = guard.classify(listOf(pending), true).single()
        assertFalse(completed.privateRoomPendingPresentation)
        assertFalse(completed.privateRoomContentHidden)
        assertEquals(raw.toText(), completed.privateRoomSafePresentation().toText())
    }

    @Test fun ordinaryToolStreamingIsNotHiddenEvenWhenPrivateToolsAreAvailable() {
        val guard = PrivateRoomGenerationPresentation(true)
        val raw = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Reasoning("ordinary ongoing thought"),
            UIMessagePart.Tool("ordinary", "mcp__stbrain__search", "query", emptyList())))
        val classified = guard.classify(listOf(raw), false).single()
        assertFalse(classified.privateRoomPendingPresentation)
        assertFalse(classified.privateRoomContentHidden)
        assertSame(raw, classified.privateRoomSafePresentation())
    }

    @Test fun partialToolThatResolvesToOrdinaryNeverKeepsTheTurnPrivate() {
        val guard = PrivateRoomGenerationPresentation(true)
        val tool = UIMessagePart.Tool("x", "", "", emptyList())
        val raw = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Reasoning("before tool"), tool))
        val partial = guard.classify(listOf(raw), false).single()
        assertTrue(partial.privateRoomPendingPresentation)
        val resolved = partial.copy(parts = listOf(raw.parts[0], tool.copy(toolName = "ordinary_tool")))
        val classified = guard.classify(listOf(resolved), false).single()
        assertFalse(classified.privateRoomPendingPresentation)
        assertFalse(classified.privateRoomContentHidden)
        assertEquals(resolved.parts, classified.privateRoomSafePresentation().parts)
    }

    @Test fun privateDetailsStayHiddenButPublicReplyAndRawAssistantContextRemain() {
        val guard = PrivateRoomGenerationPresentation(true)
        val user = UIMessage.user("Please use your room")
        val raw = UIMessage.assistant("已保存；请到申请页提交来意。").copy(parts = listOf(
            UIMessagePart.Text("已保存；请到申请页提交来意。"),
            UIMessagePart.Reasoning("ordinary thought before visiting"),
            UIMessagePart.Tool(toolCallId = "private-write", toolName = "orbis_private_room_write",
                input = "{\"body\":\"secret sentinel\"}", output = emptyList()),
            UIMessagePart.Reasoning("secret reasoning after visiting"),
        ))
        val marked = guard.classify(listOf(user, raw), true)
        assertSame(user, marked.first())
        assertTrue(marked.last().privateRoomContentHidden)
        assertEquals(raw.parts, marked.last().parts)
        val visible = Json.encodeToString(UIMessage.serializer(), marked.last().privateRoomSafePresentation())
        assertFalse(visible.contains("secret"))
        assertTrue(visible.contains("ordinary thought before visiting"))
        assertTrue(marked.last().privateRoomSafePresentation().toText().contains("已保存；请到申请页提交来意。"))
        val continuation = guard.classify(listOf(user, UIMessage.assistant("请刷新申请状态")), true).last()
        assertTrue(continuation.privateRoomContentHidden)
        assertTrue(continuation.privateRoomSafePresentation().toText().contains("请刷新申请状态"))
    }

    @Test fun unknownPartialToolNamesCannotLeakAndCancelledPendingSurvivesRestart() {
        val guard = PrivateRoomGenerationPresentation(true)
        val raw = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "x", toolName = "orbis_priv", input = "secret unfinished", output = emptyList())))
        val pending = guard.classify(listOf(raw), false).single()
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(UIMessage.serializer(), pending))
        assertTrue(restored.privateRoomPendingPresentation)
        assertFalse(restored.privateRoomSafePresentation().toText().contains("secret unfinished"))
    }

    @Test fun noRoomPreservesOrdinaryStreamingAndHistoricalPrivateFlagsSurviveResume() {
        val raw = UIMessage.assistant("streaming normally")
        assertSame(raw, PrivateRoomGenerationPresentation(false).classify(listOf(raw), false).single())
        val resumed = raw.copy(privateRoomContentHidden = true)
        assertTrue(PrivateRoomGenerationPresentation(false).classify(listOf(resumed), true).single().privateRoomContentHidden)
    }

    @Test fun nextHumanWakeRetainsRealPrivateReceiptsForAssistantWithoutMarkingNewPublicReplyPrivate() {
        val normal = UIMessage.assistant("normal context")
        val tool = UIMessagePart.Tool("previous-write", "orbis_private_room_write", "{\"body\":\"private sentinel\"}",
            listOf(UIMessagePart.Text("{\"status\":\"saved\",\"record_id\":\"synthetic-record\"}")))
        val secret = UIMessage.assistant("已保存").copy(privateRoomContentHidden = true,
            parts = listOf(UIMessagePart.Text("已保存"), UIMessagePart.Reasoning("private reasoning"), tool))
        val human = UIMessage.user("next question")
        val history = listOf(normal, secret, human)
        val selected = GenerationContextSelection(history, 0).requestMessages()
        assertEquals(history, selected)
        assertTrue(selected[1].getTools().single().input.contains("private sentinel"))
        assertEquals(tool.output, selected[1].getTools().single().output)
        val guard = PrivateRoomGenerationPresentation(true)
        guard.classify(history, false)
        val nextRaw = UIMessage.assistant("刚才已经保存成功。").copy(parts = listOf(
            UIMessagePart.Reasoning("new ordinary thought"), UIMessagePart.Text("刚才已经保存成功。"),
            UIMessagePart.Tool("normal-next", "ordinary_tool", "normal input", emptyList())))
        val next = guard.classify(history + nextRaw, false).last()
        assertFalse(next.privateRoomContentHidden)
        assertFalse(next.privateRoomPendingPresentation)
        assertTrue(next.privateRoomSafePresentation().toText().contains("刚才已经保存成功。"))
        assertEquals(nextRaw.parts, next.privateRoomSafePresentation().parts)
        assertEquals(tool, secret.getTools().single())
    }

    @Test fun mixedOrdinaryToolsCannotExportPrivateTextOrWaitForInvisibleApproval() {
        val ordinary = UIMessagePart.Tool(toolCallId = "send", toolName = "mcp__synthetic__send",
            input = "private sentinel", output = emptyList())
        val private = ordinary.copy(toolName = "orbis_private_room_read")
        val guard = PrivateRoomGenerationPresentation(true)
        assertSame(ordinary, guard.protectTool(ordinary))
        guard.classify(listOf(UIMessage.assistant("").copy(parts = listOf(private))), true)
        assertTrue(guard.protectTool(ordinary).approvalState is me.rerere.ai.ui.ToolApprovalState.Denied)
        assertSame(private, guard.protectTool(private))
        val pending = ordinary.copy(approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)
        val completed = ordinary.copy(toolCallId = "already-executed", output = listOf(UIMessagePart.Text("done")))
        val resumed = guard.protectOutstandingTools(listOf(UIMessage.assistant("").copy(parts = listOf(private, pending, completed)))).last()
        assertFalse(resumed.getTools()[1].isPending)
        assertTrue(resumed.getTools()[1].approvalState is me.rerere.ai.ui.ToolApprovalState.Denied)
        assertSame(completed, resumed.getTools()[2])
    }

    @Test fun compactionCanUsePublicTextFromPrivateTurnButNeverItsReasoningOrToolOutput() {
        val visible = UIMessage.assistant("public summary")
        val private = UIMessage.assistant("public operation summary").copy(privateRoomContentHidden = true,
            parts = listOf(UIMessagePart.Text("public operation summary"), UIMessagePart.Reasoning("private thought"),
                UIMessagePart.Tool("read", "orbis_private_room_read", "private input", listOf(UIMessagePart.Text("private result")))))
        val prepared = me.rerere.rikkahub.data.ai.compaction.prepareCompaction(
            Json.parseToJsonElement("{\"use_last_message\":true,\"keep_recent\":0}"),
            listOf(visible, private), UIMessage.assistant(""))
        assertEquals("public operation summary", prepared.summary.toText())
        assertEquals(private.id, prepared.sourceMessageId)
        assertFalse(prepared.summary.toText().contains("private"))
    }
}
