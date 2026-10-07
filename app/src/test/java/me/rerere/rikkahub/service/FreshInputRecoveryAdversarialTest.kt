package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallProtocol
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Isolated admission/queue checks, not a claim of end-to-end ChatService or device coverage. */
class FreshInputRecoveryAdversarialTest {
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val otherAssistant = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val call = "33333333-3333-4333-8333-333333333333"
    private fun text(value: String) = listOf(UIMessagePart.Text(value))
    private fun fresh(gate: FreshHumanInputGate, value: String = "new human input"): QueuedMessage {
        val id = Uuid.random()
        return QueuedMessage(id = id, parts = text(value), freshHumanInputPermit = gate.issue(id))
    }

    @Test fun `permission is bound to the gate epoch and exact input identity`() {
        val originalGate = FreshHumanInputGate(assistant)
        val input = fresh(originalGate)
        assertTrue(originalGate.permits(input, assistant))
        assertFalse(originalGate.permits(input.copy(id = Uuid.random()), assistant))
        assertFalse(originalGate.permits(input, otherAssistant))
        assertFalse(FreshHumanInputGate(assistant).permits(input, assistant))
        assertFalse(originalGate.permits(input.copy(freshHumanInputPermit = null), assistant))
    }

    @Test fun `late speech accepted before recovery cannot acquire the replacement epoch`() {
        val oldGate = FreshHumanInputGate(assistant)
        val id = Uuid.random()
        val acceptedSpeech = oldGate.issue(id, call, "turn")
        val recoveredGate = FreshHumanInputGate(assistant)
        val lateInput = QueuedMessage(id = id, parts = text("accepted before mutex wait"),
            voiceCallId = call, voiceCallKind = "turn", freshHumanInputPermit = acceptedSpeech)
        assertFalse(recoveredGate.permits(lateInput, assistant))
        val newId = Uuid.random()
        assertTrue(recoveredGate.permits(lateInput.copy(id = newId,
            freshHumanInputPermit = recoveredGate.issue(newId, call, "turn")), assistant))
    }

    @Test fun `only ordinary input and an exact fresh spoken turn can receive permissions`() {
        val gate = FreshHumanInputGate(assistant)
        for (kind in listOf("begin", "end", "opening", "visual", "archive", "restore")) {
            assertTrue(kind, runCatching { gate.issue(Uuid.random(), call, kind) }.isFailure)
        }
        assertTrue(runCatching { gate.issue(Uuid.random(), call, null) }.isFailure)
        assertTrue(runCatching { gate.issue(Uuid.random(), null, "turn") }.isFailure)
        val input = fresh(gate)
        assertFalse(gate.permits(input.copy(orbisEventId = Uuid.random().toString()), assistant))
        assertFalse(gate.permits(input.copy(voiceCallId = call, voiceCallKind = "visual"), assistant))
        assertFalse(gate.permits(input.copy(isEditing = true), assistant))
        assertFalse(gate.permits(input.copy(recoveryHeldReason = "unknown_old_input"), assistant))
    }

    @Test fun `completion invalidates copies and retained references without granting a retry`() {
        val gate = FreshHumanInputGate(assistant)
        val input = fresh(gate)
        val copy = input.copy()
        input.freshHumanInputPermit!!.completed = true
        assertFalse(gate.permits(input, assistant))
        assertFalse(gate.permits(copy, assistant))
        assertTrue(gate.permits(fresh(gate, "a separate explicit new message"), assistant))
    }

    @Test fun `fresh recovery retains the complete mixed old queue and invalidates all old permits`() {
        val queue = MessageQueue()
        val oldGate = FreshHumanInputGate(assistant)
        val oldInput = fresh(oldGate, "old ordinary text")
        queue.enqueue(oldInput.parts, id = oldInput.id, freshHumanInputPermit = oldInput.freshHumanInputPermit)
        for (kind in listOf("begin", "end", "opening", "visual", "turn", "archive", "restore")) {
            queue.enqueue(text("old $kind"), voiceCallId = call, voiceCallKind = kind)
        }
        queue.enqueue(text("old automatic input"), orbisEventId = Uuid.random().toString())
        val before = queue.state.value.messages
        assertEquals(before.size, queue.holdAllInputsForFreshRecovery())
        val held = queue.state.value.messages
        assertEquals(before.map { it.id }, held.map { it.id })
        assertEquals(before.map { it.parts }, held.map { it.parts })
        assertTrue(held.all { it.recoveryHeldReason != null && !it.acknowledgeSafetyHold && it.freshHumanInputPermit == null })
        assertFalse(oldGate.permits(oldInput, assistant))
        assertNull(queue.takeNext())
        queue.holdAllInputsForFreshRecovery()
        assertEquals(held, queue.state.value.messages)
    }

    @Test fun `new explicit input alone is selectable even with held and stale delayed predecessors`() {
        val queue = MessageQueue()
        queue.enqueue(text("old ordinary input"))
        queue.holdAllInputsForFreshRecovery()
        val gate = FreshHumanInputGate(assistant)
        val staleGate = FreshHumanInputGate(assistant)
        val stale = fresh(staleGate, "late stale input")
        queue.enqueue(stale.parts, id = stale.id, freshHumanInputPermit = stale.freshHumanInputPermit)
        val next = fresh(gate)
        queue.enqueue(next.parts, id = next.id, freshHumanInputPermit = next.freshHumanInputPermit)
        val selected = queue.takeNext { gate.permits(it, assistant) }!!
        assertEquals(next.id, selected.id)
        assertFalse(selected.acknowledgeSafetyHold)
        assertEquals(2, queue.state.value.messages.size)
        assertNull(queue.takeNext { gate.permits(it, assistant) })
    }

    @Test fun `a failed new turn does not make a following explicit send replay held predecessors`() {
        val queue = MessageQueue()
        queue.enqueue(text("old input"))
        queue.holdAllInputsForFreshRecovery()
        val gate = FreshHumanInputGate(assistant)
        val failed = fresh(gate, "rejected by server with 409")
        queue.enqueue(failed.parts, id = failed.id, freshHumanInputPermit = failed.freshHumanInputPermit)
        assertEquals(failed.id, queue.takeNext { gate.permits(it, assistant) }!!.id)
        failed.freshHumanInputPermit!!.completed = true
        queue.pause()
        val newer = fresh(gate, "explicitly asked again later")
        queue.enqueue(newer.parts, id = newer.id, freshHumanInputPermit = newer.freshHumanInputPermit)
        assertNull(queue.takeNext { gate.permits(it, assistant) })
        // Only the caller's new explicit send resumes. Admission never retries failed itself.
        queue.resume()
        assertEquals(newer.id, queue.takeNext { gate.permits(it, assistant) }!!.id)
        assertEquals(text("old input"), queue.state.value.messages.single().parts)
        assertNull(queue.takeNext { gate.permits(it, assistant) })
    }

    @Test fun `durable pause write failure still blocks an otherwise valid fresh permission`() {
        val queue = MessageQueue(initiallyPaused = true, onPauseChanged = { false })
        val gate = FreshHumanInputGate(assistant)
        val input = fresh(gate)
        queue.enqueue(input.parts, id = input.id, freshHumanInputPermit = input.freshHumanInputPermit)
        queue.resume()
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext { gate.permits(it, assistant) })
        assertEquals(input.id, queue.state.value.messages.single().id)
    }

    @Test fun `canceling an old ordinary edit keeps it held and explicit confirmation needs a new permit`() {
        val queue = MessageQueue()
        queue.enqueue(text("old text"))
        val id = queue.state.value.messages.single().id
        queue.holdAllInputsForFreshRecovery()
        assertNotNull(queue.beginEdit(id))
        queue.finishEdit(id)
        assertNotNull(queue.state.value.messages.single().recoveryHeldReason)
        assertNull(queue.takeNext())
        val gate = FreshHumanInputGate(assistant)
        assertNotNull(queue.beginEdit(id))
        queue.finishEdit(id, text("explicitly reviewed text"), gate.issue(id))
        val confirmed = queue.takeNext { gate.permits(it, assistant) }!!
        assertEquals(id, confirmed.id)
        assertEquals(text("explicitly reviewed text"), confirmed.parts)
        assertFalse(confirmed.acknowledgeSafetyHold)
    }

    @Test fun `old spoken draft confirmation strips only its wrapper and cannot authorize old controls`() {
        val queue = MessageQueue()
        queue.enqueue(text(OrbisVoiceCallProtocol.userTurn(call, "kept human words")),
            voiceCallId = call, voiceCallKind = "turn")
        for (kind in listOf("begin", "end", "opening", "visual", "archive", "restore")) {
            queue.enqueue(text(kind), voiceCallId = call, voiceCallKind = kind)
        }
        queue.holdAllInputsForFreshRecovery()
        val turn = queue.state.value.messages.first()
        val gate = FreshHumanInputGate(assistant)
        val draft = queue.beginEdit(turn.id)!!
        assertEquals(text("kept human words"), draft.parts)
        queue.finishEdit(turn.id, draft.parts, gate.issue(turn.id))
        val confirmed = queue.takeNext { gate.permits(it, assistant) }!!
        assertNull(confirmed.voiceCallId)
        assertNull(confirmed.voiceCallKind)
        for (control in queue.state.value.messages) {
            assertNull(queue.beginEdit(control.id))
            assertNull(queue.finishEdit(control.id, text("forged confirmation"), gate.issue(control.id)))
        }
        assertNull(queue.takeNext { gate.permits(it, assistant) })
    }
}
