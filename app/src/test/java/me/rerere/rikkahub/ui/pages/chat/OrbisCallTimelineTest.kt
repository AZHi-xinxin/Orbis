package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class OrbisCallTimelineTest {
    private fun turn(text: String, id: String = "call-1") = UIMessage.user(text)
        .copy(orbisVoiceCallId = id, orbisVoiceCallKind = "turn").toMessageNode()

    @Test fun continuousCallBecomesOneDisplayEntryWithoutChangingSource() {
        val first = UIMessage.user("before").toMessageNode()
        val last = UIMessage.user("after").toMessageNode()
        val nodes = listOf(first) + (1..200).map { turn("CALL_MODE_V1 {}\n$it") } + last
        val snapshot = nodes.toList()
        val shown = orbisCallTimeline(nodes)
        assertEquals(3, shown.size)
        assertEquals(200, shown[1].nodes.size)
        assertEquals(snapshot, nodes)
        assertEquals(1, orbisTimelineIndex(nodes, 87))
        assertEquals(2, orbisTimelineIndex(nodes, 201))
    }

    @Test fun plainTextLookalikesAreNeverHiddenAndInterleavedChatPreservesOrder() {
        val unrelated = UIMessage.user("CALL_MODE_V1 {\"active\":true}").toMessageNode()
        val nodes = listOf(turn("a"), unrelated, turn("b"), turn("c", "other-call"))
        val shown = orbisCallTimeline(nodes)
        assertEquals(3, shown.size)
        assertNull(shown[1].callId)
        assertEquals(unrelated, shown[1].nodes.single())
        assertEquals(listOf(0, 2), shown[0].sourceIndices)
        assertEquals(nodes.map { it.id }.toSet(), shown.flatMap { it.nodes }.map { it.id }.toSet())
        assertEquals(nodes.size, shown.sumOf { it.nodes.size })
        assertEquals(0, orbisTimelineIndex(nodes, 2))
        assertEquals(1, orbisTimelineIndex(nodes, 1))
        assertEquals(2, orbisTimelineIndex(nodes, 3))
    }

    @Test fun pendingToolApprovalRemainsActionableNotInsideFoldedCall() {
        val tool = UIMessage.assistant("").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Tool(toolCallId = "tool-1", toolName = "toy", input = "{}",
                approvalState = ToolApprovalState.Pending))).toMessageNode()
        val shown = orbisCallTimeline(listOf(turn("a"), tool, turn("b")))
        assertEquals(2, shown.size)
        assertNull(shown[1].callId)
        assertSame(tool, shown[1].nodes.single())
        assertEquals(listOf(0, 2), shown[0].sourceIndices)
    }

    @Test fun disabledProjectionKeepsLegacyListAndBranchSelectionDeterminesOwnership() {
        val nodes = listOf(turn("a"), turn("b"))
        assertEquals(2, orbisCallTimeline(nodes, false).size)
        assertEquals(1, orbisTimelineIndex(nodes, 1, false))
        val branch = nodes[0].copy(messages = nodes[0].messages + UIMessage.assistant("unrelated"), selectIndex = 1)
        assertNull(orbisCallTimeline(listOf(branch)).single().callId)
    }

    @Test fun shareSelectionExposesEverySourceNodeAndLeavingItRestoresOnlyDisplayFolding() {
        val nodes = listOf(turn("first"), turn("second"), UIMessage.user("after").toMessageNode())
        val snapshot = nodes.toList()
        assertEquals(2, orbisChatListTimeline(nodes, enabled = true, selecting = false).size)
        val selecting = orbisChatListTimeline(nodes, enabled = true, selecting = true)
        assertEquals(nodes.size, selecting.size)
        selecting.forEachIndexed { index, entry ->
            assertNull(entry.callId)
            assertEquals(index, entry.firstSourceIndex)
            assertSame(nodes[index], entry.nodes.single())
        }
        assertEquals(2, orbisChatListTimeline(nodes, enabled = true, selecting = false).size)
        assertEquals(snapshot, nodes)
    }

    @Test fun eachCallHasOneCardEvenAcrossSeveralCallsAndUnrelatedMessages() {
        val nodes = listOf(turn("A1", "A"), UIMessage.user("ordinary1").toMessageNode(),
            turn("B1", "B"), turn("A2", "A"), UIMessage.user("ordinary2").toMessageNode(),
            turn("B2", "B"), turn("A3", "A"))
        val snapshot = nodes.toList()
        val shown = orbisCallTimeline(nodes)
        assertEquals(listOf("A", null, "B", null), shown.map { it.callId })
        assertEquals(listOf(0, 3, 6), shown[0].sourceIndices)
        assertEquals(listOf(2, 5), shown[2].sourceIndices)
        assertEquals(listOf(nodes[1], nodes[4]), shown.filter { it.callId == null }.flatMap { it.nodes })
        assertEquals(listOf(0, 1, 2, 0, 3, 2, 0), nodes.indices.map { orbisTimelineIndex(nodes, it) })
        assertEquals(0, orbisTimelineIndex(nodes, 100))
        assertEquals(snapshot, nodes)
        shown.forEach { entry -> entry.nodes.zip(entry.sourceIndices).forEach { (node, index) ->
            assertSame(nodes[index], node)
        } }
    }

    @Test fun finishedToolsStayInSingleCallButPendingQuestionKeepsItsActionableRow() {
        val completed = UIMessage.assistant("").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Tool("done", "workspace_write_file", "{}",
                output = listOf(UIMessagePart.Text("successful receipt"))))).toMessageNode()
        val question = UIMessage.assistant("").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Tool("question", "ask_user", "{}",
                approvalState = ToolApprovalState.Pending))).toMessageNode()
        val nodes = listOf(turn("before"), completed, question, turn("after"))
        val shown = orbisCallTimeline(nodes)
        assertEquals(2, shown.size)
        assertEquals(listOf(0, 1, 3), shown[0].sourceIndices)
        assertSame(question, shown[1].nodes.single())
        assertNull(shown[1].callId)
        assertEquals(listOf(2), shown[1].sourceIndices)
    }

    @Test fun pendingFirstNodeRemainsVisibleAndDoesNotReserveADuplicateCallCard() {
        val pending = UIMessage.assistant("").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Tool("pending", "toy", "{}", approvalState = ToolApprovalState.Pending)))
            .toMessageNode()
        val nodes = listOf(pending, turn("second"), turn("third"))
        val shown = orbisCallTimeline(nodes)
        assertEquals(2, shown.size)
        assertNull(shown.first().callId)
        assertEquals("call-1", shown.last().callId)
        assertEquals(listOf(1, 2), shown.last().sourceIndices)
        assertEquals(listOf(0, 1, 1), nodes.indices.map { orbisTimelineIndex(nodes, it) })
    }

    @Test fun disabledProjectionPreservesEveryOriginalPositionAcrossInterleavedCalls() {
        val nodes = listOf(turn("a"), UIMessage.user("ordinary").toMessageNode(), turn("b"))
        val shown = orbisCallTimeline(nodes, enabled = false)
        assertEquals(nodes, shown.map { it.nodes.single() })
        assertEquals(nodes.indices.toList(), shown.map { it.firstSourceIndex })
        assertEquals(nodes.indices.toList(), shown.map { it.sourceIndices.single() })
        assertEquals(listOf(0, 1, 2), nodes.indices.map { orbisTimelineIndex(nodes, it, enabled = false) })
        assertEquals(0, orbisTimelineIndex(emptyList(), 20))
    }
}
