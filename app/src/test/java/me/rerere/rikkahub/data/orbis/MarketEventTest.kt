package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MarketEventTest {
    @Test fun internalMarketEventsKeepExactBindingAndExistingDeduplication() {
        val source = "market.${UUID.randomUUID()}"
        assertTrue(isMarketEventSource(source)); assertFalse(source in ORBIS_EVENT_SOURCES)
        assertFalse(isMarketEventSource("market.../secret"))
        var disk: String? = null
        val inbox = OrbisEventInbox(read = { disk }, write = { disk = it })
        val owner = OrbisEventBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        inbox.bind(setOf(source), owner)
        val input = OrbisIncomingEvent(UUID.randomUUID().toString(), source, "原样冻结的设定", occurred_at = 1)
        val one = inbox.accept(input, owner, 2, 0, true)
        val two = OrbisEventInbox(read = { disk }, write = { disk = it }).accept(input, owner, 3, 0, true)
        assertEquals(one.first, two.first); assertTrue(two.second)
        assertThrows(IllegalArgumentException::class.java) { inbox.accept(input.copy(text = "changed"), owner, 4) }
        assertEquals(owner.assistantId, one.first.assistantId)
    }
}
