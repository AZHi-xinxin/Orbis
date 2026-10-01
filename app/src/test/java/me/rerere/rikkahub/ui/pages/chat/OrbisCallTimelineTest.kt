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
        assertEquals(4, shown.size)
        assertNull(shown[1].callId)
        assertEquals(unrelated, shown[1].nodes.single())
        assertEquals(nodes.map { it.id }, shown.flatMap { it.nodes }.map { it.id })
    }

    @Test fun pendingToolApprovalRemainsActionableNotInsideFoldedCall() {
        val tool = UIMessage.assistant("").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Tool(toolCallId = "tool-1", toolName = "toy", input = "{}",
                approvalState = ToolApprovalState.Pending))).toMessageNode()
        val shown = orbisCallTimeline(listOf(turn("a"), tool, turn("b")))
        assertEquals(3, shown.size)
        assertNull(shown[1].callId)
        assertSame(tool, shown[1].nodes.single())
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
}
