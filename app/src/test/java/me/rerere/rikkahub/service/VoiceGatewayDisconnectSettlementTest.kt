package me.rerere.rikkahub.service

import kotlinx.coroutines.test.runTest
import me.rerere.ai.util.OrbisGatewayThreadState
import org.junit.Assert.*
import org.junit.Test

class VoiceGatewayDisconnectSettlementTest {
    @Test fun `first busy then idle is allowed only after a fresh positive read`() = runTest {
        var probes = 0
        assertTrue(mayAvoidNewVideoFrameGatewayHold(false, true, true, false, true, { true }, {
            if (++probes < 3) OrbisGatewayThreadState.OWNED_BUSY else OrbisGatewayThreadState.IDLE
        }, waitForCancelledProducer = true))
        assertEquals(3, probes)
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test fun `busy timeout never grants release`() = runTest {
        assertFalse(mayAvoidNewVideoFrameGatewayHold(false, true, true, false, true, { true }, {
            OrbisGatewayThreadState.BUSY
        }, waitForCancelledProducer = true))
        assertEquals(VOICE_INTERRUPTION_IDLE_PROBE_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test fun `changing owner or hold while waiting prevents subsequent probe`() = runTest {
        var safe = true
        var probes = 0
        assertFalse(mayAvoidNewVideoFrameGatewayHold(false, true, true, false, true, { safe }, {
            probes++
            safe = false
            OrbisGatewayThreadState.BUSY
        }, waitForCancelledProducer = true))
        assertEquals(1, probes)
    }

    @Test fun `unsupported or unconfirmed is not a busy retry`() = runTest {
        for (state in listOf(OrbisGatewayThreadState.UNSUPPORTED, OrbisGatewayThreadState.UNCONFIRMED)) {
            var probes = 0
            assertFalse(mayAvoidNewVideoFrameGatewayHold(false, true, true, false, true, { true }, {
                probes++; state
            }, waitForCancelledProducer = true))
            assertEquals(1, probes)
        }
    }
}
