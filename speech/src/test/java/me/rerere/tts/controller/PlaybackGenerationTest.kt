package me.rerere.tts.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PlaybackGenerationTest {
    @Test fun normalSynthesisReturnsOnlyToCurrentQueue() = runBlocking {
        val generation = PlaybackGeneration()
        assertEquals("audio", generation.awaitCurrent(generation.current()) { "audio" })
    }

    @Test fun stoppedLateNonCancellableSynthesisNeverReachesPlayback() = runBlocking {
        val generation = PlaybackGeneration()
        val token = generation.current()
        val completion = CompletableDeferred<String>()
        var played = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                generation.awaitCurrent(token) { withContext(NonCancellable) { completion.await() } }
                played = true
            } catch (_: CancellationException) {}
        }
        generation.advance()
        completion.complete("late audio")
        job.join()
        assertFalse(played)
    }

    @Test fun cancelledSynthesisDoesNotPlayEvenWithoutExplicitQueueReset() = runBlocking {
        val generation = PlaybackGeneration()
        val completion = CompletableDeferred<String>()
        var played = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            generation.awaitCurrent(generation.current()) { withContext(NonCancellable) { completion.await() } }
            played = true
        }
        job.cancel()
        completion.complete("late audio")
        job.join()
        assertFalse(played)
    }

    @Test fun staleCompletionAndFinallyCannotOverwriteReplacementStatus() = runBlocking {
        val generation = PlaybackGeneration()
        val old = generation.current()
        val replacement = generation.advance()
        var state = "replacement-playing"
        if (generation.isCurrent(old)) state = "old-ended"
        if (generation.isCurrent(old)) state = "not-speaking"
        assertEquals("replacement-playing", state)
        assertEquals("new audio", generation.awaitCurrent(replacement) { "new audio" })
    }

    @Test fun alreadyStaleWorkIsNotStartedAndRepeatedResetNeverReusesToken() = runBlocking {
        val generation = PlaybackGeneration()
        val old = generation.current()
        repeat(3) { generation.advance() }
        var started = false
        try {
            generation.awaitCurrent(old) { started = true }
            fail("Stale work must fail")
        } catch (_: CancellationException) {}
        assertFalse(started)
        assertFalse(generation.isCurrent(old))
    }
}
