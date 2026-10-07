package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class QueueRecoveryNoticeTest {
    @Test fun `acknowledged terminal notice is absent from a newly read view`() {
        for (phase in listOf(QueueRecoveryPhase.SUCCESS, QueueRecoveryPhase.PENDING, QueueRecoveryPhase.FAILURE)) {
            val result = QueueRecoveryState(phase, "synthetic result")
            val notices = consumeQueueRecoveryNotice(mapOf("chat" to result), "chat", result)
            assertEquals(QueueRecoveryState(), notices["chat"] ?: QueueRecoveryState())
            assertSame(notices, consumeQueueRecoveryNotice(notices, "chat", result))
        }
    }

    @Test fun `running and idle presentation cannot be consumed`() {
        for (phase in listOf(QueueRecoveryPhase.IDLE, QueueRecoveryPhase.RUNNING)) {
            val result = QueueRecoveryState(phase)
            val notices = mapOf("chat" to result)
            assertSame(notices, consumeQueueRecoveryNotice(notices, "chat", result))
        }
    }

    @Test fun `late acknowledgement does not hide structurally identical newer result`() {
        val old = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "recovered")
        val newer = old.copy()
        assertEquals(old, newer)
        assertNotSame(old, newer)
        val notices = mapOf("chat" to newer)
        assertSame(notices, consumeQueueRecoveryNotice(notices, "chat", old))
        assertTrue(consumeQueueRecoveryNotice(notices, "chat", newer).isEmpty())
    }

    @Test fun `late acknowledgement cannot clear a newer failure or running result`() {
        val old = QueueRecoveryState(QueueRecoveryPhase.SUCCESS)
        for (phase in listOf(QueueRecoveryPhase.RUNNING, QueueRecoveryPhase.PENDING, QueueRecoveryPhase.FAILURE)) {
            val notices = mapOf("chat" to QueueRecoveryState(phase))
            assertSame(notices, consumeQueueRecoveryNotice(notices, "chat", old))
        }
    }

    @Test fun `conversation scope and unrelated notices are preserved`() {
        val first = QueueRecoveryState(QueueRecoveryPhase.SUCCESS)
        val second = first.copy()
        val notices = mapOf("first" to first, "second" to second)
        assertSame(notices, consumeQueueRecoveryNotice(notices, "missing", first))
        assertSame(notices, consumeQueueRecoveryNotice(notices, "second", first))
        val consumed = consumeQueueRecoveryNotice(notices, "first", first)
        assertFalse(consumed.containsKey("first"))
        assertSame(second, consumed["second"])
    }

    @Test fun `consuming a notice preserves pending inputs and real pause state`() {
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text("synthetic retained input")),
            recoveryHeldReason = "previous_input_before_fresh_recovery")
        val queue = MessageQueueState(listOf(item), paused = true)
        val result = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, retainedMessageCount = 1, needsReview = true)
        val notices = consumeQueueRecoveryNotice(mapOf("chat" to result), "chat", result)
        assertTrue(notices.isEmpty())
        assertTrue(queue.paused)
        assertSame(item, queue.messages.single())
        assertEquals("previous_input_before_fresh_recovery", queue.messages.single().recoveryHeldReason)
    }
}
