package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** Coroutine cancellation around the same outer settlement boundary used by ChatService. */
class VoiceTurnSettlementTest {
    @Test fun `cancel during preflight commit saves before job completion admits next speech`() = runTest {
        checkWindow(afterModel = false)
    }

    @Test fun `cancel during post model call snapshot saves before job completion admits next speech`() = runTest {
        checkWindow(afterModel = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.checkWindow(afterModel: Boolean) {
        val settlement = VoiceTurnSettlement()
        val entered = CompletableDeferred<Unit>()
        val queue = MessageQueue()
        val next = Uuid.random()
        queue.enqueue(listOf(UIMessagePart.Text("new speech")), id = next)
        val events = mutableListOf<String>()
        val cancellation = VoiceBargeInCancellation(Uuid.random(), "call")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (afterModel) {
                    settlement.enterGeneration()
                    events += "model and journal committed"
                    settlement.returnedFromGeneration()
                }
                entered.complete(Unit)
                awaitCancellation() // either a preflight DB commit or the final call snapshot
            } catch (error: Exception) {
                settlement.acknowledgeOuterCancellation(error, true) {
                    yield() // must remain runnable in the cancelled job
                    events += "history committed"
                    yield()
                    events += "call committed"
                    true
                }
                throw error
            }
        }
        job.invokeOnCompletion { error ->
            events += "job completed"
            if (error != null && !error.isSavedVoiceInterruption()) queue.pause()
        }
        entered.await()
        job.cancel(cancellation)
        job.join()
        assertTrue(cancellation.isSavedVoiceInterruption())
        assertEquals(listOf("history committed", "call committed", "job completed"), events.takeLast(3))
        assertFalse(queue.state.value.paused)
        assertEquals(next, queue.takeNext()!!.id)
        assertNull(queue.takeNext()) // never enqueue the interrupted input again
    }

    @Test fun `inner generation with unresolved cleanup cannot use outer bypass`() = runTest {
        val guard = VoiceTurnSettlement().apply { enterGeneration() }
        var calls = 0
        val cancellation = VoiceBargeInCancellation(Uuid.random(), "call")
        guard.acknowledgeOuterCancellation(cancellation, true) { calls++; true }
        assertEquals(0, calls)
        assertFalse(cancellation.partialSafelySaved)
    }

    @Test fun `existing hold changed owner and failed storage remain unacknowledged`() = runTest {
        for (owner in listOf(true, false)) {
            val cancellation = VoiceBargeInCancellation(Uuid.random(), "call")
            VoiceTurnSettlement().acknowledgeOuterCancellation(cancellation, owner) { false }
            assertFalse(cancellation.partialSafelySaved)
        }
        val cancellation = VoiceBargeInCancellation(Uuid.random(), "call")
        VoiceTurnSettlement().acknowledgeOuterCancellation(cancellation, true) { throw IOException("fixture") }
        assertFalse(cancellation.partialSafelySaved)
    }

    @Test fun `ordinary cancellation has no outer acknowledgement permission`() = runTest {
        var writes = 0
        VoiceTurnSettlement().acknowledgeOuterCancellation(kotlinx.coroutines.CancellationException(), true) {
            writes++; true
        }
        assertEquals(0, writes)
    }

    @Test fun `inner safe acknowledgement is not replaced by outer persistence`() = runTest {
        val cancellation = VoiceBargeInCancellation(Uuid.random(), "call").apply { partialSafelySaved = true }
        VoiceTurnSettlement().acknowledgeOuterCancellation(cancellation, true) { error("already committed") }
        assertTrue(cancellation.partialSafelySaved)
    }

    @Test fun `new speech waits for cancelled predecessor persistence without replay`() = runTest {
        val events = mutableListOf<String>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(600); events += "old saved" } }
        }
        old.cancel()
        val next = launch {
            awaitCancelledVoiceGeneration(old)
            events += "new speech submitted once"
        }
        next.join()
        assertEquals(listOf("old saved", "new speech submitted once"), events)
    }
}
