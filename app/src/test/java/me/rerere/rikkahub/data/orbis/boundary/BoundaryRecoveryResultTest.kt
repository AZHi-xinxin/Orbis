package me.rerere.rikkahub.data.orbis.boundary

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class BoundaryRecoveryResultTest {
    private val request = UIMessage.user("【申请继续】\n继续刚才的聊天好吗")
    private fun reply(action: String, success: Boolean = true) = UIMessage.assistant("原对话中的答复").copy(parts = listOf(
        UIMessagePart.Text("原对话中的答复"), UIMessagePart.Tool("call", BOUNDARY_TOOL,
            """{"action":"$action","revision":12}""",
            listOf(UIMessagePart.Text("""{"ok":$success}""")))))

    @Test fun malformedReceiptCannotInventADecisionOrCrash() {
        val malformed = reply("pause").copy(parts = listOf(UIMessagePart.Tool("bad", BOUNDARY_TOOL,
            """{"action":[]}""", listOf(UIMessagePart.Text("""{"ok":[]}""")))))
        assertTrue(boundaryRecoveryResult(listOf(request, malformed), request.id, ConversationBoundary()).contains("尚未通过工具"))
    }
    @Test fun plainReplyIsNotAnImplicitRefusal() {
        val result = boundaryRecoveryResult(listOf(request, UIMessage.assistant("我在。")), request.id, ConversationBoundary())
        assertTrue(result.contains("尚未通过工具"))
        assertFalse(result.contains("明确继续暂停"))
    }
    @Test fun failedResumeIsNotARefusal() {
        assertTrue(boundaryRecoveryResult(listOf(request, reply("resume", false)), request.id, ConversationBoundary()).contains("尚未通过工具"))
    }
    @Test fun explicitDecisionsNeedTheirActualReceiptsAndCurrentState() {
        assertTrue(boundaryRecoveryResult(listOf(request, reply("pause")), request.id, ConversationBoundary()).contains("明确继续暂停"))
        assertTrue(boundaryRecoveryResult(listOf(request, reply("resume")), request.id, null).contains("主动恢复"))
        assertTrue(boundaryRecoveryResult(listOf(request, reply("resume")), request.id, ConversationBoundary()).contains("尚未通过工具"))
    }
    @Test fun oldPauseCallsNeverBecomeTheNewRequestsDecision() {
        assertTrue(boundaryRecoveryResult(listOf(reply("pause"), request, UIMessage.assistant("还在看。")), request.id, ConversationBoundary()).contains("尚未通过工具"))
    }
    @Test fun noReplyOrMissingRequestIsReportedWithoutInventingAChoice() {
        assertTrue(boundaryRecoveryResult(listOf(request), request.id, ConversationBoundary()).contains("未获得 AI 答复"))
        assertTrue(boundaryRecoveryResult(emptyList(), request.id, null).contains("记录暂不可用"))
    }
}
