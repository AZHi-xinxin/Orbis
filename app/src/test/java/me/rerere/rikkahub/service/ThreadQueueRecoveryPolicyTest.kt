package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ThreadQueueRecoveryPolicyTest {
    @Test fun `trusted idle needs no old nonce no stop and is idempotent`() = runTest {
        var probes = 0
        var stops = 0
        repeat(2) {
            assertEquals(ThreadRecoveryState.IDLE, recoverCurrentGatewayThread<Any>(
                { probes++; ThreadRecoveryProbe(ThreadRecoveryState.IDLE) },
                { error("idle has no exact request") }, { stops++; true }, { true }))
        }
        assertEquals(2, probes)
        assertEquals(0, stops)
    }

    @Test fun `only server owned delivered wait is stopped then independently checked idle`() = runTest {
        val exact = Any()
        var probes = 0
        var stops = 0
        val result = recoverCurrentGatewayThread(
            { if (probes++ == 0) ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, exact)
                else ThreadRecoveryProbe(ThreadRecoveryState.IDLE) },
            { assertSame(exact, it); GatewayStopProbe.CAN_STOP },
            { assertSame(exact, it); stops++; true }, { true })
        assertEquals(ThreadRecoveryState.IDLE, result)
        assertEquals(2, probes)
        assertEquals(1, stops)
    }

    @Test fun `not current is insufficient when lane remains foreign busy`() = runTest {
        var probes = 0
        val result = recoverCurrentGatewayThread(
            { if (probes++ == 0) ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, Any())
                else ThreadRecoveryProbe(ThreadRecoveryState.BUSY) },
            { GatewayStopProbe.NOT_CURRENT }, { error("no stop permit") }, { true })
        assertEquals(ThreadRecoveryState.BUSY, result)
        assertEquals(2, probes)
    }

    @Test fun `foreign busy unsupported and unconfirmed never stop or manufacture idle`() = runTest {
        for (state in listOf(ThreadRecoveryState.BUSY, ThreadRecoveryState.UNSUPPORTED, ThreadRecoveryState.UNCONFIRMED)) {
            assertEquals(state, recoverCurrentGatewayThread<Any>({ ThreadRecoveryProbe(state) },
                { error("no exact owner") }, { error("must not stop") }, { true }))
        }
    }

    @Test fun `owned response without real original nonce stays unconfirmed`() = runTest {
        assertEquals(ThreadRecoveryState.UNCONFIRMED, recoverCurrentGatewayThread<Any>(
            { ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY) }, { error("no request") },
            { error("no stop") }, { true }))
    }

    @Test fun `generating cleanup unsupported and refused stop stay blocked`() = runTest {
        for (exactState in listOf(GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING,
            GatewayStopProbe.UNSUPPORTED, GatewayStopProbe.CAN_STOP)) {
            var stops = 0
            val result = recoverCurrentGatewayThread({ ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, Any()) },
                { exactState }, { stops++; false }, { true })
            assertNotEquals(ThreadRecoveryState.IDLE, result)
            assertEquals(if (exactState == GatewayStopProbe.CAN_STOP) 1 else 0, stops)
        }
    }

    @Test fun `settings session or journal change during probe prevents exact stop`() = runTest {
        var owner = true
        assertEquals(ThreadRecoveryState.OWNER_CHANGED, recoverCurrentGatewayThread(
            { owner = false; ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, Any()) },
            { error("ownership changed") }, { error("must not stop") }, { owner }))
    }

    @Test fun `scope change during stop cannot release queue`() = runTest {
        var owner = true
        var probes = 0
        assertEquals(ThreadRecoveryState.OWNER_CHANGED, recoverCurrentGatewayThread(
            { probes++; ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, Any()) },
            { GatewayStopProbe.CAN_STOP }, { owner = false; true }, { owner }))
        assertEquals(1, probes)
    }

    @Test fun `retired exact wait but unconfirmed new lane remains blocked`() = runTest {
        var probes = 0
        assertEquals(ThreadRecoveryState.UNCONFIRMED, recoverCurrentGatewayThread(
            { if (probes++ == 0) ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, Any())
                else ThreadRecoveryProbe(ThreadRecoveryState.UNCONFIRMED) },
            { GatewayStopProbe.CAN_STOP }, { true }, { true }))
    }

    @Test fun `deadline network errors and outer cancellation never synthesize idle`() = runTest {
        assertEquals(ThreadRecoveryState.UNCONFIRMED, recoverCurrentGatewayThread<Any>(
            { delay(100); ThreadRecoveryProbe(ThreadRecoveryState.IDLE) }, { error("unused") },
            { error("unused") }, { true }, timeoutMs = 1))
        assertEquals(ThreadRecoveryState.UNCONFIRMED, recoverCurrentGatewayThread<Any>(
            { error("network") }, { error("unused") }, { error("unused") }, { true }))
        try {
            recoverCurrentGatewayThread<Any>({ throw CancellationException("caller cancelled") },
                { error("unused") }, { error("unused") }, { true })
            fail("outer cancellation must escape")
        } catch (_: CancellationException) { }
    }
}
