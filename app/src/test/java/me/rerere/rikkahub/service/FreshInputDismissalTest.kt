package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class FreshInputDismissalTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun `old inputs are held before authorization and only later new input can leave`() {
        val assistant = Uuid.random()
        val previousGate = FreshHumanInputGate(assistant)
        val id = Uuid.random()
        val oldPermit = previousGate.issue(id)
        val writes = mutableListOf<String>()
        val queue = MessageQueue(onPauseChanged = { writes += if (it) "pause" else "resume"; true })
        queue.enqueue(text("old human"), id = id, freshHumanInputPermit = oldPermit)
        queue.enqueue(text("old automatic"), orbisEventId = "event")
        queue.enqueue(text("old call"), voiceCallId = "call", voiceCallKind = "turn")
        val originals = queue.state.value.messages
        commitDismissedPauseForFreshInput(queue, authorizeFreshInput = {
            assertTrue(queue.state.value.paused)
            assertTrue(queue.state.value.messages.all { it.recoveryHeldReason != null })
            assertNull(queue.takeNext())
            writes += "authorize"
        }, replaceGate = {
            assertEquals("authorize", writes.last())
            writes += "gate"
        })
        assertEquals(listOf("pause", "authorize", "gate", "resume"), writes)
        assertFalse(queue.state.value.paused)
        assertTrue(oldPermit.completed)
        assertEquals(originals.map { it.id }, queue.state.value.messages.map { it.id })
        assertEquals(originals.map { it.parts }, queue.state.value.messages.map { it.parts })
        assertEquals(originals.map { it.voiceCallId }, queue.state.value.messages.map { it.voiceCallId })
        assertTrue(queue.state.value.messages.all { !it.acknowledgeSafetyHold && it.freshHumanInputPermit == null })
        assertNull(queue.takeNext())
        queue.enqueue(text("new explicit input"))
        assertEquals(text("new explicit input"), queue.takeNext()!!.parts)
        assertEquals(3, queue.state.value.messages.size)
    }

    @Test fun `authorization failure preserves all input and never replaces gate or resumes`() {
        val queue = MessageQueue()
        queue.enqueue(text("old"))
        val original = queue.state.value.messages.single()
        val failure = IllegalStateException("durable authorization failed")
        val result = runCatching {
            commitDismissedPauseForFreshInput(queue, { throw failure }, { fail("gate must remain unchanged") })
        }
        assertSame(failure, result.exceptionOrNull())
        assertHeldAndPaused(queue, original)
    }

    @Test fun `gate replacement failure cannot dispatch despite already persisted authorization`() {
        val queue = MessageQueue()
        queue.enqueue(text("old"))
        val original = queue.state.value.messages.single()
        var authorized = false
        val failure = IllegalStateException("RAM gate failed")
        assertSame(failure, runCatching {
            commitDismissedPauseForFreshInput(queue, { authorized = true }, { throw failure })
        }.exceptionOrNull())
        assertTrue(authorized)
        assertHeldAndPaused(queue, original)
    }

    @Test fun `failed durable resume is reported with retained non replayable inputs`() {
        val queue = MessageQueue(onPauseChanged = { paused -> paused })
        queue.enqueue(text("old"))
        val original = queue.state.value.messages.single()
        var authorized = false
        var replaced = false
        assertTrue(runCatching {
            commitDismissedPauseForFreshInput(queue, { authorized = true }, { replaced = true })
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(authorized)
        assertTrue(replaced)
        assertHeldAndPaused(queue, original)
    }

    @Test fun `throwing resume and rollback preserve original error and live pause`() {
        val resumeFailure = IllegalStateException("resume failed")
        val rollbackFailure = IllegalStateException("pause persistence failed")
        var pauseCalls = 0
        val queue = MessageQueue(onPauseChanged = { paused ->
            if (!paused) throw resumeFailure
            if (++pauseCalls > 1) throw rollbackFailure
            true
        })
        queue.enqueue(text("old"))
        val original = queue.state.value.messages.single()
        assertSame(resumeFailure, runCatching {
            commitDismissedPauseForFreshInput(queue, {}, {})
        }.exceptionOrNull())
        assertEquals(listOf(rollbackFailure), resumeFailure.suppressed.toList())
        assertHeldAndPaused(queue, original)
    }

    @Test fun `initial pause failure still holds old input and never authorizes`() {
        val failure = IllegalStateException("pause unavailable")
        val queue = MessageQueue(onPauseChanged = { throw failure })
        queue.enqueue(text("old"))
        val original = queue.state.value.messages.single()
        assertSame(failure, runCatching {
            commitDismissedPauseForFreshInput(queue, { fail("must not authorize") }, { fail("must not replace") })
        }.exceptionOrNull())
        assertHeldAndPaused(queue, original)
    }

    private fun assertHeldAndPaused(queue: MessageQueue, original: QueuedMessage) {
        assertTrue(queue.state.value.paused)
        val retained = queue.state.value.messages.single()
        assertEquals(original.id, retained.id)
        assertEquals(original.parts, retained.parts)
        assertNotNull(retained.recoveryHeldReason)
        assertNull(queue.takeNext())
    }
}
