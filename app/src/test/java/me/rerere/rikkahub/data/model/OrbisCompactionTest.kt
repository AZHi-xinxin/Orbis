package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.repository.decodeConversationEntity
import me.rerere.rikkahub.data.repository.encodeConversationEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisCompactionTest {
    @Test fun `legacy conversation defaults epoch to zero`() {
        val restored = JsonInstant.decodeFromString<Conversation>(
            """{"assistantId":"${Uuid.random()}","messageNodes":[]}""",
        )
        assertEquals(0L, restored.compactionEpoch)
    }

    @Test fun `epoch persists through database mapping`() {
        // Existing database timestamps are milliseconds, not Instant's nanosecond precision.
        val instant = java.time.Instant.ofEpochMilli(1_790_000_000_123)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(),
            compactionEpoch = 42, createAt = instant, updateAt = instant)
        assertEquals(conversation, decodeConversationEntity(encodeConversationEntity(conversation), emptyList()))
    }

    @Test fun `stale epoch is rejected rather than resurrecting old chat`() {
        assertThrows(OrbisCompactionConflictException::class.java) { requireCompactionEpoch(0, 1) }
        requireCompactionEpoch(2, 2)
    }

    @Test fun `summary hash uses exact original UTF8 without changing voice or whitespace`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", orbisCompactionSummaryHash("abc"))
        assertNotEquals(orbisCompactionSummaryHash("我在。"), orbisCompactionSummaryHash(" 我在。"))
        assertNotEquals(orbisCompactionSummaryHash("我在。"), orbisCompactionSummaryHash("他在。"))
    }

    @Test fun `restoring removes summary and keeps post compact messages`() {
        val old = node("old")
        val recent = node("recent")
        val summary = summary()
        val new = node("new after compact")
        assertEquals(listOf(old, recent, new), restoreCompactionNodes(
            listOf(old, recent), setOf(summary.id, recent.id), summary.id, listOf(summary, recent, new),
        ))
    }

    @Test fun `edits to retained message win over rollback backup`() {
        val old = node("old")
        val recent = node("before edit")
        val edited = recent.copy(messages = listOf(recent.currentMessage.copy(parts = UIMessage.user("after edit").parts)))
        val summary = summary()
        assertEquals(listOf(old, edited), restoreCompactionNodes(
            listOf(old, recent), setOf(summary.id, recent.id), summary.id, listOf(summary, edited),
        ))
    }

    @Test fun `retained messages deliberately deleted after compact are not resurrected`() {
        val old = node("old")
        val recent = node("deleted later")
        val summary = summary()
        assertEquals(listOf(old), restoreCompactionNodes(
            listOf(old, recent), setOf(summary.id, recent.id), summary.id, listOf(summary),
        ))
    }

    @Test fun `original summary output is restored to original position if same node was moved`() {
        val old = node("old")
        val authorSummary = summary()
        val later = node("after")
        assertEquals(listOf(old, authorSummary, later), restoreCompactionNodes(
            listOf(old, authorSummary), setOf(authorSummary.id), authorSummary.id, listOf(authorSummary, later),
        ))
    }

    @Test fun `rollback preserves alternative branches and selected index`() {
        val original = MessageNode(messages = listOf(UIMessage.assistant("a"), UIMessage.assistant("b")), selectIndex = 1)
        val summary = summary()
        val restored = restoreCompactionNodes(listOf(original), setOf(summary.id), summary.id, listOf(summary))
        assertEquals(original, restored.single())
        assertEquals("b", restored.single().currentMessage.toText())
    }

    @Test fun `rollback permits exact threshold plus 50k but rejects the next token`() {
        requireCompactionRollbackWithinLimit(400_000, 350_000)
        val failure = assertThrows(OrbisRollbackLimitException::class.java) {
            requireCompactionRollbackWithinLimit(400_001, 350_000)
        }
        assertEquals(400_001L, failure.estimatedTokens)
        assertEquals(400_000L, failure.maximumTokens)
    }

    @Test fun `zero threshold disables rollback size gate`() {
        requireCompactionRollbackWithinLimit(Long.MAX_VALUE, 0)
    }

    @Test fun `invalid rollback estimate and threshold fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { requireCompactionRollbackWithinLimit(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { requireCompactionRollbackWithinLimit(1, -1) }
    }

    @Test fun `duplicate nodes and missing backup never create an empty restored conversation`() {
        val old = node("old")
        val summary = summary()
        assertThrows(IllegalArgumentException::class.java) {
            restoreCompactionNodes(emptyList(), setOf(summary.id), summary.id, listOf(summary))
        }
        assertThrows(IllegalArgumentException::class.java) {
            restoreCompactionNodes(listOf(old, old), setOf(summary.id), summary.id, listOf(summary))
        }
    }

    @Test fun `node fingerprint changes for branches and ordering but ignores UI favorite flag`() {
        val a = node("a")
        val b = node("b")
        assertNotEquals(compactionNodeFingerprint(listOf(a, b)), compactionNodeFingerprint(listOf(b, a)))
        assertNotEquals(compactionNodeFingerprint(listOf(a)), compactionNodeFingerprint(listOf(a.copy(messages = a.messages + UIMessage.user("other branch")))))
        assertEquals(compactionNodeFingerprint(listOf(a)), compactionNodeFingerprint(listOf(a.copy(isFavorite = true))))
    }

    private fun node(text: String) = MessageNode.of(UIMessage.user(text))
    private fun summary() = MessageNode.of(UIMessage.assistant("Summary from AI itself"))
}
