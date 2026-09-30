package me.rerere.rikkahub.data.orbis.sentinel

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unique synthetic conversations keep the process-local singleton isolated between tests. */
class OrbisSentinelPresenceTest {
    @Test fun `same conversation remains present until its last visible owner leaves`() {
        val id = UUID.randomUUID().toString()
        OrbisSentinelPresence.enter(id)
        OrbisSentinelPresence.enter(id)
        OrbisSentinelPresence.leave(id, 1000)
        assertNull(OrbisSentinelPresence.leftAt(id, 1500))
        OrbisSentinelPresence.leave(id, 2000)
        assertEquals(2000L, OrbisSentinelPresence.leftAt(id, 3000))
    }
    @Test fun `unseen conversation starts absence at first observation not imaginary process history`() {
        val id = UUID.randomUUID().toString()
        assertEquals(1000L, OrbisSentinelPresence.leftAt(id, 1000))
        assertEquals(1000L, OrbisSentinelPresence.leftAt(id, 9000))
    }

    @Test fun `resumed chat has no absence and actual leave freezes its new baseline`() {
        val id = UUID.randomUUID().toString()
        OrbisSentinelPresence.enter(id)
        assertNull(OrbisSentinelPresence.leftAt(id, 1000))
        OrbisSentinelPresence.leave(id, 2000)
        assertEquals(2000L, OrbisSentinelPresence.leftAt(id, 9000))
        OrbisSentinelPresence.leave(id, 8000)
        assertEquals(2000L, OrbisSentinelPresence.leftAt(id, 10000))
    }

    @Test fun `reenter clears old absence so a later departure starts over`() {
        val id = UUID.randomUUID().toString()
        OrbisSentinelPresence.enter(id)
        OrbisSentinelPresence.leave(id, 2000)
        OrbisSentinelPresence.enter(id)
        assertNull(OrbisSentinelPresence.leftAt(id, 9000))
        OrbisSentinelPresence.leave(id, 10000)
        assertEquals(10000L, OrbisSentinelPresence.leftAt(id, 20000))
    }

    @Test fun `different windows stay isolated even when they belong to one assistant`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        OrbisSentinelPresence.enter(first)
        OrbisSentinelPresence.enter(second)
        OrbisSentinelPresence.leave(first, 2000)
        assertEquals(2000L, OrbisSentinelPresence.leftAt(first, 5000))
        assertNull(OrbisSentinelPresence.leftAt(second, 5000))
        OrbisSentinelPresence.leave(second, 6000)
        assertEquals(6000L, OrbisSentinelPresence.leftAt(second, 10000))
        assertEquals(2000L, OrbisSentinelPresence.leftAt(first, 10000))
    }
}
