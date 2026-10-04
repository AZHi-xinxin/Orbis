package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyResumePolicyTest {
    private val ready = VoiceReplyResumeSnapshot(true, true, false, 0, 0, false, false,
        false, false, false, false)

    @Test fun `only fully idle verified call can resume new audio`() {
        assertTrue(ready.ready)
        listOf(
            ready.copy(activeCallMatches = false), ready.copy(sessionReady = false),
            ready.copy(queuePaused = true), ready.copy(queuedInputs = 1),
            ready.copy(automaticInputs = 1), ready.copy(generating = true),
            ready.copy(submitting = true), ready.copy(recoveryBlocked = true),
            ready.copy(manualWrite = true), ready.copy(pendingTool = true),
            ready.copy(checkpointExists = true),
        ).forEach { assertFalse(it.ready) }
    }

    @Test fun `both local and server tool work must finish before recording resumes`() {
        fun messages(part: UIMessagePart) = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)))
        assertTrue(messages(UIMessagePart.Tool("local", "test", "{}")).hasUnfinishedVoiceReplyTools())
        assertFalse(messages(UIMessagePart.Tool("local", "test", "{}",
            output = listOf(UIMessagePart.Text("synthetic result")))).hasUnfinishedVoiceReplyTools())
        assertTrue(messages(UIMessagePart.ServerTool("remote", "test", status = ServerToolStatus.IN_PROGRESS))
            .hasUnfinishedVoiceReplyTools())
        assertFalse(messages(UIMessagePart.ServerTool("remote", "test", status = ServerToolStatus.COMPLETED))
            .hasUnfinishedVoiceReplyTools())
        assertFalse(messages(UIMessagePart.ServerTool("remote", "test", status = ServerToolStatus.FAILED))
            .hasUnfinishedVoiceReplyTools())
    }
}
