package me.rerere.rikkahub.data.orbis.workshop

import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

class WorkshopEventTest {
    private val source = "image_workshop.11111111-1111-4111-8111-111111111111"
    private val binding = OrbisEventBinding("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
    private val files = listOf("a".repeat(64) + ".png", "b".repeat(64) + ".webp")
    private fun inbox(): OrbisEventInbox { var disk: String? = null
        return OrbisEventInbox({ disk }, { disk = it }).also { it.bind(setOf(source, "lc_sentinel"), binding) } }
    @Test fun `internal source supports multiple durable originals`() {
        val inbox = inbox(); val input = OrbisIncomingEvent("job-result", source, "done", workshopImages = files)
        val event = inbox.accept(input, binding, 100).first
        assertEquals(files, event.workshopImages); assertEquals(binding.conversationId, event.conversationId)
        assertTrue(inbox.accept(input, binding, 200).second)
    }
    @Test fun `HTTP allowed sources cannot inject workshop files`() {
        assertFalse(source in ORBIS_EVENT_SOURCES)
        assertEquals("invalid_event_attachment", runCatching { inbox().accept(OrbisIncomingEvent("one", "lc_sentinel", "text", workshopImages = files), binding, 100) }.exceptionOrNull()?.message)
    }
    @Test fun `attachment replay changing images conflicts`() {
        val inbox = inbox(); val input = OrbisIncomingEvent("one", source, "done", workshopImages = files)
        inbox.accept(input, binding, 100)
        assertEquals("event_id_conflict", runCatching { inbox.accept(input.copy(workshopImages = files.take(1)), binding, 200) }.exceptionOrNull()?.message)
    }
    @Test fun `paths and duplicate attachments rejected`() {
        listOf(listOf("../private.png"), listOf(files.first(), files.first())).forEach { names ->
            assertEquals("invalid_event_attachment", runCatching { inbox().accept(OrbisIncomingEvent("one", source, "done", workshopImages = names), binding, 100) }.exceptionOrNull()?.message)
        }
    }

    @Test fun `workshop notifications remain isolated from another assistants active binding`() {
        val inbox = inbox()
        val other = OrbisEventBinding("44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555")
        inbox.bind(setOf("lc_sentinel"), other)
        val event = inbox.accept(OrbisIncomingEvent("image-result", source, "done", workshopImages = files),
            binding, 100, independentDelivery = true).first
        assertTrue(event.isConversationNotification(binding.assistantId, binding.conversationId))
        assertFalse(event.isConversationNotification(other.assistantId, other.conversationId))
        assertEquals(binding, inbox.binding(source))
        assertEquals(other, inbox.binding("lc_sentinel"))
        assertTrue(inbox.claimIndependentDispatch(event.id))
    }

    @Test fun `duplicate image result cannot claim a second automatic wake`() {
        val inbox = inbox()
        val input = OrbisIncomingEvent("image-result", source, "done", workshopImages = files)
        val event = inbox.accept(input, binding, 100, independentDelivery = true).first
        assertTrue(inbox.claimIndependentDispatch(event.id))
        val duplicate = inbox.accept(input, binding, 200, independentDelivery = true)
        assertTrue(duplicate.second)
        assertEquals(event.id, duplicate.first.id)
        assertEquals(files, duplicate.first.workshopImages)
        assertFalse(inbox.claimIndependentDispatch(duplicate.first.id))
        assertEquals(1, inbox.state.value.events.size)
    }

    @Test fun `changed target prevents old workshop result from waking another assistant`() {
        val inbox = inbox()
        val event = inbox.accept(OrbisIncomingEvent("image-result", source, "done", workshopImages = files),
            binding, 100, independentDelivery = true).first
        val other = OrbisEventBinding("44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555")
        inbox.bind(setOf(source), other)
        assertFalse(inbox.targetStillMatches(event))
        assertFalse(inbox.claimIndependentDispatch(event.id))
        assertEquals(binding.assistantId, inbox.get(event.id)?.assistantId)
        assertEquals(binding.conversationId, inbox.get(event.id)?.conversationId)
    }

    @Test fun `replayed result retains its original receipt even after source rebinding`() {
        val inbox = inbox()
        val input = OrbisIncomingEvent("image-result", source, "done", workshopImages = files)
        val event = inbox.accept(input, binding, 100, independentDelivery = true).first
        val other = OrbisEventBinding("44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555")
        inbox.bind(setOf(source), other)
        val duplicate = inbox.accept(input, other, 200, independentDelivery = true)
        assertTrue(duplicate.second)
        assertEquals(event, duplicate.first)
        assertFalse(inbox.claimIndependentDispatch(duplicate.first.id))
        assertFalse(duplicate.first.isConversationNotification(other.assistantId, other.conversationId))
    }
}
