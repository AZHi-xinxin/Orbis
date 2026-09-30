package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.OrbisEventMetadata
import org.junit.Assert.*
import org.junit.Test

class OrbisEventTriggerTimeTest {
    private val binding = OrbisEventBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private val input = OrbisIncomingEvent("trigger-time-test", "lc_sentinel", "original", false)

    @Test fun senderTriggerAndHostReceiptRemainSeparateAndPersistAcrossReload() {
        var disk: String? = null
        val inbox = OrbisEventInbox({ disk }, { disk = it })
        inbox.bind(setOf(input.source), binding)
        val first = inbox.accept(input.copy(occurred_at = 1_000L), binding, 4_000L).first
        val restored = OrbisEventInbox({ disk }, { error("Read must not write") }).get(first.id)!!
        assertEquals(1_000L, restored.occurredAt)
        assertEquals(4_000L, restored.receivedAt)
        assertEquals("original", restored.text)
    }

    @Test fun identicalOrLegacyReplayDoesNotRewriteOriginalTime() {
        val inbox = OrbisEventInbox({ null }, {})
        inbox.bind(setOf(input.source), binding)
        val original = inbox.accept(input.copy(occurred_at = 100L), binding, 200L).first
        assertEquals(original, inbox.accept(input.copy(occurred_at = 100L), binding, 900L).first)
        assertEquals(original, inbox.accept(input, binding, 1_000L).first)
        assertTrue(runCatching { inbox.accept(input.copy(occurred_at = 101L), binding, 900L) }.isFailure)
        assertEquals(original, inbox.get(original.id))
    }

    @Test fun oldRecordKeepsUnknownTriggerInsteadOfInventingOneOnReplay() {
        val inbox = OrbisEventInbox({ null }, {})
        inbox.bind(setOf(input.source), binding)
        val original = inbox.accept(input, binding, 200L).first
        assertNull(original.occurredAt)
        assertEquals(original, inbox.accept(input.copy(occurred_at = 100L), binding, 900L).first)
    }

    @Test fun invalidOrUnreasonablyFutureTimestampDoesNotWrite() {
        var writes = 0
        val inbox = OrbisEventInbox({ null }, { writes++ })
        inbox.bind(setOf(input.source), binding)
        for (bad in listOf(-1L, 0L, 301_001L, Long.MAX_VALUE)) {
            val failure = runCatching { inbox.accept(input.copy(occurred_at = bad), binding, 1_000L) }.exceptionOrNull()
            assertEquals("invalid_event_time", failure?.message)
        }
        assertEquals(1, writes)
        assertTrue(inbox.state.value.events.isEmpty())
        assertEquals(301_000L, inbox.accept(input.copy(occurred_at = 301_000L), binding, 1_000L).first.occurredAt)
    }

    @Test fun earlierEventAndMessageFormatsDecodeWithoutMigration() {
        val request = Json.decodeFromString<OrbisIncomingEvent>("""{"event_id":"old","source":"lc_sentinel","text":"old"}""")
        assertNull(request.occurred_at)
        val metadata = Json.decodeFromString<OrbisEventMetadata>("""{"recordId":"old","source":"lc_sentinel","eventId":"old","receivedAt":200}""")
        assertNull(metadata.occurredAt)
        assertTrue(metadata.collapsed)
        val receipt = Json.decodeFromString<OrbisInboxEvent>("""{"eventId":"old","source":"lc_sentinel","text":"old","wake":false,"assistantId":"a","conversationId":"c","receivedAt":200}""")
        assertNull(receipt.occurredAt)
    }
}
