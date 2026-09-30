package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisEventInboxTest {
    private val first = OrbisEventBinding(
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
    )
    private val second = OrbisEventBinding(
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444",
    )
    private val json = Json { encodeDefaults = true }
    private val source = "lc_sentinel"
    private fun input(id: String = "event-1", text: String = "original", wake: Boolean = true) =
        OrbisIncomingEvent(id, source, text, wake)

    private class Storage(var contents: String? = null) {
        var writes = 0
        var fail = false
        fun open() = OrbisEventInbox(read = { contents }, write = {
            if (fail) throw IllegalStateException("disk_unavailable")
            writes++
            contents = it
        })
    }

    private fun rejects(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Expected $message", failure)
        assertEquals(message, failure?.message)
    }

    private fun archived(index: Int, text: String = "original") = OrbisInboxEvent(
        id = "record-$index", eventId = "event-$index", source = source, text = text, wake = true,
        assistantId = first.assistantId, conversationId = first.conversationId, receivedAt = index.toLong(),
    )

    @Test
    fun `new inbox is empty and does not write during construction`() {
        val disk = Storage()
        val inbox = disk.open()
        assertEquals(OrbisInboxState(), inbox.state.value)
        assertEquals(0, disk.writes)
        assertNull(inbox.binding(source))
        assertNull(inbox.get("absent"))
        assertNull(inbox.receipt(source, "absent"))
    }

    @Test
    fun `binding sources is isolated and validates identifiers without publishing failures`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source, "self_reminder"), first)
        inbox.bind(setOf("rikka_sentinel"), second)
        assertEquals(first, inbox.binding(source))
        assertEquals(first, inbox.binding("self_reminder"))
        assertEquals(second, inbox.binding("rikka_sentinel"))
        val before = inbox.state.value
        rejects("invalid_event_source") { inbox.bind(emptySet(), first) }
        rejects("invalid_event_source") { inbox.bind(setOf(source, "unknown"), first) }
        assertNotNull(runCatching { inbox.bind(setOf(source), first.copy(assistantId = "invalid")) }.exceptionOrNull())
        assertNotNull(runCatching { inbox.bind(setOf(source), first.copy(conversationId = "invalid")) }.exceptionOrNull())
        assertEquals(before, inbox.state.value)
        assertEquals(2, disk.writes)
    }

    @Test
    fun `accept retains exact original text destination and timestamp`() {
        val inbox = Storage().open()
        inbox.bind(setOf(source), first)
        val text = "  line one\n用户原文\t\n"
        val (event, duplicate) = inbox.accept(input(text = text, wake = false), first, 123L)
        assertFalse(duplicate)
        assertEquals(text, event.text)
        assertFalse(event.wake)
        assertEquals(123L, event.receivedAt)
        assertEquals(first.assistantId, event.assistantId)
        assertEquals(first.conversationId, event.conversationId)
        assertEquals("accepted", event.state)
        assertEquals(event, inbox.get(event.id))
        assertEquals(event, inbox.receipt(source, "event-1"))
        assertTrue(inbox.targetStillMatches(event))
    }

    @Test
    fun `identical replay returns original receipt without another write`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source), first)
        val original = inbox.accept(input(), first, 123L).first
        inbox.mark(original.id, "replied")
        val writes = disk.writes
        val (replayed, duplicate) = inbox.accept(input(), first, 999L)
        assertTrue(duplicate)
        assertEquals(original.id, replayed.id)
        assertEquals(123L, replayed.receivedAt)
        assertEquals("replied", replayed.state)
        assertEquals(1, inbox.state.value.events.size)
        assertEquals(writes, disk.writes)
    }

    @Test
    fun `same source and event id with altered text or wake conflicts`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source), first)
        inbox.accept(input(), first, 1)
        val before = inbox.state.value
        val stored = disk.contents
        rejects("event_id_conflict") { inbox.accept(input(text = "original "), first, 2) }
        rejects("event_id_conflict") { inbox.accept(input(wake = false), first, 2) }
        assertEquals(before, inbox.state.value)
        assertEquals(stored, disk.contents)
        assertEquals(2, disk.writes)
    }

    @Test
    fun `same event id from separate sources gets independent receipts and destinations`() {
        val inbox = Storage().open()
        inbox.bind(setOf(source), first)
        inbox.bind(setOf("self_reminder"), second)
        val a = inbox.accept(input(), first, 1).first
        val b = inbox.accept(input(text = "different").copy(source = "self_reminder"), second, 2).first
        assertFalse(a.id == b.id)
        assertEquals(2, inbox.state.value.events.size)
        assertEquals(a, inbox.receipt(source, "event-1"))
        assertEquals(b, inbox.receipt("self_reminder", "event-1"))
        assertEquals(second.conversationId, b.conversationId)
    }

    @Test
    fun `wrong expected target and unbound source cannot accept a new event`() {
        val inbox = Storage().open()
        rejects("event_target_changed") { inbox.accept(input(), first, 1) }
        inbox.bind(setOf(source), first)
        rejects("event_target_changed") { inbox.accept(input(), second, 1) }
        assertTrue(inbox.state.value.events.isEmpty())
    }

    @Test
    fun `rebinding invalidates old delivery target without rewriting accepted receipts`() {
        val inbox = Storage().open()
        inbox.bind(setOf(source), first)
        val original = inbox.accept(input(), first, 1).first
        inbox.bind(setOf(source), second)
        assertFalse(inbox.targetStillMatches(original))
        rejects("event_target_changed") { inbox.accept(input("event-2"), first, 2) }
        val next = inbox.accept(input("event-2"), second, 2).first
        assertTrue(inbox.targetStillMatches(next))
        assertEquals(original, inbox.get(original.id))
        // Receipt replay is not a new delivery and must never retarget an old event.
        val replay = inbox.accept(input(), second, 3)
        assertTrue(replay.second)
        assertEquals(original, replay.first)
        assertFalse(inbox.targetStillMatches(replay.first))
    }

    @Test
    fun `disabling binding blocks new events and invalidates existing delivery`() {
        val inbox = Storage().open()
        inbox.bind(setOf(source), first)
        val original = inbox.accept(input(), first, 1).first
        val disabled = first.copy(enabled = false)
        inbox.bind(setOf(source), disabled)
        assertNull(inbox.binding(source))
        assertFalse(inbox.targetStillMatches(original))
        rejects("event_target_changed") { inbox.accept(input("event-2"), first, 2) }
        rejects("event_target_changed") { inbox.accept(input("event-2"), disabled, 2) }
        assertEquals(original, inbox.accept(input(), first, 3).first)
        assertEquals(1, inbox.state.value.events.size)
    }

    @Test
    fun `input validation rejects blank or oversized bodies and malformed ids`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source), first)
        listOf("", " ", "with/slash", "with\nnewline", "x".repeat(181)).forEach { id ->
            rejects("invalid_event_id") { inbox.accept(input(id), first, 1) }
        }
        listOf("", " \n\t", "x".repeat(65537), "界".repeat(21846)).forEach { body ->
            rejects("invalid_event_text") { inbox.accept(input(text = body), first, 1) }
        }
        rejects("invalid_event_source") { inbox.accept(input().copy(source = "other"), first, 1) }
        assertEquals(1, disk.writes)
        assertTrue(inbox.state.value.events.isEmpty())
        assertEquals(65536, inbox.accept(input(text = "x".repeat(65536)), first, 1).first.text.length)
    }

    @Test
    fun `record capacity rejects new writes but still returns existing receipt`() {
        val events = List(2000) { archived(it) }
        val disk = Storage(json.encodeToString(OrbisInboxState(bindings = mapOf(source to first), events = events)))
        val inbox = disk.open()
        rejects("event_inbox_full") { inbox.accept(input("new-event"), first, 2001) }
        assertEquals(events[1], inbox.accept(input("event-1"), first, 2001).first)
        assertEquals(2000, inbox.state.value.events.size)
        assertEquals(0, disk.writes)
    }

    @Test
    fun `serialized byte capacity fails before storage write and state publication`() {
        val events = List(127) { archived(it, "x".repeat(65536)) }
        val encoded = json.encodeToString(OrbisInboxState(bindings = mapOf(source to first), events = events))
        assertTrue(encoded.toByteArray().size < 8 * 1024 * 1024)
        assertTrue(encoded.toByteArray().size + 65536 > 8 * 1024 * 1024)
        val disk = Storage(encoded)
        val inbox = disk.open()
        rejects("event_inbox_full") { inbox.accept(input("new-event", "x".repeat(65536)), first, 999) }
        assertEquals(events, inbox.state.value.events)
        assertEquals(encoded, disk.contents)
        assertEquals(0, disk.writes)
    }

    @Test
    fun `storage failure never publishes a new binding event or status`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source), first)
        val original = inbox.accept(input(), first, 1).first
        val before = inbox.state.value
        val stored = disk.contents
        disk.fail = true
        rejects("disk_unavailable") { inbox.bind(setOf(source), second) }
        rejects("disk_unavailable") { inbox.accept(input("event-2"), first, 2) }
        rejects("disk_unavailable") { inbox.mark(original.id, "queued") }
        assertEquals(before, inbox.state.value)
        assertEquals(stored, disk.contents)
        assertEquals(2, disk.writes)
    }

    @Test
    fun `state publication happens only after persistence callback completes`() {
        lateinit var inbox: OrbisEventInbox
        val observed = mutableListOf<OrbisInboxState>()
        inbox = OrbisEventInbox(read = { null }, write = { observed += inbox.state.value })
        inbox.bind(setOf(source), first)
        inbox.accept(input(), first, 1)
        assertTrue(observed[0].bindings.isEmpty())
        assertEquals(first, observed[1].bindings[source])
        assertTrue(observed[1].events.isEmpty())
        assertEquals(1, inbox.state.value.events.size)
    }

    @Test
    fun `restart restores receipt ids bindings and marked state without rewriting disk`() {
        val disk = Storage()
        val initial = disk.open()
        initial.bind(setOf(source), first)
        initial.bind(setOf("self_reminder"), second.copy(enabled = false))
        val event = initial.accept(input(wake = false), first, 77).first
        initial.mark(event.id, "unknown", "interrupted_after_dispatch")
        val writes = disk.writes
        val reopened = disk.open()
        assertEquals(initial.state.value, reopened.state.value)
        assertEquals("unknown", reopened.get(event.id)?.state)
        assertEquals("interrupted_after_dispatch", reopened.get(event.id)?.error)
        assertNull(reopened.binding("self_reminder"))
        assertTrue(reopened.accept(input(wake = false), first, 999).second)
        assertEquals(writes, disk.writes)
    }

    @Test
    fun `every supported mark preserves original source text and target`() {
        OrbisEventInbox.STATES.forEach { status ->
            val inbox = Storage().open()
            inbox.bind(setOf(source), first)
            val original = inbox.accept(input(), first, 1).first
            inbox.mark(original.id, status, "diagnostic")
            assertEquals(original.copy(state = status, error = "diagnostic"), inbox.get(original.id))
            inbox.mark(original.id, "displayed")
            if (status == "suppressed") {
                assertEquals("suppressed", inbox.get(original.id)?.state)
                assertEquals("diagnostic", inbox.get(original.id)?.error)
            } else assertNull(inbox.get(original.id)?.error)
        }
    }

    @Test
    fun `invalid status or missing record cannot create or alter an event`() {
        val disk = Storage()
        val inbox = disk.open()
        inbox.bind(setOf(source), first)
        val event = inbox.accept(input(), first, 1).first
        val before = inbox.state.value
        assertNotNull(runCatching { inbox.mark(event.id, "invented-status") }.exceptionOrNull())
        rejects("event_missing") { inbox.mark("not-present", "queued") }
        assertEquals(before, inbox.state.value)
        assertEquals(2, disk.writes)
    }

    @Test
    fun `corrupt unsupported or unreadable storage is not silently reset`() {
        var writes = 0
        listOf("not-json", json.encodeToString(OrbisInboxState(version = 2))).forEach { stored ->
            assertNotNull(runCatching { OrbisEventInbox({ stored }, { writes++ }) }.exceptionOrNull())
        }
        rejects("event_inbox_too_large") {
            OrbisEventInbox({ " ".repeat(8 * 1024 * 1024 + 1) }, { writes++ })
        }
        rejects("read_failed") {
            OrbisEventInbox({ throw IllegalStateException("read_failed") }, { writes++ })
        }
        assertEquals(0, writes)
    }

    @Test
    fun `unknown optional persisted fields do not discard existing records`() {
        val event = archived(1)
        val encoded = json.encodeToString(OrbisInboxState(events = listOf(event)))
        val extended = encoded.dropLast(1) + ",\"future_note\":\"ignored\"}"
        val disk = Storage(extended)
        assertEquals(event, disk.open().get(event.id))
        assertEquals(0, disk.writes)
    }

    @Test
    fun `token comparison requires exact nonshort token and bounded supplied token`() {
        val token = "aB0_".repeat(8)
        assertTrue(OrbisEventInbox.tokenMatches(token, token))
        assertFalse(OrbisEventInbox.tokenMatches(token, null))
        assertFalse(OrbisEventInbox.tokenMatches("short", "short"))
        assertFalse(OrbisEventInbox.tokenMatches(token, token.lowercase()))
        assertFalse(OrbisEventInbox.tokenMatches(token, "$token "))
        assertFalse(OrbisEventInbox.tokenMatches(token, token.dropLast(1)))
        assertFalse(OrbisEventInbox.tokenMatches(token, "z" + token.drop(1)))
        assertTrue(OrbisEventInbox.tokenMatches("a".repeat(256), "a".repeat(256)))
        assertFalse(OrbisEventInbox.tokenMatches("a".repeat(257), "a".repeat(257)))
    }
}
