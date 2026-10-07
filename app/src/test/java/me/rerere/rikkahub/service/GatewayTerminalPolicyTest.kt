package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayTerminalPolicyTest {
    private data class Request(val id: String, val scope: String = "synthetic", val capable: Boolean = true)

    @Test fun `generic providers receive no automatic control requests`() = runTest {
        val result = finishTerminatedGatewayRequests(listOf(Request("one", capable = false)),
            { it.capable }, { a, b -> a.scope == b.scope }, { error("no probe") }, { _, _ -> error("no finish") })
        assertFalse(result.attempted)
    }

    @Test fun `rejected continuation cannot hide old delivered wait`() = runTest {
        val retired = mutableListOf<String>()
        val result = finishTerminatedGatewayRequests(listOf(Request("rejected", capable = false), Request("old")),
            { it.capable }, { a, b -> a.scope == b.scope },
            { if (it.id == "old") GatewayStopProbe.CAN_STOP else GatewayStopProbe.NOT_CURRENT },
            { request, proof -> assertTrue(proof.capable); retired += request.id; true })
        assertEquals(listOf("old"), retired)
        assertEquals(2, result.checked)
        assertEquals(1, result.retired)
        assertFalse(result.mustKeepPaused)
    }

    @Test fun `new accepted request without response headers still blocks release`() = runTest {
        val result = finishTerminatedGatewayRequests(listOf(Request("lost-response", capable = false), Request("old")),
            { it.capable }, { a, b -> a.scope == b.scope },
            { if (it.id == "lost-response") GatewayStopProbe.GENERATING else GatewayStopProbe.NOT_CURRENT },
            { _, _ -> error("still generating") })
        assertTrue(result.mustKeepPaused)
        assertEquals(1, result.pending)
    }

    @Test fun `capability is never borrowed across credentials endpoints models or owners`() = runTest {
        val seen = mutableListOf<String>()
        val result = finishTerminatedGatewayRequests(listOf(Request("foreign", "other", false), Request("own")),
            { it.capable }, { a, b -> a.scope == b.scope },
            { seen += it.id; GatewayStopProbe.NOT_CURRENT }, { _, _ -> error("not needed") })
        assertEquals(listOf("own"), seen)
        assertEquals(1, result.checked)
        assertTrue(result.mustKeepPaused)
    }

    @Test fun `unconfirmed close and disappearing protocol retain local hold`() = runTest {
        for (probe in listOf(GatewayStopProbe.CAN_STOP, GatewayStopProbe.CLEANUP_PENDING, GatewayStopProbe.UNSUPPORTED)) {
            val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
                { probe }, { _, _ -> false })
            assertTrue(result.mustKeepPaused)
            assertEquals(0, result.retired)
        }
    }

    @Test fun `lost finish receipt is not retried and does not claim closed`() = runTest {
        var finishes = 0
        val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
            { GatewayStopProbe.CAN_STOP }, { _, _ -> finishes++; error("sensitive body") })
        assertEquals(1, finishes)
        assertTrue(result.mustKeepPaused)
        assertFalse(result.notice.contains("sensitive"))
    }

    @Test fun `terminal housekeeping is bounded and keeps partial retired counts honest`() = runTest {
        val result = finishTerminatedGatewayRequests(listOf(Request("first"), Request("slow")),
            { it.capable }, { _, _ -> true }, { if (it.id == "slow") delay(60_000); GatewayStopProbe.CAN_STOP },
            { _, _ -> true })
        assertEquals(1, result.retired)
        assertTrue(result.uncertain)
        assertTrue(result.mustKeepPaused)
        assertTrue(result.notice.contains("尚未确认"))
    }

    @Test fun `outer cancellation propagates without replay`() = runTest {
        try {
            finishTerminatedGatewayRequests(listOf(Request("one")), { it.capable }, { _, _ -> true },
                { throw CancellationException() }, { _, _ -> error("no finish") })
            fail("must cancel")
        } catch (_: CancellationException) { }
    }

    @Test fun `cancelled voice waits for exact generating request to become not current`() = runTest {
        var probes = 0
        val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
            { probes++; if (probes < 3) GatewayStopProbe.GENERATING else GatewayStopProbe.NOT_CURRENT },
            { _, _ -> error("no finish or replay") }, stillSafeCancelledVoice = { true })
        assertEquals(3, probes)
        assertEquals(1, result.checked)
        assertFalse(result.mustKeepPaused)
        assertEquals(0, result.retired)
    }

    @Test fun `cancelled voice busy status cannot extend terminal budget`() = runTest {
        var probes = 0
        val started = testScheduler.currentTime
        val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
            { probes++; GatewayStopProbe.CLEANUP_PENDING }, { _, _ -> error("no finish") },
            stillSafeCancelledVoice = { true })
        assertTrue(probes > 1)
        assertEquals(4_000L, testScheduler.currentTime - started)
        assertTrue(result.mustKeepPaused)
        assertTrue(result.uncertain)
    }

    @Test fun `cancelled voice changed owner cannot consume a later idle or finish receipt`() = runTest {
        for (late in listOf(GatewayStopProbe.NOT_CURRENT, GatewayStopProbe.CAN_STOP)) {
            var safe = true
            var probes = 0
            val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
                { probes++; if (probes == 1) GatewayStopProbe.GENERATING else { safe = false; late } },
                { _, _ -> error("stale owner cannot finish") }, stillSafeCancelledVoice = { safe })
            assertEquals(2, probes)
            assertTrue(result.mustKeepPaused)
        }
    }

    @Test fun `unknown protocol is not retried even during a voice cancellation`() = runTest {
        var probes = 0
        val result = finishTerminatedGatewayRequests(listOf(Request("own")), { it.capable }, { _, _ -> true },
            { probes++; GatewayStopProbe.UNSUPPORTED }, { _, _ -> error("no finish") },
            stillSafeCancelledVoice = { true })
        assertEquals(1, probes)
        assertTrue(result.mustKeepPaused)
    }
}
