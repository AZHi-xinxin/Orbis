package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayStopPolicyTest {
    @Test fun `a rejected newer request must not hide the older waiting turn`() = runTest {
        val stopped = mutableListOf<String>()
        val result = stopRememberedGatewayRequests(listOf("rejected-new", "old-wait"),
            { if (it == "old-wait") GatewayStopProbe.CAN_STOP else GatewayStopProbe.NOT_CURRENT },
            { stopped += it; true })
        assertEquals(listOf("old-wait"), stopped)
        assertEquals(1, result.retired)
        assertEquals(2, result.checked)
    }
    @Test fun `unsupported generating and pending cannot receive blind stop`() = runTest {
        val result = stopRememberedGatewayRequests(GatewayStopProbe.entries.filter { it != GatewayStopProbe.CAN_STOP },
            { it }, { error("no stop permitted") })
        assertEquals(0, result.retired)
        assertEquals(2, result.pending)
        assertEquals(1, result.unsupported)
        assertTrue(result.notice.contains("仍在执行或收尾"))
    }
    @Test fun `cleanup receipt never counts as closed`() = runTest {
        val result = stopRememberedGatewayRequests(listOf("wait"), { GatewayStopProbe.CAN_STOP }, { false })
        assertEquals(1, result.pending)
        assertEquals(0, result.retired)
    }
    @Test fun `network failure stops without retry or releasing hold`() = runTest {
        var calls = 0
        val result = boundedGatewayStopCheck {
            stopRememberedGatewayRequests(listOf("first", "second"), { calls++; error("private details") }, { true })
        }
        assertEquals(1, calls)
        assertTrue(result.uncertain)
        assertFalse(result.notice.contains("private"))
    }
    @Test fun `whole explicit check has bounded wait and no automatic retries`() = runTest {
        val result = boundedGatewayStopCheck { delay(60_000); GatewayStopSummary(retired = 1) }
        assertTrue(result.uncertain)
        assertEquals(0, result.retired)
    }
    @Test fun `cancellation is propagated and no remote request restarts`() = runTest {
        try {
            boundedGatewayStopCheck { throw CancellationException("cancelled") }
            fail("must cancel")
        } catch (_: CancellationException) { }
    }
    @Test fun `no remembered request is not a successful unlock`() = runTest {
        val result = stopRememberedGatewayRequests(emptyList<String>(), { error("unused") }, { error("unused") })
        assertEquals(0, result.retired)
        assertTrue(result.notice.contains("没有本次运行"))
    }
    @Test fun `limit checks to recent bounded handles`() = runTest {
        val result = stopRememberedGatewayRequests((1..100).toList(), { GatewayStopProbe.NOT_CURRENT }, { error("unused") })
        assertEquals(16, result.checked)
    }
    @Test fun `partial retired result does not describe every connection as idle`() {
        val result = GatewayStopSummary(retired = 1, unsupported = 1, checked = 2)
        assertTrue(result.notice.contains("另有旧连接不支持"))
        assertTrue(result.notice.contains("不能据此认定全部连接空闲"))
    }
}
