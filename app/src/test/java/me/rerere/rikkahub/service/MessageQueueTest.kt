package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.KnownEmptyCompletionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    @Test fun `failed preflight restores exact input before later items and preserves pause`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("first")))
        queue.enqueue(listOf(UIMessagePart.Text("second")))
        val first = queue.takeNext()!!
        val second = queue.state.value.messages.single()
        queue.retainUndispatched(first)
        assertEquals(listOf(first, second), queue.state.value.messages)
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.retainUndispatched(first)
        assertEquals(listOf(first, second), queue.state.value.messages)
        queue.resume()
        assertEquals(first, queue.takeNext())
    }
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun emptyFailure() = KnownEmptyCompletionFailure.classify(
        HttpException("Only reasoning", "upstream_empty_completion", "stiller_gateway_error"), null,
    )

    @Test fun `restored durable pause keeps old events queued`() {
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(text("restored pending"), orbisEventId = "old-event")
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals("old-event", queue.takeNext()!!.orbisEventId)
    }

    @Test fun `resume storage failure cannot release queued work`() {
        val writes = mutableListOf<Boolean>()
        val queue = MessageQueue(initiallyPaused = true, onPauseChanged = { writes += it; false })
        queue.enqueue(text("keep"))
        queue.resume()
        assertEquals(listOf(false), writes)
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
    }

    @Test fun `pause stops immediately even if persistence fails`() {
        val queue = MessageQueue(onPauseChanged = { false })
        queue.enqueue(text("keep"))
        queue.pause()
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
    }

    @Test fun `safe empty failure does not persist a new pause`() {
        val writes = mutableListOf<Boolean>()
        val queue = MessageQueue(onPauseChanged = { writes += it; true })
        queue.afterGenerationFailure(emptyFailure(), partialSnapshotSaved = true)
        assertTrue(writes.isEmpty())
        assertFalse(queue.state.value.paused)
    }

    @Test fun `known empty failure advances independent input without replaying failed input`() {
        val queue = MessageQueue()
        queue.enqueue(text("failed wake"), orbisEventId = "event-1")
        queue.enqueue(text("later wake"), orbisEventId = "event-2")
        assertEquals("event-1", queue.takeNext()!!.orbisEventId)
        queue.afterGenerationFailure(emptyFailure(), partialSnapshotSaved = true)
        assertFalse(queue.state.value.paused)
        assertEquals("event-2", queue.takeNext()!!.orbisEventId)
        assertNull(queue.takeNext())
    }

    @Test fun `known empty failure never overrides an existing explicit pause`() {
        val queue = MessageQueue()
        queue.enqueue(text("later"))
        queue.pause()
        queue.afterGenerationFailure(emptyFailure(), partialSnapshotSaved = true)
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
    }

    @Test fun `unsaved partial tool receipts still pause even for known empty result`() {
        val queue = MessageQueue()
        queue.enqueue(text("later"))
        queue.afterGenerationFailure(emptyFailure(), partialSnapshotSaved = false)
        assertTrue(queue.state.value.paused)
        assertEquals(1, queue.state.value.messages.size)
    }

    @Test fun `unknown failure still preserves queue for human decision`() {
        val queue = MessageQueue()
        queue.enqueue(text("later"))
        queue.afterGenerationFailure(IllegalStateException("result unknown"), partialSnapshotSaved = true)
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }
}
