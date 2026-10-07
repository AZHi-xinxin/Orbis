package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.OrbisGatewayThreadState
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test

/** Real failure classification + finish selection + idle policy + queue; not an Android service test. */
class VideoFrameGatewayIdlePolicyTest {
    private val conversation = "11111111-1111-4111-8111-111111111111"
    private val frame = UIMessage.user("synthetic frame").copy(isSynthetic = true, orbisVoiceCallKind = "visual")
    private val before = listOf(frame)
    private val after = before + UIMessage.assistant("partial synthetic observation")
    private val rejectedFrame = HttpException("synthetic upstream failure", httpStatus = 502)

    private suspend fun idleProof(
        attempted: Boolean = false,
        requests: Boolean = true,
        peer: Boolean = true,
        predecessor: Boolean = false,
        scope: Boolean = true,
        safe: () -> Boolean = { true },
        probe: suspend () -> OrbisGatewayThreadState,
    ) = mayAvoidNewVideoFrameGatewayHold(attempted, requests, peer, predecessor, scope, safe, probe)

    @Test fun `safe first frame without response capability may prove idle without stopping or replaying`() = runTest {
        var bytes: String? = null
        val gatewayHold = OrbisQueuePauseStore({ bytes }, { bytes = it })
        val queue = MessageQueue()
        var requestProbes = 0
        var finishCalls = 0
        var threadProbes = 0
        val finish = finishTerminatedGatewayRequests(listOf("no-response-headers"),
            { false }, { _, _ -> true },
            { requestProbes++; error("No current invocation advertised automatic finish") },
            { _, _ -> finishCalls++; error("Read-only idle proof cannot finish a request") })
        assertFalse(finish.attempted)
        val safeFailure = canContinueAfterVideoFrameFailure(rejectedFrame, before, after, false)
        assertTrue(safeFailure)
        val idle = idleProof(attempted = finish.attempted,
            safe = { safeFailure && !queue.state.value.paused && gatewayHold.status(conversation) == QueuePauseStatus.UNPAUSED },
            probe = { threadProbes++; OrbisGatewayThreadState.IDLE })
        if (!idle) { gatewayHold.pause(conversation, "gateway_terminal_unconfirmed"); queue.pause() }
        assertTrue(idle)
        assertFalse(queue.state.value.paused)
        assertEquals(QueuePauseStatus.UNPAUSED, gatewayHold.status(conversation))
        assertNull(queue.takeNext()) // Failed frame is never re-enqueued or replayed.
        val freshSpeech = listOf(UIMessagePart.Text("fresh human speech"))
        queue.enqueue(freshSpeech)
        assertEquals(freshSpeech, queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
        assertEquals(1, threadProbes)
        assertEquals(0, requestProbes)
        assertEquals(0, finishCalls)
    }

    @Test fun `busy unsupported unconfirmed and failed probes do not establish idle`() = runTest {
        for (state in OrbisGatewayThreadState.entries.filter { it != OrbisGatewayThreadState.IDLE }) {
            var probes = 0
            assertFalse(state.name, idleProof(probe = { probes++; state }))
            assertEquals(1, probes)
        }
        assertFalse(idleProof(probe = { throw IllegalStateException("synthetic failure") }))
    }

    @Test fun `two second budget cancels slow read and never means idle`() = runTest {
        val startedAt = testScheduler.currentTime
        var cancelledRead = false
        assertFalse(idleProof(probe = {
            try { delay(60_000); OrbisGatewayThreadState.IDLE }
            finally { cancelledRead = true }
        }))
        assertTrue(cancelledRead)
        assertEquals(2_000L, testScheduler.currentTime - startedAt)
        assertEquals(2_000L, VIDEO_FRAME_IDLE_PROBE_TIMEOUT_MS)
    }

    @Test fun `changed owner during read invalidates even a positive idle reply`() = runTest {
        var currentOwner = true
        assertFalse(idleProof(safe = { currentOwner }, probe = {
            currentOwner = false
            OrbisGatewayThreadState.IDLE
        }))
    }

    @Test fun `old cancelled invocation cannot capture replacement job as its own failed frame owner`() = runTest {
        var sessionJob: Job? = null
        var failedOwner: Job? = null
        var avoidedHold = true
        var probes = 0
        val oldInvocation = launch(start = CoroutineStart.LAZY) {
            // Match production: capture the actual invocation BEFORE it suspends, not getJob
            // inside the later failure handler after regenerateAtMessage has replaced it.
            val capturedInvocation = kotlin.coroutines.coroutineContext[Job]
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    failedOwner = capturedInvocation
                    avoidedHold = idleProof(safe = {
                        capturedInvocation != null && sessionJob === capturedInvocation
                    }, probe = { probes++; OrbisGatewayThreadState.IDLE })
                }
            }
        }
        sessionJob = oldInvocation
        oldInvocation.start()
        runCurrent()
        val replacement = Job()
        sessionJob = replacement
        oldInvocation.cancelAndJoin()
        assertSame(oldInvocation, failedOwner)
        assertSame(replacement, sessionJob)
        assertFalse(avoidedHold)
        assertEquals(0, probes)
        replacement.cancel()
    }

    @Test fun `existing holds and unproven scope never send even a readonly probe`() = runTest {
        var probes = 0
        val probe: suspend () -> OrbisGatewayThreadState = { probes++; OrbisGatewayThreadState.IDLE }
        assertFalse(idleProof(attempted = true, probe = probe))
        assertFalse(idleProof(requests = false, probe = probe))
        assertFalse(idleProof(peer = false, probe = probe))
        assertFalse(idleProof(predecessor = true, probe = probe))
        assertFalse(idleProof(scope = false, probe = probe))
        var bytes: String? = null
        val hold = OrbisQueuePauseStore({ bytes }, { bytes = it })
        hold.pause(conversation, "gateway_terminal_unconfirmed")
        assertFalse(idleProof(safe = { hold.status(conversation) == QueuePauseStatus.UNPAUSED }, probe = probe))
        assertEquals(QueuePauseStatus.PAUSED, hold.status(conversation))
        assertFalse(idleProof(safe = { !MessageQueue(initiallyPaused = true).state.value.paused }, probe = probe))
        assertEquals(0, probes)
    }

    @Test fun `409 pending tools and unknown tool effects cannot acquire idle proof`() = runTest {
        var probes = 0
        val pending = UIMessagePart.Tool(toolCallId = "synthetic-tool", toolName = "synthetic-action", input = "{}")
        val cases = listOf(
            HttpException("synthetic conflict", httpStatus = 409) to after,
            rejectedFrame to (before + UIMessage.assistant("").copy(parts = listOf(pending))),
            rejectedFrame to (before + UIMessage.assistant("").copy(parts = listOf(pending.withHostToolFailure(HostToolFailure.INTERRUPTED)))),
        )
        for ((error, messages) in cases) {
            assertFalse(idleProof(safe = { canContinueAfterVideoFrameFailure(error, before, messages, false) },
                probe = { probes++; OrbisGatewayThreadState.IDLE }))
        }
        assertFalse(idleProof(safe = { canContinueAfterVideoFrameFailure(rejectedFrame, before, after, true) },
            probe = { probes++; OrbisGatewayThreadState.IDLE }))
        assertEquals(0, probes)
    }

    @Test fun `outer cancellation remains cancellation instead of a successful release`() = runTest {
        try {
            idleProof(probe = { throw CancellationException("synthetic cancellation") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
