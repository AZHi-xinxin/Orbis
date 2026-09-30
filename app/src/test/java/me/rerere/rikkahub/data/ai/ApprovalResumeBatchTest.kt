package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ApprovalResumeBatchTest {
    private fun call(id: String, state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(id, "test", "{}", approvalState = state)

    @Test fun mixedAutoApprovedDeniedRetainsEveryUnexecutedPeerInOrder() {
        val tools = listOf(call("auto"), call("yes", ToolApprovalState.Approved), call("no", ToolApprovalState.Denied("no")))
        assertEquals(tools, resumedApprovalBatch(tools))
    }

    @Test fun anyStillPendingPeerPreventsResumingEvenApprovedOnes() {
        assertTrue(resumedApprovalBatch(listOf(call("yes", ToolApprovalState.Approved), call("wait", ToolApprovalState.Pending))).isEmpty())
    }

    @Test fun completedReceiptsNeverExecuteAgainAndAllAutoIsNotAResume() {
        val completed = call("done", ToolApprovalState.Approved).copy(output = listOf(UIMessagePart.Text("done")))
        val active = call("active", ToolApprovalState.Approved)
        assertEquals(listOf(active), resumedApprovalBatch(listOf(completed, active)))
        assertTrue(resumedApprovalBatch(listOf(call("auto"))).isEmpty())
        assertTrue(resumedApprovalBatch(listOf(completed)).isEmpty())
    }
}
