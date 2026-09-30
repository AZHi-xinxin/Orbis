package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisToolRecordEdit
import me.rerere.rikkahub.data.model.applyToolRecordEdit
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisToolRecordPersistenceTest {
    private val now = LocalDateTime(2026, 9, 24, 21, 30)
    private fun conversation(): Conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("before"),
            UIMessagePart.Tool("call", "synthetic", "{}", listOf(UIMessagePart.Text("receipt"))),
            UIMessagePart.Text("after"))).toMessageNode(),
    ))
    private fun edit(value: Conversation, restore: Boolean = false) = OrbisToolRecordEdit(value.currentMessages.single().id, "call", restore)

    @Test fun `database success precedes live state publication`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val request = edit(original)
        val desired = original.applyToolRecordEdit(request, now)
        persistOrbisToolRecordEdit(state, request, now) { before ->
            assertEquals(original, before)
            assertEquals(original, state.value)
            desired.currentMessages.single()
        }
        assertEquals(desired, state.value)
    }

    @Test fun `failed transaction leaves original message and undo list untouched`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        try {
            persistOrbisToolRecordEdit(state, edit(original), now) { error("synthetic storage failure") }
            fail("must fail")
        } catch (expected: IllegalStateException) { assertEquals("synthetic storage failure", expected.message) }
        assertEquals(original, state.value)
    }

    @Test fun `suspended transaction does not show optimistic deletion and preserves newer unrelated fields`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val request = edit(original)
        val desired = original.applyToolRecordEdit(request, now)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            persistOrbisToolRecordEdit(state, request, now) {
                entered.complete(Unit)
                finish.await()
                desired.currentMessages.single()
            }
        }
        entered.await()
        assertEquals(original, state.value)
        state.update { current -> current.copy(title = "changed title", messageNodes = current.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.copy(translation = "completed translation") })
        }) }
        finish.complete(Unit)
        saving.await()
        assertEquals("changed title", state.value.title)
        assertEquals("completed translation", state.value.currentMessages.single().translation)
        assertEquals(desired.currentMessages.single().parts, state.value.currentMessages.single().parts)
    }

    @Test fun `generation read waits until tool transaction and state publication finish`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val gate = Mutex()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = edit(original)
        val desired = original.applyToolRecordEdit(request, now)
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock {
                persistOrbisToolRecordEdit(state, request, now) {
                    entered.complete(Unit)
                    finish.await()
                    desired.currentMessages.single()
                }
            }
        }
        entered.await()
        var sent = false
        val generation = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock { Unit } // Identical barrier at launchGenerationJob's entry.
            sent = true
            state.value.currentMessages.single()
        }
        assertFalse(sent)
        assertFalse(generation.isCompleted)
        finish.complete(Unit)
        saving.await()
        assertTrue(generation.await().getTools().isEmpty())
        assertTrue(sent)
    }

    @Test fun `restore publishes only the committed call and receipt with no execution`() = runBlocking {
        val original = conversation()
        val deleted = original.applyToolRecordEdit(edit(original), now)
        val state = MutableStateFlow(deleted)
        val request = edit(deleted, restore = true)
        var commits = 0
        persistOrbisToolRecordEdit(state, request, now) { before ->
            commits++
            before.applyToolRecordEdit(request, now).currentMessages.single()
        }
        assertEquals(1, commits)
        assertEquals(original.currentMessages.single().parts, state.value.currentMessages.single().parts)
        assertTrue(state.value.currentMessages.single().deletedToolRecords.isEmpty())
    }

    @Test fun `cancelled UI cannot leave successful database deletion unpublished`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val request = edit(original)
        val desired = original.applyToolRecordEdit(request, now)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            persistOrbisToolRecordEdit(state, request, now) {
                entered.complete(Unit)
                finish.await()
                desired.currentMessages.single()
            }
        }
        entered.await()
        saving.cancel()
        finish.complete(Unit)
        saving.join()
        assertEquals(desired, state.value)
    }

    @Test fun `validation failure never invokes persistent writer`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        var wrote = false
        try {
            persistOrbisToolRecordEdit(state, edit(original).copy(toolCallId = "missing"), now) {
                wrote = true
                it.currentMessages.single()
            }
            fail("must reject")
        } catch (_: IllegalStateException) { }
        assertFalse(wrote)
        assertEquals(original, state.value)
    }
}
