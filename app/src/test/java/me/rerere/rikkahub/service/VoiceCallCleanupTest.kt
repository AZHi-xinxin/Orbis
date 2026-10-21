package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceCallCleanupTest {
    @Test fun joinFailuresStillArchiveAndFinishButCannotReleaseAnUnconfirmedOwner() = runTest {
        val cleanup = VoiceCallCleanup()
        val visited = mutableListOf<String>()
        completeVoiceCallShutdown(cleanup,
            joinStartup = { visited += "startup"; throw CancellationException("private startup detail") },
            joinVoice = { visited += "voice"; error("private recorder detail") },
            releaseConfirmed = { false },
            releaseOwner = { fail("Unconfirmed native resources must keep their owner") },
            persistEnd = { failures ->
                visited += "archive"
                assertEquals(listOf("VOICE_STARTUP_JOIN", "VOICE_SESSION_JOIN", "VOICE_SHUTDOWN_UNCONFIRMED"), failures)
            },
            finished = { visited += "finished" },
        )
        assertEquals(listOf("startup", "voice", "archive", "finished"), visited)
        assertFalse(cleanup.failures.joinToString().contains("private"))
    }

    @Test fun completedOwnerCanReleaseEvenAfterJoinThrowsButArchiveFailureStillFinishes() = runTest {
        val cleanup = VoiceCallCleanup()
        val visited = mutableListOf<String>()
        completeVoiceCallShutdown(cleanup,
            joinStartup = { throw CancellationException("synthetic already completed join") },
            joinVoice = {}, releaseConfirmed = { true },
            releaseOwner = { visited += "released" },
            persistEnd = { visited += "archive"; error("synthetic persistence failure") },
            finished = { visited += "finished" },
        )
        assertEquals(listOf("released", "archive", "finished"), visited)
        assertEquals(listOf("VOICE_STARTUP_JOIN", "CALL_END_PERSIST_FAILED"), cleanup.failures)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pendingJoinDoesNotPretendToReleaseANativeOwnerOrFinishEarly() = runTest {
        val joined = CompletableDeferred<Unit>()
        val visited = mutableListOf<String>()
        val cleanupJob = launch {
            completeVoiceCallShutdown(VoiceCallCleanup(), joinStartup = {}, joinVoice = { joined.await() },
                releaseConfirmed = { joined.isCompleted }, releaseOwner = { visited += "released" },
                persistEnd = { visited += "archive" }, finished = { visited += "finished" })
        }
        runCurrent()
        assertTrue(visited.isEmpty())
        assertFalse(cleanupJob.isCompleted)
        joined.complete(Unit)
        cleanupJob.join()
        assertEquals(listOf("released", "archive", "finished"), visited)
    }

    @Test fun readinessReadFailureStillArchivesAndFinishesWithoutReleasingOwner() = runTest {
        val cleanup = VoiceCallCleanup()
        var archived = false
        var finished = false
        completeVoiceCallShutdown(cleanup, joinStartup = {}, joinVoice = {},
            releaseConfirmed = { error("synthetic readiness failure") },
            releaseOwner = { fail("No confirmation means no release") },
            persistEnd = { archived = true }, finished = { finished = true })
        assertTrue(archived)
        assertTrue(finished)
        assertEquals(listOf("VOICE_SHUTDOWN_CONFIRM", "VOICE_SHUTDOWN_UNCONFIRMED"), cleanup.failures)
    }

    @Test fun eachReleaseRunsAfterFailuresAndOnlyFixedStageCodesEscape() {
        val cleanup = VoiceCallCleanup()
        val visited = mutableListOf<String>()
        for (step in listOf("CAPTURE", "PLAYBACK", "ROUTE", "FOCUS", "SERVICE", "END_STATE")) {
            cleanup.attempt(step) {
                visited += step
                if (step == "CAPTURE") throw CancellationException("synthetic private detail")
                if (step == "ROUTE") error("synthetic provider URL and content")
            }
        }
        assertEquals(listOf("CAPTURE", "PLAYBACK", "ROUTE", "FOCUS", "SERVICE", "END_STATE"), visited)
        assertEquals(listOf("CAPTURE", "ROUTE"), cleanup.failures)
        val error = voiceCallCleanupError(null, cleanup.failures)!!
        assertTrue(error.contains("CALL_CLEANUP:CAPTURE,ROUTE"))
        assertFalse(error.contains("private"))
        assertFalse(error.contains("provider"))
    }

    @Test fun nestedFailuresStayOrderedAndDoNotEraseTheOriginalEndReason() {
        val cleanup = VoiceCallCleanup()
        cleanup.include(listOf("AUDIO_DEVICE_RELEASE", "AUDIO_MODE_RELEASE"))
        cleanup.attempt("AUDIO_DEVICE_RELEASE") { error("duplicate") }
        val snapshot = cleanup.failures
        cleanup.include(listOf("VOICE_FOREGROUND_REMOVE"))
        assertEquals(listOf("AUDIO_DEVICE_RELEASE", "AUDIO_MODE_RELEASE"), snapshot)
        assertEquals(listOf("AUDIO_DEVICE_RELEASE", "AUDIO_MODE_RELEASE", "VOICE_FOREGROUND_REMOVE"), cleanup.failures)
        val message = voiceCallCleanupError("Original call failure", cleanup.failures)!!
        assertTrue(message.startsWith("Original call failure\n"))
        assertTrue(message.contains("VOICE_FOREGROUND_REMOVE"))
        assertNull(voiceCallCleanupError(null, emptyList()))
        assertEquals("original", voiceCallCleanupError("original", emptyList()))
    }

    @Test fun pendingArchiveAndUnconfirmedReleaseDoNotMakeEndedCallActive() {
        val ended = OrbisVoiceCallState(callId = "synthetic-call", endedAtMillis = 123,
            ending = true, error = voiceCallCleanupError(null, listOf("AUDIO_MODE_RELEASE_UNCONFIRMED")))
        assertFalse(ended.isActive)
        assertTrue(ended.ending)
        assertTrue(ended.error!!.contains("AUDIO_MODE_RELEASE_UNCONFIRMED"))
        assertFalse(ended.copy(ending = false).isActive)
        assertTrue(OrbisVoiceCallState(callId = "new-call").isActive)
    }
}
