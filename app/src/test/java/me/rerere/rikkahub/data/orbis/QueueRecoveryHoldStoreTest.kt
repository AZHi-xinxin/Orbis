package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class QueueRecoveryHoldStoreTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test fun `transport recovery cannot acknowledge unknown external result`() {
        var disk: String? = null
        val store = OrbisQueuePauseStore({ disk }, { disk = it })
        store.pause(id, "unknown_tool_result")
        val original = disk
        assertFalse(store.resumeIfReason(id, "gateway_terminal_unconfirmed"))
        assertEquals(original, disk)
        assertEquals(QueuePauseStatus.PAUSED, store.status(id))
    }

    @Test fun `transport hold clears only after verified matching write`() {
        var disk: String? = null
        val store = OrbisQueuePauseStore({ disk }, { disk = it })
        store.pause(id, "gateway_terminal_unconfirmed")
        assertTrue(store.resumeIfReason(id, "gateway_terminal_unconfirmed"))
        assertEquals(QueuePauseStatus.UNPAUSED, OrbisQueuePauseStore({ disk }, { disk = it }).status(id))
    }

    @Test fun `silent write failure never authorizes selective recovery`() {
        var disk: String? = null
        var dropWrites = false
        val store = OrbisQueuePauseStore({ disk }, { if (!dropWrites) disk = it })
        store.pause(id, "gateway_terminal_unconfirmed")
        dropWrites = true
        assertTrue(runCatching { store.resumeIfReason(id, "gateway_terminal_unconfirmed") }.isFailure)
        assertEquals(QueuePauseStatus.PAUSED, store.status(id))
        assertFalse(store.resumeIfReason(id, "gateway_terminal_unconfirmed"))
    }

    @Test fun `fresh store selective recovery does not manufacture acknowledgement`() {
        var writes = 0
        val store = OrbisQueuePauseStore({ null }, { writes++ })
        assertFalse(store.resumeIfReason(id, "gateway_terminal_unconfirmed"))
        assertEquals(0, writes)
    }
}
