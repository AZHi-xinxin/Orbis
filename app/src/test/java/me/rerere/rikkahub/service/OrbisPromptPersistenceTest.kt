package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisPromptPersistenceTest {
    @Test fun `worldbook shares the atomic committed prompt record`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        val value = initial.orbisPrompt.copy(worldBookText = "synthetic world", worldBookEnabled = true)
        persistOrbisPromptEdit(state, value) { snapshot, prompt ->
            assertEquals(initial, snapshot)
            assertEquals(value, prompt)
            assertEquals(initial.orbisPrompt, state.value.orbisPrompt)
        }
        assertEquals(value, withCommittedOrbisPrompt(initial, state.value).orbisPrompt)
        assertEquals(initial.messageNodes, state.value.messageNodes)
    }

    private fun conversation() = Conversation(
        assistantId = Uuid.random(), title = "current title",
        messageNodes = listOf(MessageNode.of(UIMessage.user("existing message"))),
        orbisPrompt = OrbisConversationPrompt("previous draft", false),
    )

    @Test fun `save uses latest state and preserves its messages and other metadata`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        state.update { it.copy(title = "newer title", messageNodes = it.messageNodes + MessageNode.of(UIMessage.user("newer message"))) }
        val latest = state.value
        val prompt = OrbisConversationPrompt("new prompt", true)
        persistOrbisPromptEdit(state, prompt) { value, submitted ->
            assertEquals(latest, value)
            assertEquals(latest, state.value)
            assertSame(prompt, submitted)
        }
        assertEquals(latest.copy(orbisPrompt = prompt), state.value)
    }

    @Test fun `failed persistence never publishes draft and retains unrelated updates`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        val failure = IllegalStateException("synthetic persistence failure")
        try {
            persistOrbisPromptEdit(state, OrbisConversationPrompt("draft", true)) { _, _ ->
                assertEquals(initial.orbisPrompt, state.value.orbisPrompt)
                state.update { it.copy(title = "concurrent title") }
                throw failure
            }
            fail("save must throw")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(initial.copy(title = "concurrent title"), state.value)
    }

    @Test fun `cancellation propagates and leaves the committed prompt unchanged`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        try {
            persistOrbisPromptEdit(state, OrbisConversationPrompt("draft", true)) { _, _ ->
                throw CancellationException("synthetic cancellation")
            }
            fail("save must cancel")
        } catch (_: CancellationException) {
            assertEquals(initial, state.value)
        }
    }

    @Test fun `failure never reverts a newer prompt field change`() = runBlocking {
        val state = MutableStateFlow(conversation())
        val newer = OrbisConversationPrompt("newer edit", false)
        try {
            persistOrbisPromptEdit(state, OrbisConversationPrompt("draft", true)) { _, _ ->
                state.update { it.copy(orbisPrompt = newer) }
                error("synthetic failure")
            }
            fail("save must throw")
        } catch (_: IllegalStateException) {
            assertEquals(newer, state.value.orbisPrompt)
        }
    }

    @Test fun `queued send cannot observe a failed draft during pending persistence`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        val mutex = Mutex()
        val pending = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val attempted = OrbisConversationPrompt("failed draft", true)
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.withLock {
                    persistOrbisPromptEdit(state, attempted) { _, _ ->
                        pending.complete(Unit)
                        finish.await()
                        error("synthetic storage failure")
                    }
                }
                fail("save must fail")
            } catch (_: IllegalStateException) {
                // The simulated storage operation did not commit.
            }
        }
        pending.await()
        // sendQueuedMessage may read before acquiring its save lock. Only committed text is visible.
        val senderSnapshot = state.value
        assertEquals(initial.orbisPrompt, senderSnapshot.orbisPrompt)
        val send = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                val saved = withCommittedOrbisPrompt(senderSnapshot, state.value)
                state.value = saved
                saved
            }
        }
        finish.complete(Unit)
        save.await()
        assertEquals(initial.orbisPrompt, send.await().orbisPrompt)
        assertEquals(initial.orbisPrompt, state.value.orbisPrompt)
    }

    @Test fun `whole-state sender with old snapshot preserves a successful newer prompt commit`() = runBlocking {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        val mutex = Mutex()
        val pending = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val committed = OrbisConversationPrompt("successfully stored", true)
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                persistOrbisPromptEdit(state, committed) { _, _ ->
                    pending.complete(Unit)
                    finish.await()
                }
            }
        }
        pending.await()
        val senderSnapshot = state.value.copy(
            messageNodes = state.value.messageNodes + MessageNode.of(UIMessage.user("queued message")),
        )
        assertEquals(initial.orbisPrompt, senderSnapshot.orbisPrompt)
        val send = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                val saved = withCommittedOrbisPrompt(senderSnapshot, state.value)
                state.value = saved
                saved
            }
        }
        finish.complete(Unit)
        save.await()
        val saved = send.await()
        assertEquals(committed, saved.orbisPrompt)
        assertEquals(senderSnapshot.messageNodes, saved.messageNodes)
        assertEquals(committed, state.value.orbisPrompt)
    }

    @Test fun `stale general update keeps latest committed prompt atomically`() {
        val initial = conversation()
        val state = MutableStateFlow(initial)
        val committed = OrbisConversationPrompt("new committed prompt", true)
        state.update { it.copy(orbisPrompt = committed) }
        state.update { current -> withCommittedOrbisPrompt(initial.copy(title = "new title"), current) }
        assertEquals(committed, state.value.orbisPrompt)
        assertEquals("new title", state.value.title)
    }
}
