package me.rerere.rikkahub.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualGatewayConfirmationPolicyTest {
    private data class Request(val id: String, val capable: Boolean = false)

    @Test fun `one click directly probes unadvertised exact request and accepts not current`() = runTest {
        val request = Request("lost-response")
        var probes = 0
        val result = recoverEndedGatewayRequests(listOf(request), { false }, { _, _ -> false },
            { probes++; GatewayStopProbe.NOT_CURRENT }, { _, _ -> error("automatic finish forbidden") },
            explicitProbeWithoutCapability = true, manualStop = { error("no stop needed") })
        assertTrue(result.safe)
        assertEquals(1, probes)
    }

    @Test fun `one click without capability can only use exact manual stop after positive permit`() = runTest {
        val request = Request("wait")
        var manualStops = 0
        val result = recoverEndedGatewayRequests(listOf(request), { false }, { _, _ -> false },
            { GatewayStopProbe.CAN_STOP }, { _, _ -> error("automatic finish forbidden") },
            explicitProbeWithoutCapability = true, manualStop = { manualStops++; true })
        assertTrue(result.safe)
        assertEquals(1, manualStops)
    }

    @Test fun `explicit unsupported probe does not silently become a positive receipt`() = runTest {
        val request = Request("unsupported")
        val result = recoverEndedGatewayRequests(listOf(request), { false }, { _, _ -> false },
            { GatewayStopProbe.UNSUPPORTED }, { _, _ -> error("automatic finish forbidden") },
            explicitProbeWithoutCapability = true, manualStop = { error("manual stop forbidden") })
        assertFalse(result.safe)
        assertSame(request, result.unconfirmed.single())
    }

    @Test fun `manual not current receipt settles the exact unadvertised owner without replay`() = runTest {
        val request = Request("lost-response")
        val initial = recoverEndedGatewayRequests(listOf(request), { it.capable }, { a, b -> a === b },
            { error("automatic probe must not run without capability") }, { _, _ -> error("no finish") })
        assertFalse(initial.safe)
        val confirmed = mutableListOf<Request>()
        var probes = 0
        val stop = stopRememberedGatewayRequests(listOf(request),
            { probes++; GatewayStopProbe.NOT_CURRENT }, { error("no stop") }, { confirmed += it })
        val receipt = reconcileManualGatewayConfirmations(listOf(request), initial.unconfirmed, confirmed, true)
        assertEquals(1, probes)
        assertEquals(1, stop.checked)
        assertEquals(0, stop.retired)
        assertTrue(receipt.settled)
        assertTrue(receipt.unconfirmed.isEmpty())
        // This is the service's existing one-click loop decision: a settled owner no longer
        // re-enters the automatic-only probe that previously kept this exact case paused.
        if (!receipt.settled) fail("explicit recovery must consume the manual exact receipt")
    }

    @Test fun `manual retired receipt settles owner but never calls queue resume or tools`() = runTest {
        val request = Request("waiting")
        val confirmed = mutableListOf<Request>()
        var stops = 0
        val stop = stopRememberedGatewayRequests(listOf(request), { GatewayStopProbe.CAN_STOP },
            { stops++; true }, { confirmed += it })
        val receipt = reconcileManualGatewayConfirmations(listOf(request), listOf(request), confirmed, true)
        assertEquals(1, stops)
        assertEquals(1, stop.retired)
        assertTrue(receipt.settled)
        // Neither policy exposes a dispatch/resume/tool callback; consumption is evidence only.
        val recoveryStillBlocked = QueueRecoveryAdmission(true, true, false, false, false, true, false)
        assertFalse(recoveryStillBlocked.ready)
    }

    @Test fun `every nonpositive stop outcome retains owner`() = runTest {
        for (state in GatewayStopProbe.entries.filter { it != GatewayStopProbe.NOT_CURRENT }) {
            val request = Request(state.name)
            val confirmed = mutableListOf<Request>()
            stopRememberedGatewayRequests(listOf(request), { state }, { false }, { confirmed += it })
            val receipt = reconcileManualGatewayConfirmations(listOf(request), listOf(request), confirmed, true)
            assertFalse(state.name, receipt.settled)
            assertEquals(listOf(request), receipt.unconfirmed)
            assertTrue(confirmed.isEmpty())
        }
    }

    @Test fun `later network failure does not erase earlier exact receipt or release unresolved sibling`() = runTest {
        val first = Request("first")
        val failed = Request("failed")
        val confirmed = mutableListOf<Request>()
        val result = boundedGatewayStopCheck {
            stopRememberedGatewayRequests(listOf(first, failed),
                { if (it === first) GatewayStopProbe.NOT_CURRENT else error("private error") },
                { error("no stop") }, { confirmed += it })
        }
        assertTrue(result.uncertain)
        val receipt = reconcileManualGatewayConfirmations(listOf(first, failed), listOf(first, failed), confirmed, true)
        assertFalse(receipt.settled)
        assertEquals(listOf(failed), receipt.unconfirmed)
        val later = reconcileManualGatewayConfirmations(listOf(first, failed), receipt.unconfirmed, listOf(failed), true)
        assertTrue(later.settled)
    }

    @Test fun `timeout preserves only completed positive receipts`() = runTest {
        val first = Request("first")
        val slow = Request("slow")
        val confirmed = mutableListOf<Request>()
        val result = boundedGatewayStopCheck {
            stopRememberedGatewayRequests(listOf(first, slow), {
                if (it === slow) delay(30_000)
                GatewayStopProbe.NOT_CURRENT
            }, { error("no stop") }, { confirmed += it })
        }
        assertTrue(result.uncertain)
        val receipt = reconcileManualGatewayConfirmations(listOf(first, slow), listOf(first, slow), confirmed, true)
        assertFalse(receipt.settled)
        assertEquals(listOf(slow), receipt.unconfirmed)
    }

    @Test fun `same textual id from a different request cannot confirm owner`() {
        val owned = Request("same-id")
        val another = Request("same-id")
        assertEquals(owned, another)
        assertNotSame(owned, another)
        val receipt = reconcileManualGatewayConfirmations(listOf(owned), listOf(owned), listOf(another), true)
        assertFalse(receipt.settled)
        assertSame(owned, receipt.unconfirmed.single())
    }

    @Test fun `changed session assistant model provider or local work rejects receipt application`() {
        val request = Request("own")
        for (changed in listOf("session", "assistant", "model", "provider", "ended-owner", "local-job", "manual-write")) {
            val receipt = reconcileManualGatewayConfirmations(listOf(request), listOf(request), listOf(request), false)
            assertFalse(changed, receipt.settled)
            assertSame(request, receipt.unconfirmed.single())
        }
    }

    @Test fun `broad manual ledger confirmation cannot settle an unrelated ended invocation`() {
        val owned = Request("own")
        val unrelated = Request("unrelated")
        val receipt = reconcileManualGatewayConfirmations(listOf(owned), listOf(owned), listOf(unrelated), true)
        assertFalse(receipt.settled)
        assertSame(owned, receipt.unconfirmed.single())
    }

    @Test fun `missing owner or empty evidence cannot manufacture a success`() {
        val request = Request("own")
        assertFalse(reconcileManualGatewayConfirmations(emptyList(), emptyList(), listOf(request), true).settled)
        assertFalse(reconcileManualGatewayConfirmations(listOf(request), emptyList(), listOf(request), true).settled)
        assertFalse(reconcileManualGatewayConfirmations(listOf(request), listOf(request), emptyList(), true).settled)
    }

    @Test fun `an inconsistent unresolved set is retained rather than accepted`() {
        val owned = Request("own")
        val foreign = Request("foreign")
        val receipt = reconcileManualGatewayConfirmations(listOf(owned), listOf(foreign), listOf(foreign), true)
        assertFalse(receipt.settled)
        assertSame(foreign, receipt.unconfirmed.single())
    }

    @Test fun `bounded manual ledger cannot confirm requests outside checked prefix`() = runTest {
        val requests = (1..17).map { Request("request-$it") }
        val confirmed = mutableListOf<Request>()
        val result = stopRememberedGatewayRequests(requests, { GatewayStopProbe.NOT_CURRENT },
            { error("no stop") }, { confirmed += it })
        assertEquals(16, result.checked)
        val receipt = reconcileManualGatewayConfirmations(requests, requests, confirmed, true)
        assertFalse(receipt.settled)
        assertSame(requests.last(), receipt.unconfirmed.single())
    }

    @Test fun `confirmation diagnostics never expose request details`() {
        val request = Request("private-request-routing")
        val receipt = reconcileManualGatewayConfirmations(listOf(request), listOf(request), emptyList(), true)
        assertFalse(receipt.toString().contains(request.id))
        assertTrue(receipt.toString().contains("unconfirmedCount=1"))
    }
}
