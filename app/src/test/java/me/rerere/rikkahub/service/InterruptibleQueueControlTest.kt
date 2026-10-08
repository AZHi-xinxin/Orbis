package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import org.junit.Assert.*
import org.junit.Test

class InterruptibleQueueControlTest {
    @Test fun `reset cancels only its control scope and leaves callers and other keys active`() = runTest {
        val control = InterruptibleQueueControl<String>()
        val started = CompletableDeferred<Unit>()
        val otherRelease = CompletableDeferred<Unit>()
        val parent = async {
            try {
                control.run("chat") { started.complete(Unit); awaitCancellation() }
                fail("control should be cancelled")
            } catch (_: CancellationException) {
                assertTrue(currentCoroutineContext().isActive)
            }
            "parent survived"
        }
        val other = async { control.run("other") { otherRelease.await(); 7 } }
        started.await()
        assertTrue(control.beginLocalReset("chat"))
        control.awaitInterrupted("chat")
        assertEquals("parent survived", parent.await())
        assertTrue(other.isActive)
        assertTrue(currentCoroutineContext().isActive)
        otherRelease.complete(Unit)
        assertEquals(7, other.await())
        control.endLocalReset("chat")
        assertEquals(9, control.run("chat") { 9 })
    }

    @Test fun `one reset wins and no later registration enters its block`() = runTest {
        val control = InterruptibleQueueControl<String>()
        assertTrue(control.beginLocalReset("chat"))
        assertFalse(control.beginLocalReset("chat"))
        assertTrue(control.isResetting("chat"))
        var entered = false
        try {
            control.run("chat") { entered = true }
            fail("reset must reject registration")
        } catch (_: CancellationException) {
            assertTrue(currentCoroutineContext().isActive)
        }
        assertFalse(entered)
        control.awaitInterrupted("chat")
        control.endLocalReset("chat")
        assertFalse(control.isResetting("chat"))
        control.run("chat") { entered = true }
        assertTrue(entered)
    }

    @Test fun `reset returns without waiting but await includes captured worker cleanup`() = runTest {
        val control = InterruptibleQueueControl<String>()
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val worker = launch {
            try {
                control.run("chat") {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.await() } }
                }
            } catch (_: CancellationException) { }
        }
        started.await()
        assertTrue(control.beginLocalReset("chat"))
        val waiting = launch { control.awaitInterrupted("chat") }
        runCurrent()
        assertFalse(worker.isCompleted)
        assertFalse(waiting.isCompleted)
        cleanup.complete(Unit)
        waiting.join()
        worker.join()
        control.endLocalReset("chat")
    }

    @Test fun `registration racing reset is either captured or rejected`() = runTest {
        repeat(32) {
            coroutineScope {
                val control = InterruptibleQueueControl<String>()
                val start = CompletableDeferred<Unit>()
                val worker = launch(Dispatchers.Default) {
                    start.await()
                    try { control.run("chat") { awaitCancellation() } }
                    catch (_: CancellationException) { assertTrue(currentCoroutineContext().isActive) }
                }
                val reset = async(Dispatchers.Default) {
                    start.await()
                    control.beginLocalReset("chat")
                }
                start.complete(Unit)
                assertTrue(reset.await())
                control.awaitInterrupted("chat")
                worker.join()
                assertTrue(control.isResetting("chat"))
                control.endLocalReset("chat")
            }
        }
    }

    @Test fun `manual interruption and local reset release independently`() = runTest {
        val control = InterruptibleQueueControl<String>()
        control.interrupt("chat")
        assertTrue(control.beginLocalReset("chat"))
        control.release("chat")
        assertTrue(runCatching { control.run("chat") { error("must not enter") } }
            .exceptionOrNull() is CancellationException)
        control.interrupt("chat")
        control.endLocalReset("chat")
        assertFalse(control.isResetting("chat"))
        assertTrue(runCatching { control.run("chat") { error("must not enter") } }
            .exceptionOrNull() is CancellationException)
        control.release("chat")
        assertEquals("ready", control.run("chat") { "ready" })
    }

    @Test fun `completed failed run does not leave a registered worker`() = runTest {
        val control = InterruptibleQueueControl<String>()
        val failure = IllegalStateException("synthetic")
        val observed = checkNotNull(runCatching { control.run("chat") { throw failure } }.exceptionOrNull())
        // Coroutine stacktrace recovery may copy the exception while retaining its cause.
        assertEquals(failure.javaClass, observed.javaClass)
        assertEquals(failure.message, observed.message)
        assertTrue(generateSequence(observed) { it.cause }.take(8).any { it === failure })
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(control.beginLocalReset("chat"))
        control.awaitInterrupted("chat")
        control.endLocalReset("chat")
        assertEquals(1, control.run("chat") { 1 })
    }

    @Test fun `scope remains registered after its block returns until attached child cleanup ends`() = runTest {
        val control = InterruptibleQueueControl<String>()
        val blockReturned = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>()
        val childCleanup = CompletableDeferred<Unit>()
        var childFinished = false
        val parent = launch {
            try {
                control.run("chat") {
                    CoroutineScope(currentCoroutineContext()).launch {
                        try { childStarted.complete(Unit); awaitCancellation() }
                        finally {
                            withContext(NonCancellable) { childCleanup.await() }
                            childFinished = true
                        }
                    }
                    blockReturned.complete(Unit)
                }
                fail("reset must cancel the still-running scope")
            } catch (_: CancellationException) {
                assertTrue(currentCoroutineContext().isActive)
            }
        }
        blockReturned.await()
        childStarted.await()
        assertTrue(control.beginLocalReset("chat"))
        val waiting = launch { control.awaitInterrupted("chat") }
        runCurrent()
        assertFalse(parent.isCompleted)
        assertFalse(waiting.isCompleted)
        assertFalse(childFinished)
        childCleanup.complete(Unit)
        waiting.join()
        parent.join()
        assertTrue(childFinished)
        control.endLocalReset("chat")
    }

    @Test fun `dismissal cancels stuck remote control but waits for local generation save before fresh input`() = runTest {
        assertDismissalHandoff(resetBeforeRegistration = false)
    }

    @Test fun `reset before terminal control registration rejects network work but still saves local generation`() = runTest {
        assertDismissalHandoff(resetBeforeRegistration = true)
    }

    /** Mirrors ChatService's NonCancellable terminal cleanup and explicit-dismissal ordering. */
    private suspend fun TestScope.assertDismissalHandoff(resetBeforeRegistration: Boolean) {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val controls = InterruptibleQueueControl<Uuid>()
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(listOf(UIMessagePart.Text("old accepted input")))
        queue.enqueue(listOf(UIMessagePart.Text("old automatic event")), orbisEventId = "old-event")
        val oldIds = queue.state.value.messages.map { it.id }
        var durableAuthorization: String? = null
        val store = FreshHumanInputRecoveryStore(OrbisQueuePauseStore(
            { durableAuthorization }, { durableAuthorization = it }))
        var gate: FreshHumanInputGate? = null
        var remoteCalls = 0
        var remoteReturned = false
        var remoteHoldPreserved = false
        var automaticHoldPreserved = false
        var locallySaved = false
        val generationStarted = CompletableDeferred<Unit>()
        val remoteStarted = CompletableDeferred<Unit>()
        val remoteResponse = CompletableDeferred<Unit>()
        val localSaveStarted = CompletableDeferred<Unit>()
        val allowLocalSave = CompletableDeferred<Unit>()
        val generation = launch {
            try { generationStarted.complete(Unit); awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    try {
                        controls.run(conversation) {
                            remoteCalls++
                            remoteStarted.complete(Unit)
                            remoteResponse.await()
                            remoteReturned = true
                        }
                    } catch (cancelled: CancellationException) {
                        if (!controls.isResetting(conversation)) throw cancelled
                        assertTrue(currentCoroutineContext().isActive)
                        remoteHoldPreserved = true
                        automaticHoldPreserved = true
                    } finally {
                        // This must finish even when remote control was cancelled/rejected.
                        localSaveStarted.complete(Unit)
                        allowLocalSave.await()
                        locallySaved = true
                    }
                }
            }
        }
        generationStarted.await()
        if (!resetBeforeRegistration) {
            generation.cancel()
            remoteStarted.await()
        }
        assertTrue(controls.beginLocalReset(conversation))
        queue.holdAllInputsForFreshRecovery()
        generation.cancel()
        val dismissal = async {
            try {
                controls.awaitInterrupted(conversation)
                generation.join()
                assertTrue(locallySaved)
                commitDismissedPauseForFreshInput(queue,
                    authorizeFreshInput = { store.authorize(conversation.toString(), assistant.toString()) },
                    replaceGate = { gate = FreshHumanInputGate(assistant) })
            } finally { controls.endLocalReset(conversation) }
        }
        localSaveStarted.await()
        runCurrent()
        assertFalse(dismissal.isCompleted)
        assertFalse(generation.isCompleted)
        assertFalse(locallySaved)
        assertTrue(queue.state.value.paused)
        assertNull(gate)
        assertEquals(FreshHumanRecoveryStatus.NONE, store.status(conversation.toString(), assistant.toString()))
        assertFalse(remoteResponse.isCompleted)
        assertFalse(remoteReturned)
        assertEquals(if (resetBeforeRegistration) 0 else 1, remoteCalls)

        allowLocalSave.complete(Unit)
        dismissal.await()
        assertTrue(generation.isCompleted)
        assertTrue(locallySaved)
        assertTrue(remoteHoldPreserved)
        assertTrue(automaticHoldPreserved)
        assertFalse(remoteResponse.isCompleted) // No remote response was ever required.
        assertFalse(remoteReturned)
        assertFalse(controls.isResetting(conversation))
        assertFalse(queue.state.value.paused)
        assertEquals(FreshHumanRecoveryStatus.ACTIVE, store.status(conversation.toString(), assistant.toString()))
        assertEquals(oldIds, queue.state.value.messages.map { it.id })
        assertTrue(queue.state.value.messages.all { it.recoveryHeldReason != null })
        assertNull(queue.takeNext())

        val currentGate = checkNotNull(gate)
        val newId = Uuid.random()
        queue.enqueue(listOf(UIMessagePart.Text("explicitly new input")), id = newId,
            freshHumanInputPermit = currentGate.issue(newId))
        val selected = checkNotNull(queue.takeNext { currentGate.permits(it, assistant) })
        var newCalls = 0
        withGatewayInputAdmission(selected, awaitHistory = {}, isBlocked = {
            it == null || !currentGate.permits(it, assistant)
        }) { newCalls++ }
        assertEquals(1, newCalls)
        assertEquals(newId, selected.id)
        assertEquals(oldIds, queue.state.value.messages.map { it.id })
        assertNull(queue.takeNext { currentGate.permits(it, assistant) })
        assertTrue(remoteHoldPreserved)
        assertTrue(automaticHoldPreserved)
        assertTrue(currentCoroutineContext().isActive)
    }
}
