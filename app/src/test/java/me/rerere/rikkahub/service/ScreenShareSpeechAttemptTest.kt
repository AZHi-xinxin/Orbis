package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenShareSpeechAttemptTest {
    @Test fun providerFailureIsLocalAndStopsOnlyOwnedSegment() = runBlocking {
        var stopped = 0
        assertFalse(runScreenShareSpeechAttempt(stop = { stopped++ }) { error("synthetic provider failure") })
        assertEquals(1, stopped)
    }
    @Test fun failedResultStopsWithoutThrowingIntoVoiceSession() = runBlocking {
        var stopped = 0
        assertFalse(runScreenShareSpeechAttempt(stop = { stopped++ }) { false })
        assertEquals(1, stopped)
    }
    @Test fun hungSynthesisIsBoundedAndDoesNotFailTextOrCapture() = runBlocking {
        var stopped = 0
        assertFalse(runScreenShareSpeechAttempt(timeoutMillis = 1, stop = { stopped++ }) { awaitCancellation() })
        assertEquals(1, stopped)
    }
    @Test fun realSessionCancellationIsNotSwallowed() = runBlocking {
        var stopped = 0
        val cancellation = CancellationException("ended synthetic session")
        try {
            runScreenShareSpeechAttempt(stop = { stopped++ }) { throw cancellation }
            fail("session cancellation must propagate")
        } catch (caught: CancellationException) {
            // Coroutine debug stack recovery may copy the exception and retain the original cause.
            assertEquals(cancellation.message, caught.message)
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.take(8).any { it === cancellation })
        }
        assertEquals(1, stopped)
    }
    @Test fun successfulSpeechDoesNotChangeOtherAudio() = runBlocking {
        var stopped = 0
        assertTrue(runScreenShareSpeechAttempt(stop = { stopped++ }) { true })
        assertEquals(0, stopped)
    }
}
