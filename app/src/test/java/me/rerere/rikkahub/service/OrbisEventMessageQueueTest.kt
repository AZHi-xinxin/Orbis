package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisEventMessageQueueTest {
    private val id = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `explicit id deduplicates queued events without replacing original payload`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"), answer = false, id = id, orbisEventId = "receipt-1")
        queue.enqueue(text("altered"), answer = true, id = id, orbisEventId = "receipt-2")
        assertEquals(1, queue.state.value.messages.size)
        val event = queue.takeNext()!!
        assertEquals(id, event.id)
        assertEquals("receipt-1", event.orbisEventId)
        assertEquals(text("original"), event.parts)
        assertFalse(event.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `different explicit ids are not content deduplicated`() {
        val queue = MessageQueue()
        queue.enqueue(text("same"), id = id, orbisEventId = "receipt-1")
        queue.enqueue(text("same"), id = Uuid.random(), orbisEventId = "receipt-2")
        assertEquals(2, queue.state.value.messages.size)
        assertEquals("receipt-1", queue.takeNext()!!.orbisEventId)
        assertEquals("receipt-2", queue.takeNext()!!.orbisEventId)
    }

    @Test
    fun `begin edit refuses event payload while leaving it dispatchable`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"), id = id, orbisEventId = "receipt-1")
        assertNull(queue.beginEdit(id))
        assertFalse(queue.state.value.messages.single().isEditing)
        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `direct finish edit cannot bypass event immutability`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"), id = id, orbisEventId = "receipt-1")
        val before = queue.state.value
        assertNull(queue.finishEdit(id, text("rewritten as human")))
        assertNull(queue.finishEdit(id))
        assertEquals(before, queue.state.value)
        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pause resume retains event identity and FIFO order among human messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("human before"))
        queue.enqueue(text("event"), id = id, orbisEventId = "receipt-1", answer = false)
        queue.enqueue(text("human after"))
        queue.pause()
        assertNull(queue.takeNext())
        assertEquals(3, queue.state.value.messages.size)
        queue.resume()
        assertEquals(text("human before"), queue.takeNext()!!.parts)
        val event = queue.takeNext()!!
        assertEquals(id, event.id)
        assertEquals("receipt-1", event.orbisEventId)
        assertFalse(event.answer)
        assertEquals(text("human after"), queue.takeNext()!!.parts)
    }

    @Test
    fun `removing event retains receipt on return and does not alter following human input`() {
        val queue = MessageQueue()
        queue.enqueue(text("event"), id = id, orbisEventId = "receipt-1")
        queue.enqueue(text("human"))
        val removed = queue.remove(id)!!
        assertEquals("receipt-1", removed.orbisEventId)
        assertEquals(text("event"), removed.parts)
        assertNull(queue.remove(id))
        assertEquals(text("human"), queue.takeNext()!!.parts)
    }

    @Test
    fun `explicit id scope is local to each conversation queue`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("one"), id = id, orbisEventId = "receipt-1")
        second.enqueue(text("two"), id = id, orbisEventId = "receipt-2")
        first.pause()
        assertNull(first.takeNext())
        assertEquals("receipt-2", second.takeNext()!!.orbisEventId)
        assertEquals(1, first.state.value.messages.size)
    }

    @Test
    fun `event parts snapshot cannot be mutated by caller after submission`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts, id = id, orbisEventId = "receipt-1")
        parts.clear()
        assertEquals(text("original"), queue.takeNext()!!.parts)
        assertTrue(queue.state.value.messages.isEmpty())
    }
}
