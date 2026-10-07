package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.uuid.Uuid

class VideoFrameFailurePolicyTest {
    private val marker = UIMessage.user("synthetic frame").copy(isSynthetic = true, orbisVoiceCallKind = "visual")
    private val before = listOf(marker)
    private val after = before + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("partial reply")))

    @Test fun failedOrWithdrawnCameraTickDoesNotPauseIndependentSpeech() {
        assertTrue(canContinueAfterVideoFrameFailure(SocketTimeoutException(), before, after, false))
        assertTrue(canContinueAfterVideoFrameFailure(CancellationException("background"), before, after, false))
        assertFalse(canContinueAfterVideoFrameFailure(SocketTimeoutException(), before, after, true))
        assertFalse(canContinueAfterVideoFrameFailure(IllegalStateException("disk receipt unknown"), before, after, false))
    }

    @Test fun unknownOrChangedToolReceiptsStillHoldTheQueue() {
        val pending = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("t", "external-write", "{}", emptyList())))
        assertFalse(canContinueAfterVideoFrameFailure(SocketTimeoutException(), before, after + pending, false))
        val completed = pending.copy(parts = listOf(UIMessagePart.Tool("t", "write", "{}", listOf(UIMessagePart.Text("done")))))
        assertFalse(canContinueAfterVideoFrameFailure(CancellationException("background"), before, after + completed, false))
    }

    @Test fun unchangedInterruptedToolOutputDoesNotProveExecutionOnCancellation() {
        for (reason in listOf(HostToolFailure.INTERRUPTED, HostToolFailure.USER_CANCELLED)) {
            val tool = UIMessagePart.Tool("t", "external-write", "{}").withHostToolFailure(reason)
            assertTrue(tool.isExecuted) // Output exists, but executionPerformed remains unknown.
            val history = before + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            for (error in listOf(CancellationException("background"),
                VoiceBargeInCancellation(Uuid.random(), "synthetic-call"))) {
                assertFalse(canContinueAfterVideoFrameFailure(error, history, history, false))
            }
        }
    }

    @Test fun unchangedInterruptedToolOutputDoesNotProveExecutionOnIoFailure() {
        for (reason in listOf(HostToolFailure.INTERRUPTED, HostToolFailure.USER_CANCELLED)) {
            val tool = UIMessagePart.Tool("t", "external-write", "{}").withHostToolFailure(reason)
            assertTrue(tool.isExecuted)
            val history = before + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            assertFalse(canContinueAfterVideoFrameFailure(IOException("synthetic disconnect"), history, history, false))
        }
    }

    @Test fun unchangedCompletedOrKnownUnexecutedToolDoesNotCreateUnknownExecution() {
        val tools = listOf(
            UIMessagePart.Tool("done", "read", "{}", output = listOf(UIMessagePart.Text("done"))),
            UIMessagePart.Tool("missing", "unavailable", "{}").withHostToolFailure(HostToolFailure.NOT_PROVIDED),
        )
        for (tool in tools) {
            val history = before + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            assertTrue(canContinueAfterVideoFrameFailure(CancellationException("background"), history, history, false))
            assertTrue(canContinueAfterVideoFrameFailure(IOException("synthetic disconnect"), history, history, false))
        }
    }
}
