package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.applyEventPresentation
import me.rerere.rikkahub.data.model.withCommittedEventPresentation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisEventPresentationPersistenceTest {
    private val owner = Uuid.random()
    private val metadata = OrbisEventMetadata("receipt", "lc_sentinel", "event", 1_000L, occurredAt = 900L)
    private fun conversation() = Conversation(
        assistantId = owner, title = "Synthetic title",
        messageNodes = listOf(
            MessageNode.of(UIMessage.user("  原文\nexact whitespace  ").copy(orbisEvent = metadata)),
            MessageNode.of(UIMessage.assistant("stream start")),
        ),
    )
    private fun edit(conversation: Conversation) = OrbisEventPresentationEdit(
        nodeId = conversation.messageNodes.first().id,
        messageId = conversation.currentMessages.first().id,
        expected = metadata,
        originalText = conversation.currentMessages.first().toText(),
        read = true, collapsed = false,
    )
    private fun flags(conversation: Conversation) = requireNotNull(conversation.currentMessages.first().orbisEvent)

    @Test fun `success changes only read and collapsed and does not alter activity time`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        persistOrbisEventPresentation(state, owner, edit(original)) { assertEquals(original, state.value) }
        assertEquals(original.applyEventPresentation(owner, edit(original)), state.value)
        assertEquals(original.updateAt, state.value.updateAt)
        assertEquals(metadata.copy(read = true, collapsed = false), flags(state.value))
        assertEquals(original.currentMessages.first().parts, state.value.currentMessages.first().parts)
        assertEquals(original.currentMessages.first().role, state.value.currentMessages.first().role)
    }

    @Test fun `pending save publishes no optimistic flag and keeps concurrent stream plus new nodes`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            persistOrbisEventPresentation(state, owner, edit(original)) { started.complete(Unit); finish.await() }
        }
        started.await()
        assertEquals(metadata, flags(state.value))
        val streamed = original.messageNodes.last().copy(messages = listOf(
            original.currentMessages.last().copy(parts = listOf(UIMessagePart.Text("new stream tokens"))),
        ))
        val added = MessageNode.of(UIMessage.user("newly queued actual human message"))
        state.update { it.copy(title = "new title", messageNodes = listOf(it.messageNodes.first(), streamed, added)) }
        val latest = state.value
        finish.complete(Unit)
        save.await()
        assertEquals(latest.applyEventPresentation(owner, edit(original)), state.value)
        assertEquals(streamed, state.value.messageNodes[1])
        assertEquals(added, state.value.messageNodes[2])
    }

    @Test fun `failed persistence keeps previous flags and concurrent messages`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val appended = MessageNode.of(UIMessage.user("during failed storage"))
        val failure = IllegalStateException("synthetic disk failure")
        try {
            persistOrbisEventPresentation(state, owner, edit(original)) {
                state.update { it.copy(messageNodes = it.messageNodes + appended) }
                throw failure
            }
            fail("must fail")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy exceptions across withContext.
            // Propagation is defined by failure type/message, not object identity.
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        assertEquals(original.copy(messageNodes = original.messageNodes + appended), state.value)
    }

    @Test fun `stale streaming snapshot preserves committed flags while accepting new text and nodes`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        persistOrbisEventPresentation(state, owner, edit(original)) {}
        val streamSnapshot = original.copy(messageNodes = listOf(original.messageNodes.first(),
            original.messageNodes.last().copy(messages = listOf(original.currentMessages.last().copy(
                parts = listOf(UIMessagePart.Text("new stream chunk")),
            ))), MessageNode.of(UIMessage.user("new event queued"))))
        val merged = withCommittedEventPresentation(streamSnapshot, state.value)
        assertEquals(metadata.copy(read = true, collapsed = false), flags(merged))
        assertEquals(streamSnapshot.messageNodes.drop(1), merged.messageNodes.drop(1))
        assertEquals(streamSnapshot.currentMessages.first().parts, merged.currentMessages.first().parts)
    }

    @Test fun `merge never resurrects a deleted event node`() {
        val original = conversation()
        val committed = original.applyEventPresentation(owner, edit(original))
        val deleted = original.copy(messageNodes = original.messageNodes.drop(1))
        assertEquals(deleted, withCommittedEventPresentation(deleted, committed))
    }

    @Test fun `merge never transfers flags across changed owner or conversation`() {
        val original = conversation()
        val committed = original.applyEventPresentation(owner, edit(original))
        val otherOwner = original.copy(assistantId = Uuid.random())
        val otherChat = original.copy(id = Uuid.random())
        assertEquals(otherOwner, withCommittedEventPresentation(otherOwner, committed))
        assertEquals(otherChat, withCommittedEventPresentation(otherChat, committed))
    }

    @Test fun `merge does not mask new legitimate event payload or provenance`() {
        val original = conversation()
        val committed = original.applyEventPresentation(owner, edit(original))
        val event = original.currentMessages.first()
        val changedMessages = listOf(
            event.copy(parts = listOf(UIMessagePart.Text("changed payload"))),
            event.copy(role = MessageRole.ASSISTANT),
            event.copy(orbisEvent = metadata.copy(source = "self_reminder")),
            event.copy(orbisEvent = metadata.copy(eventId = "different")),
            event.copy(orbisEvent = metadata.copy(occurredAt = 901L)),
            event.copy(orbisEvent = metadata.copy(receivedAt = 1001L)),
            event.copy(orbisEvent = null),
        )
        changedMessages.forEach { message ->
            val incoming = original.copy(messageNodes = listOf(original.messageNodes.first().copy(messages = listOf(message))))
            assertEquals(incoming, withCommittedEventPresentation(incoming, committed))
        }
    }

    @Test fun `all immutable fields and exact payload are checked before storage`() = runBlocking {
        val original = conversation()
        val valid = edit(original)
        val invalid = listOf(
            valid.copy(nodeId = Uuid.random()), valid.copy(messageId = Uuid.random()),
            valid.copy(originalText = valid.originalText.trim()),
            valid.copy(expected = metadata.copy(recordId = "wrong")),
            valid.copy(expected = metadata.copy(source = "self_reminder")),
            valid.copy(expected = metadata.copy(eventId = "wrong")),
            valid.copy(expected = metadata.copy(receivedAt = 2_000)),
            valid.copy(expected = metadata.copy(occurredAt = null)),
        )
        invalid.forEach { request ->
            val state = MutableStateFlow(original)
            var invoked = false
            try {
                persistOrbisEventPresentation(state, owner, request) { invoked = true }
                fail("invalid identity must fail")
            } catch (_: IllegalStateException) { }
            assertFalse(invoked)
            assertEquals(original, state.value)
        }
    }

    @Test fun `missing node and wrong owner do not create a message or invoke storage`() = runBlocking {
        val original = conversation()
        listOf(original.copy(assistantId = Uuid.random()), original.copy(messageNodes = emptyList())).forEach { value ->
            val state = MutableStateFlow(value)
            var invoked = false
            try {
                persistOrbisEventPresentation(state, owner, edit(original)) { invoked = true }
                fail("invalid target must fail")
            } catch (_: IllegalStateException) { }
            assertFalse(invoked)
            assertEquals(value, state.value)
        }
    }

    @Test fun `alternate branches and selection index are not rewritten`() = runBlocking {
        val original = conversation()
        val alternate = UIMessage.user("alternate branch")
        val branched = original.copy(messageNodes = listOf(original.messageNodes.first().copy(
            messages = original.messageNodes.first().messages + alternate, selectIndex = 1,
        )) + original.messageNodes.drop(1))
        val state = MutableStateFlow(branched)
        persistOrbisEventPresentation(state, owner, edit(original)) {}
        assertEquals(1, state.value.messageNodes.first().selectIndex)
        assertEquals(alternate, state.value.messageNodes.first().messages[1])
        assertEquals(metadata.copy(read = true, collapsed = false), state.value.messageNodes.first().messages[0].orbisEvent)
    }

    @Test fun `stale fold request cannot mark an already read event unread`() = runBlocking {
        val original = conversation()
        val read = original.applyEventPresentation(owner, edit(original))
        val state = MutableStateFlow(read)
        persistOrbisEventPresentation(state, owner, edit(original).copy(read = false, collapsed = true)) {}
        assertEquals(metadata.copy(read = true, collapsed = true), flags(state.value))
    }

    @Test fun `cancelled UI waiter still publishes a completed storage commit`() = runBlocking {
        val original = conversation()
        val state = MutableStateFlow(original)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var persisted = false
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            persistOrbisEventPresentation(state, owner, edit(original)) {
                started.complete(Unit); finish.await(); persisted = true
            }
        }
        started.await()
        save.cancel()
        finish.complete(Unit)
        save.join()
        assertTrue(persisted)
        assertEquals(metadata.copy(read = true, collapsed = false), flags(state.value))
    }

    @Test fun `forked history can change presentation without an original delivery receipt`() = runBlocking {
        val original = conversation()
        val fork = original.copy(id = Uuid.random(), messageNodes = original.messageNodes.map { it.copy(id = Uuid.random()) })
        val state = MutableStateFlow(fork)
        var committed = false
        persistOrbisEventPresentation(state, owner, edit(fork)) { committed = true }
        assertTrue(committed)
        assertEquals(fork.id, state.value.id)
        assertEquals(metadata.copy(read = true, collapsed = false), flags(state.value))
        assertEquals(metadata, flags(original))
        assertEquals(original.currentMessages.first().parts, state.value.currentMessages.first().parts)
    }
}
