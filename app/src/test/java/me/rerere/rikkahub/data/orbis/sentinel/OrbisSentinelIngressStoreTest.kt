package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.*
import org.junit.Test

class OrbisSentinelIngressStoreTest {
    @Test fun duplicateIncludingCrashLeftProcessingNeverAuthorizesDispatch() {
        var disk: String? = null
        fun store() = OrbisSentinelIngressStore({ disk }, { disk = it })
        assertFalse(store().begin("touch", "event-1", "1000", 1000, 2000).second)
        val retry = store().begin("touch", "event-1", "1000", 1000, 3000)
        assertTrue(retry.second)
        assertEquals("processing", retry.first.status)
    }
    @Test fun disabledAndNoRuleReceiptsStayConsumedAfterRestart() {
        for (status in listOf("suppressed", "no_rules")) {
            var disk: String? = null
            val store = OrbisSentinelIngressStore({ disk }, { disk = it })
            store.begin("touch", "event-1", "1000", 1000, 2000)
            store.finish("touch", "event-1", status)
            val next = OrbisSentinelIngressStore({ disk }, { disk = it }).begin("touch", "event-1", "1000", 1000, 5000)
            assertTrue(next.second)
            assertEquals(status, next.first.status)
        }
    }
    @Test fun mismatchedIdentityRejected() {
        var disk: String? = null
        val store = OrbisSentinelIngressStore({ disk }, { disk = it })
        store.begin("lc_location", "event-1", "first", 1000, 2000)
        assertThrows(IllegalArgumentException::class.java) { store.begin("lc_location", "event-1", "changed", 1000, 2000) }
    }
    @Test fun writerFailureAfterCommitIsStillDeduplicated() {
        var disk: String? = null
        val store = OrbisSentinelIngressStore({ disk }, { disk = it; error("after_write") })
        assertThrows(IllegalStateException::class.java) { store.begin("touch", "event-1", "1000", 1000, 2000) }
        assertTrue(OrbisSentinelIngressStore({ disk }, { disk = it }).begin("touch", "event-1", "1000", 1000, 3000).second)
    }
    @Test fun terminalReceiptNotRewrittenByLateResult() {
        var disk: String? = null
        val store = OrbisSentinelIngressStore({ disk }, { disk = it })
        store.begin("touch", "event-1", "1000", 1000, 2000)
        store.finish("touch", "event-1", "suppressed")
        assertEquals("suppressed", store.finish("touch", "event-1", "accepted", 1).status)
    }
    @Test fun uncertainOutcomeCanOnlyBeResolvedByExplicitDownstreamReconciliation() {
        var disk: String? = null
        val store = OrbisSentinelIngressStore({ disk }, { disk = it })
        store.begin("touch", "event-1", "1000", 1000, 2000)
        store.finish("touch", "event-1", "unknown")
        assertEquals("unknown", store.finish("touch", "event-1", "accepted", 1).status)
        assertEquals("accepted", store.reconcile("touch", "event-1", 1).status)
        assertTrue(store.begin("touch", "event-1", "1000", 1000, 5000).second)
        assertEquals("accepted", store.reconcile("touch", "event-1", 0).status)
    }
}
