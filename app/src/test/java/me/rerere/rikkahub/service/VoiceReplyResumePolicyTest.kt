package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test fun `runtime continue path only reads queue and never resumes removes or dispatches input`() = runTest {
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(listOf(UIMessagePart.Text("retained human input")))
        val before = queue.state.value
        var checks = 0
        assertFalse(canResumeOwnedVoiceReplies({ true }) {
            checks++
            ready.copy(queuePaused = queue.state.value.paused,
                queuedInputs = queue.state.value.messages.size).ready
        })
        assertEquals(before, queue.state.value)
        assertEquals(1, checks)
        assertFalse(canResumeOwnedVoiceReplies({ false }) { checks++; true })
        assertEquals(1, checks)
        assertEquals(before, queue.state.value)
    }

    @Test fun `runtime ready check cannot resume a replaced or ended call after suspension`() = runTest {
        var owned = true
        val gate = CompletableDeferred<Boolean>()
        val checking = async { canResumeOwnedVoiceReplies({ owned }) { gate.await() } }
        runCurrent()
        owned = false
        gate.complete(true)
        assertFalse(checking.await())
        assertTrue(canResumeOwnedVoiceReplies({ true }) { true })
    }

    @Test fun `runtime read cancellation never becomes permission to resume`() = runTest {
        try {
            canResumeOwnedVoiceReplies({ true }) { throw CancellationException("synthetic check") }
            fail("cancel expected")
        } catch (_: CancellationException) { }
    }
}
