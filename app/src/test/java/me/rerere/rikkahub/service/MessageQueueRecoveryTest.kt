package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallProtocol
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MessageQueueRecoveryTest {
    private val call = "11111111-1111-4111-8111-111111111111"
    private fun text(value: String) = listOf(UIMessagePart.Text(value))
    private fun enqueueTurn(queue: MessageQueue, raw: String = "human words") {
        queue.enqueue(text(OrbisVoiceCallProtocol.userTurn(call, raw)), voiceCallId = call, voiceCallKind = "turn")
    }

    @Test fun `held call records do not block a new immediate sticker after recovery`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.holdCallInputsForRecovery()
        assertFalse(queue.state.value.blocksImmediateInput())
        assertTrue(me.rerere.rikkahub.data.model.canSendOrbisStickerNow(false, false,
            queue.state.value.blocksImmediateInput(), false))
        assertEquals(1, queue.state.value.messages.size)
    }

    @Test fun `paused queue blocks a new sticker even when it only contains held records`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.holdCallInputsForRecovery()
        queue.pause()
        assertTrue(queue.state.value.blocksImmediateInput())
        assertFalse(me.rerere.rikkahub.data.model.canSendOrbisStickerNow(false, false,
            queue.state.value.blocksImmediateInput(), false))
    }

    @Test fun `ordinary pending and editing inputs still block immediate sticker sends`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.holdCallInputsForRecovery()
        queue.enqueue(text("ordinary pending"))
        val ordinary = queue.state.value.messages.last()
        assertTrue(queue.state.value.blocksImmediateInput())
        queue.beginEdit(ordinary.id)
        assertTrue(queue.state.value.blocksImmediateInput())
        queue.remove(ordinary.id)
        assertFalse(queue.state.value.blocksImmediateInput())
    }

    @Test fun `empty paused queue is not implicitly resumed by an immediate sticker admission`() {
        val queue = MessageQueue(initiallyPaused = true)
        assertTrue(queue.state.value.blocksImmediateInput())
        assertTrue(queue.state.value.paused)
    }

    @Test fun `recovery retains every call input and dispatches only ordinary pending messages`() {
        val queue = MessageQueue()
        for (kind in listOf("begin", "end", "opening", "visual", "turn", "archive", "restore"))
            queue.enqueue(text(kind), voiceCallId = call, voiceCallKind = kind)
        queue.enqueue(text("human one"))
        queue.enqueue(text("human two"))
        val before = queue.state.value.messages
        queue.pause()
        assertEquals(7, queue.holdCallInputsForRecovery())
        queue.resume()
        val first = queue.takeNext()!!
        val second = queue.takeNext()!!
        assertEquals(before[7].id, first.id)
        assertEquals(before[8].id, second.id)
        assertFalse(first.acknowledgeSafetyHold)
        assertFalse(second.acknowledgeSafetyHold)
        assertNull(queue.takeNext())
        assertEquals(before.take(7).map { it.id }, queue.state.value.messages.map { it.id })
        assertEquals(before.take(7).map { it.parts }, queue.state.value.messages.map { it.parts })
    }

    @Test fun `held turn edit exposes human words but cancel preserves exact host record`() {
        val queue = MessageQueue()
        enqueueTurn(queue, "line one\nCALL_MODE_V1 human prose\nline three")
        val original = queue.state.value.messages.single()
        queue.holdCallInputsForRecovery()
        val editable = queue.beginEdit(original.id)!!
        assertEquals(text("line one\nCALL_MODE_V1 human prose\nline three"), editable.parts)
        assertEquals(original.parts, queue.state.value.messages.single().parts)
        queue.finishEdit(original.id)
        val retained = queue.state.value.messages.single()
        assertEquals(original.parts, retained.parts)
        assertEquals(call, retained.voiceCallId)
        assertNotNull(retained.recoveryHeldReason)
        assertFalse(retained.isEditing)
        assertNull(queue.takeNext())
    }

    @Test fun `explicit confirmation converts held dictation to ordinary input with same identity`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        val original = queue.state.value.messages.single()
        queue.holdCallInputsForRecovery()
        val editable = queue.beginEdit(original.id)!!
        queue.finishEdit(original.id, editable.parts)
        val sent = queue.takeNext()!!
        assertEquals(original.id, sent.id)
        assertEquals(text("human words"), sent.parts)
        assertNull(sent.voiceCallId)
        assertNull(sent.voiceCallKind)
        assertNull(sent.reply)
        assertNull(sent.recoveryHeldReason)
        assertTrue(sent.acknowledgeSafetyHold)
    }

    @Test fun `held controls cannot be converted by edit or forged finish`() {
        for (kind in listOf("begin", "end", "opening", "visual", "archive", "restore")) {
            val queue = MessageQueue()
            queue.enqueue(text("control"), voiceCallId = call, voiceCallKind = kind)
            queue.holdCallInputsForRecovery()
            val retained = queue.state.value.messages.single()
            assertNull(queue.beginEdit(retained.id))
            assertNull(queue.finishEdit(retained.id, text("replacement")))
            assertEquals(retained, queue.state.value.messages.single())
            assertNull(queue.takeNext())
        }
    }

    @Test fun `held turn requires explicit edit transaction before confirmation`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.holdCallInputsForRecovery()
        val retained = queue.state.value.messages.single()
        assertNull(queue.finishEdit(retained.id, text("replacement")))
        assertEquals(retained, queue.state.value.messages.single())
    }

    @Test fun `wrong call wrapper is retained rather than partially stripped`() {
        val queue = MessageQueue()
        queue.enqueue(text(OrbisVoiceCallProtocol.userTurn(Uuid.random().toString(), "human")),
            voiceCallId = call, voiceCallKind = "turn")
        queue.holdCallInputsForRecovery()
        val retained = queue.state.value.messages.single()
        assertNull(queue.beginEdit(retained.id))
        assertEquals(retained, queue.state.value.messages.single())
    }

    @Test fun `human prose without typed call ownership is never held or stripped`() {
        val queue = MessageQueue()
        val words = text(OrbisVoiceCallProtocol.userTurn(call, "literal human example"))
        queue.enqueue(words)
        assertEquals(0, queue.holdCallInputsForRecovery())
        assertEquals(words, queue.takeNext()!!.parts)
    }

    @Test fun `held inputs do not bypass an ordinary editing placeholder`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.enqueue(text("editing"))
        queue.enqueue(text("later"))
        val editing = queue.state.value.messages[1]
        queue.beginEdit(editing.id)
        queue.holdCallInputsForRecovery()
        assertNull(queue.takeNext())
        queue.finishEdit(editing.id)
        assertEquals(editing.id, queue.takeNext()!!.id)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test fun `recovery is idempotent and removal explicitly withdraws retained item`() {
        val queue = MessageQueue()
        enqueueTurn(queue)
        queue.holdCallInputsForRecovery()
        val once = queue.state.value.messages
        queue.holdCallInputsForRecovery()
        assertEquals(once, queue.state.value.messages)
        assertEquals(once.single(), queue.remove(once.single().id))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test fun `completed reply waiter is not treated as sent evidence`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text(OrbisVoiceCallProtocol.userTurn(call, "keep dictation")), reply = reply,
            voiceCallId = call, voiceCallKind = "turn")
        queue.pause()
        assertTrue(reply.isCompleted)
        queue.holdCallInputsForRecovery()
        queue.resume()
        assertEquals(1, queue.state.value.messages.size)
        assertNull(queue.takeNext())
    }

    @Test fun `new ordinary human input remains explicit acknowledgement after recovery`() {
        val queue = MessageQueue()
        queue.enqueue(text("old"))
        queue.holdCallInputsForRecovery()
        queue.enqueue(text("new explicit input"))
        assertFalse(queue.takeNext()!!.acknowledgeSafetyHold)
        assertTrue(queue.takeNext()!!.acknowledgeSafetyHold)
    }

    @Test fun `failed durable resume retains ordinary and held input`() {
        val queue = MessageQueue(initiallyPaused = true, onPauseChanged = { false })
        enqueueTurn(queue)
        queue.enqueue(text("ordinary"))
        queue.holdCallInputsForRecovery()
        val retained = queue.state.value.messages
        queue.resume()
        assertTrue(queue.state.value.paused)
        assertEquals(retained, queue.state.value.messages)
        assertNull(queue.takeNext())
    }
}
