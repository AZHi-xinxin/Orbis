package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.util.OrbisGatewayTerminalReason
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HumanGatewayInvocationTest {
    private data class Request(val id: String, val capable: Boolean = true)
    private fun tracker() = GatewayInvocationTracker<Request>({ it.id })
    private fun approval() = checkNotNull(GatewayToolBatchIdentity.create(
        "chat", "assistant", "model", "message", listOf(GatewayToolCallIdentity("call", "shell", "{}"))))

    @Test fun `502 after a delivered tool retires old request before failure reaches UI`() = runTest {
        val tracker = tracker()
        val order = mutableListOf<String>()
        val failure = IllegalStateException("synthetic HTTP 502")
        try {
            withHumanGatewayInvocation(tracker, "chat", null, settle = { requests, reason ->
                assertEquals(OrbisGatewayTerminalReason.FAILED, reason)
                val result = finishTerminatedGatewayRequests(requests, { it.capable }, { _, _ -> true },
                    { if (it.id == "tool-response") GatewayStopProbe.CAN_STOP else GatewayStopProbe.NOT_CURRENT },
                    { r, _ -> order += "retired:${r.id}"; true })
                assertEquals(1, result.retired)
                assertFalse(result.mustKeepPaused)
            }) { record ->
                record(Request("tool-response"))
                record(Request("failed-continuation", capable = false))
                order += "tool-unwound"
                throw failure
            }
            fail("must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
            order += "reported"
        }
        assertEquals(listOf("tool-unwound", "retired:tool-response", "reported"), order)
        assertEquals(0L, testScheduler.currentTime) // No five-minute timer or model/tool retry.
    }

    @Test fun `normal completion has no stop and approval only transfers its exact batch`() = runTest {
        val tracker = tracker()
        assertTrue(withHumanGatewayInvocation(tracker, "chat", null, { _, _ -> fail("no terminal") }) {
            it(Request("done")); HumanGatewayOutcome(true)
        })
        val batch = approval()
        assertFalse(withHumanGatewayInvocation(tracker, "chat", null, { _, _ -> fail("approval is suspended") }) {
            it(Request("waiting")); HumanGatewayOutcome(false, pendingApproval = batch)
        })
        var ended = emptyList<Request>()
        try {
            withHumanGatewayInvocation(tracker, "chat", batch, { r, _ -> ended = r }) {
                error("synthetic preparation failure before new request")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(Request("waiting")), ended)
    }

    @Test fun `step budget and unexplained incomplete return settle rather than wait for timeout`() = runTest {
        for (stopped in listOf(null, OrbisGatewayTerminalReason.BUDGET_EXHAUSTED)) {
            var seen: OrbisGatewayTerminalReason? = null
            assertFalse(withHumanGatewayInvocation(tracker(), "chat", null, { _, reason -> seen = reason }) {
                it(Request("owned")); HumanGatewayOutcome(false, stopped = stopped)
            })
            assertEquals(stopped ?: OrbisGatewayTerminalReason.FAILED, seen)
        }
    }

    @Test fun `cancelled old producer cleans only old requests after unwind and cannot erase successor`() = runTest {
        val tracker = tracker()
        val oldExited = CompletableDeferred<Unit>()
        val endNew = CompletableDeferred<Unit>()
        val retired = mutableListOf<String>()
        val old = backgroundScope.launch {
            withHumanGatewayInvocation(tracker, "chat", null, { requests, reason ->
                assertEquals(OrbisGatewayTerminalReason.CANCELLED, reason)
                retired += requests.map { it.id }
            }) { record ->
                record(Request("old"))
                try { CompletableDeferred<Unit>().await() }
                finally { withContext(NonCancellable) { oldExited.await() } }
                HumanGatewayOutcome(true)
            }
        }
        runCurrent(); old.cancel(); runCurrent()
        assertTrue(retired.isEmpty())
        backgroundScope.launch {
            withHumanGatewayInvocation(tracker, "chat", null, { r, _ -> retired += r.map { it.id } }) {
                it(Request("new")); endNew.await(); HumanGatewayOutcome(false)
            }
        }
        runCurrent(); oldExited.complete(Unit); runCurrent()
        assertEquals(listOf("old"), retired)
        endNew.complete(Unit); runCurrent()
        assertEquals(listOf("old", "new"), retired)
    }

    @Test fun `failed cleanup preserves original exception and does not settle twice`() = runTest {
        val original = CancellationException("synthetic cancellation")
        var count = 0
        try {
            withHumanGatewayInvocation(tracker(), "chat", null, { _, _ -> count++; error("cleanup failed") }) {
                throw original
            }
            fail("must cancel")
        } catch (caught: CancellationException) {
            assertSame(original, caught)
            assertEquals(1, caught.suppressed.size)
        }
        assertEquals(1, count)
    }
}
