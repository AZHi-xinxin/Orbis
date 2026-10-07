package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QueueRecoveryPolicyTest {
    private data class Request(val id: String, val scope: String = "own", val capable: Boolean = true)

    private suspend fun recover(
        requests: List<Request>,
        probe: suspend (Request) -> GatewayStopProbe,
        finish: suspend (Request, Request) -> Boolean = { _, _ -> error("unexpected finish") },
        timeoutMs: Long = 15_000,
        evidence: List<Request> = emptyList(),
        knownDirect: (Request) -> Boolean = { false },
    ) = recoverEndedGatewayRequests(requests, { it.capable }, { a, b -> a.scope == b.scope },
        probe, finish, timeoutMs, evidence, knownDirect)

    @Test fun `public state defaults idle and only running is busy`() {
        val result: QueueRecoveryResult = QueueRecoveryState()
        assertEquals(QueueRecoveryPhase.IDLE, result.phase)
        assertFalse(result.isRunning)
        for (phase in QueueRecoveryPhase.entries) {
            assertEquals(phase == QueueRecoveryPhase.RUNNING, result.copy(phase = phase).isRunning)
        }
    }

    @Test fun `local admission requires every ownership and completion condition`() {
        val ready = QueueRecoveryAdmission(sessionMatches = true, ownerMatches = true,
            unfinishedJobs = false, submitting = false, manualWrite = false,
            checkpointBlocked = false, pendingTools = false)
        assertTrue(ready.ready)
        val denied = listOf(
            ready.copy(sessionMatches = false),
            ready.copy(ownerMatches = false),
            ready.copy(unfinishedJobs = true),
            ready.copy(submitting = true),
            ready.copy(manualWrite = true),
            ready.copy(checkpointBlocked = true),
            ready.copy(pendingTools = true),
        )
        assertTrue(denied.all { !it.ready })
    }

    @Test fun `empty evidence is not confirmation that an old gateway wait ended`() = runTest {
        val result = recover(emptyList(), { error("no probe") })
        assertFalse(result.safe)
        assertEquals(GatewayRecoveryDisposition.PENDING, result.disposition)
        assertTrue(result.uncertain)
        assertEquals(0, result.checked)
    }

    @Test fun `ordinary provider receives no control traffic and is not falsely confirmed`() = runTest {
        val ordinary = Request("ordinary", capable = false)
        val result = recover(listOf(ordinary), { error("no probe") })
        assertFalse(result.safe)
        assertEquals(listOf(ordinary), result.unconfirmed)
        assertEquals(1, result.pending)
        assertEquals(0, result.checked)
    }

    @Test fun `positively known direct requests clear without control traffic`() = runTest {
        val requests = listOf(Request("direct-one", capable = false), Request("direct-two", capable = false))
        val result = recover(requests, { error("no direct probe") }, knownDirect = { true })
        assertTrue(result.safe)
        assertEquals(2, result.checked)
        assertEquals(0, result.retired)
        assertTrue(result.unconfirmed.isEmpty())
        assertTrue(result.capabilityEvidence.isEmpty())
    }

    @Test fun `one known direct request never excuses another unknown destination`() = runTest {
        val direct = Request("direct", capable = false)
        val unknown = Request("unknown", capable = false)
        val result = recover(listOf(direct, unknown), { error("no probe") }, knownDirect = { it === direct })
        assertFalse(result.safe)
        assertEquals(1, result.checked)
        assertEquals(listOf(unknown), result.unconfirmed)
        assertTrue(result.uncertain)
    }

    @Test fun `mixed positively direct and gateway requests each require their own proof`() = runTest {
        val direct = Request("direct", scope = "direct", capable = false)
        val gateway = Request("gateway")
        val seen = mutableListOf<Request>()
        val result = recover(listOf(direct, gateway), { seen += it; GatewayStopProbe.CAN_STOP },
            { request, proof -> assertEquals(gateway, request); assertEquals(gateway, proof); true },
            knownDirect = { it === direct })
        assertTrue(result.safe)
        assertEquals(listOf(gateway), seen)
        assertEquals(2, result.checked)
        assertEquals(1, result.retired)
    }

    @Test fun `advertised protocol overrides direct classification and generating still holds`() = runTest {
        val gateway = Request("advertised")
        var probes = 0
        val result = recover(listOf(gateway), { probes++; GatewayStopProbe.GENERATING },
            knownDirect = { true })
        assertFalse(result.safe)
        assertEquals(1, probes)
        assertEquals(listOf(gateway), result.unconfirmed)
    }

    @Test fun `direct classification failure retains that request and checks remaining requests`() = runTest {
        val broken = Request("broken", capable = false)
        val direct = Request("direct", capable = false)
        val result = recover(listOf(broken, direct), { error("no probe") },
            knownDirect = { if (it === broken) error("private route") else true })
        assertFalse(result.safe)
        assertEquals(GatewayRecoveryDisposition.FAILURE, result.disposition)
        assertEquals(listOf(broken), result.unconfirmed)
        assertEquals(1, result.checked)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `empty requests remain unconfirmed even with a direct classifier`() = runTest {
        val result = recover(emptyList(), { error("no probe") }, knownDirect = { true })
        assertFalse(result.safe)
        assertEquals(0, result.checked)
    }

    @Test fun `uncertain aggregate retains a retry set even after every individual confirmation`() = runTest {
        val request = Request("direct", capable = false)
        var reads = 0
        val first = recoverEndedGatewayRequests(listOf(request),
            advertised = { if (reads++ == 0) error("uncertain evidence read") else false },
            sameScope = { _, _ -> false }, probe = { error("no direct probe") },
            finish = { _, _ -> error("no direct finish") }, knownDirect = { true })
        assertFalse(first.safe)
        assertEquals(GatewayRecoveryDisposition.FAILURE, first.disposition)
        assertEquals(listOf(request), first.unconfirmed)
        val second = recover(first.unconfirmed, { error("no direct probe") }, knownDirect = { true })
        assertTrue(second.safe)
    }

    @Test fun `capability never crosses a captured scope`() = runTest {
        val foreign = Request("foreign", scope = "other", capable = false)
        val own = Request("own")
        val seen = mutableListOf<String>()
        val result = recover(listOf(foreign, own), { seen += it.id; GatewayStopProbe.NOT_CURRENT })
        assertEquals(listOf("own"), seen)
        assertEquals(listOf(foreign), result.unconfirmed)
        assertFalse(result.safe)
        assertTrue(result.uncertain)
    }

    @Test fun `all exact not current requests safely clear without a finish`() = runTest {
        val result = recover(listOf(Request("one"), Request("two")), { GatewayStopProbe.NOT_CURRENT })
        assertTrue(result.safe)
        assertEquals(GatewayRecoveryDisposition.SAFE, result.disposition)
        assertEquals(2, result.checked)
        assertEquals(0, result.retired)
        assertTrue(result.unconfirmed.isEmpty())
    }

    @Test fun `not current continuation never hides an older delivered wait`() = runTest {
        val old = Request("old")
        val continuation = Request("rejected", capable = false)
        val finished = mutableListOf<Request>()
        val result = recover(listOf(continuation, old),
            { if (it === old) GatewayStopProbe.CAN_STOP else GatewayStopProbe.NOT_CURRENT },
            { request, proof -> assertEquals(old, proof); finished += request; true })
        assertTrue(result.safe)
        assertEquals(listOf(old), finished)
        assertEquals(1, result.retired)
    }

    @Test fun `generating cleanup and unsupported never authorize a finish`() = runTest {
        for (state in listOf(GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING, GatewayStopProbe.UNSUPPORTED)) {
            val request = Request("own")
            val result = recover(listOf(request), { state })
            assertFalse(result.safe)
            assertEquals(listOf(request), result.unconfirmed)
            assertEquals(1, result.pending)
            assertEquals(0, result.retired)
        }
    }

    @Test fun `cleanup response from exact finish remains unresolved without retry`() = runTest {
        var finishes = 0
        val request = Request("own")
        val result = recover(listOf(request), { GatewayStopProbe.CAN_STOP }, { _, _ -> finishes++; false })
        assertFalse(result.safe)
        assertEquals(GatewayRecoveryDisposition.PENDING, result.disposition)
        assertEquals(listOf(request), result.unconfirmed)
        assertEquals(1, finishes)
    }

    @Test fun `failed status does not hide another exact wait and preserves failed subset`() = runTest {
        val broken = Request("broken")
        val old = Request("old")
        val finished = mutableListOf<Request>()
        val result = recover(listOf(broken, old),
            { if (it === broken) error("private transport details") else GatewayStopProbe.CAN_STOP },
            { request, _ -> finished += request; true })
        assertEquals(GatewayRecoveryDisposition.FAILURE, result.disposition)
        assertEquals(listOf(broken), result.unconfirmed)
        assertEquals(listOf(old), finished)
        assertEquals(2, result.checked)
        assertEquals(1, result.retired)
        assertTrue(result.uncertain)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `lost finish receipt is not retried but later requests are checked`() = runTest {
        val lost = Request("lost")
        val later = Request("later")
        val seen = mutableListOf<Request>()
        var finishes = 0
        val result = recover(listOf(lost, later),
            { seen += it; if (it === lost) GatewayStopProbe.CAN_STOP else GatewayStopProbe.NOT_CURRENT },
            { _, _ -> finishes++; error("409 or lost receipt") })
        assertEquals(listOf(lost, later), seen)
        assertEquals(listOf(lost), result.unconfirmed)
        assertEquals(1, finishes)
        assertEquals(0, result.retired)
        assertFalse(result.safe)
    }

    @Test fun `deadline retains partial confirmation and all unchecked requests`() = runTest {
        val first = Request("first")
        val slow = Request("slow")
        val last = Request("last")
        val result = recover(listOf(first, slow, last),
            { if (it === slow) delay(60_000); GatewayStopProbe.CAN_STOP }, { _, _ -> true }, timeoutMs = 100)
        assertEquals(listOf(slow, last), result.unconfirmed)
        assertEquals(2, result.checked)
        assertEquals(1, result.retired)
        assertEquals(2, result.pending)
        assertTrue(result.uncertain)
        assertFalse(result.safe)
    }

    @Test fun `timeout during finish keeps its nonce for a fresh status next time`() = runTest {
        val request = Request("own")
        var finishes = 0
        val first = recover(listOf(request), { GatewayStopProbe.CAN_STOP },
            { _, _ -> finishes++; delay(60_000); true }, timeoutMs = 100)
        assertEquals(listOf(request), first.unconfirmed)
        assertEquals(0, first.retired)
        val second = recover(first.unconfirmed, { GatewayStopProbe.NOT_CURRENT }, evidence = first.capabilityEvidence)
        assertTrue(second.safe)
        assertEquals(1, finishes)
    }

    @Test fun `resolved old request remains capability evidence for lost continuation retry`() = runTest {
        val old = Request("old")
        val lost = Request("lost-headers", capable = false)
        val first = recover(listOf(lost, old),
            { if (it === lost) GatewayStopProbe.GENERATING else GatewayStopProbe.NOT_CURRENT })
        assertEquals(listOf(lost), first.unconfirmed)
        assertEquals(listOf(old), first.capabilityEvidence)
        val second = recover(first.unconfirmed, { GatewayStopProbe.CAN_STOP },
            { request, proof -> assertEquals(lost, request); assertEquals(old, proof); true },
            evidence = first.capabilityEvidence)
        assertTrue(second.safe)
        assertEquals(1, second.retired)
    }

    @Test fun `retry evidence is revalidated and cannot cross scope`() = runTest {
        val own = Request("own", capable = false)
        for (proof in listOf(Request("foreign", "other"), Request("not-accepted", capable = false))) {
            val result = recover(listOf(own), { error("no probe") }, evidence = listOf(proof))
            assertFalse(result.safe)
            assertEquals(0, result.checked)
            assertEquals(listOf(own), result.unconfirmed)
        }
    }

    @Test fun `request bound preserves overflow rather than clearing a prefix`() = runTest {
        val requests = (0..16).map { Request("request-$it") }
        val result = recover(requests, { GatewayStopProbe.NOT_CURRENT })
        assertEquals(16, result.checked)
        assertEquals(listOf(requests.last()), result.unconfirmed)
        assertFalse(result.safe)
        assertTrue(result.uncertain)
    }

    @Test fun `genuine cancellation propagates instead of becoming a recoverable failure`() = runTest {
        var finishes = 0
        try {
            recover(listOf(Request("one"), Request("two")), { throw CancellationException("outer") },
                { _, _ -> finishes++; true })
            fail("must propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, finishes)
        }
    }

    @Test fun `outer deadline escapes rather than returning inner pending summary`() = runTest {
        var returned = false
        var finishes = 0
        try {
            withTimeout(100) {
                recover(listOf(Request("one")), { delay(60_000); GatewayStopProbe.CAN_STOP },
                    { _, _ -> finishes++; true }, timeoutMs = 15_000)
                returned = true
            }
            fail("outer deadline must propagate")
        } catch (_: TimeoutCancellationException) {
            assertFalse(returned)
            assertEquals(0, finishes)
        }
    }

    @Test fun `a suspension cannot replace the captured request list with newer ownership`() = runTest {
        val first = Request("first")
        val second = Request("second")
        val requests = mutableListOf(first, second)
        val seen = mutableListOf<Request>()
        val result = recover(requests, {
            seen += it
            requests.clear()
            requests += Request("new-owner")
            delay(1)
            GatewayStopProbe.NOT_CURRENT
        })
        assertTrue(result.safe)
        assertEquals(listOf(first, second), seen)
        assertEquals(listOf(first, second), result.capabilityEvidence)
    }

    @Test fun `diagnostics never stringify captured handles or capability evidence`() = runTest {
        val request = Request("private-nonce", scope = "private-route")
        val result = recover(listOf(request), { GatewayStopProbe.GENERATING })
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("Request("))
    }
}
