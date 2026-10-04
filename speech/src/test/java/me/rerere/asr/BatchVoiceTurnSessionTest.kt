package me.rerere.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BatchVoiceTurnSessionTest {
    @Test fun silenceProducesNoRequests() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = AtomicInteger()
        val session = BatchVoiceTurnSession(scope, 16_000, { requests.incrementAndGet(); "unexpected" })
        try {
            repeat(500) { session.acceptPcm(pcmFrame(0)) }
            assertNull(session.state.value.voiceTurn.itemId)
            session.pauseCapture()
            assertEquals(0, requests.get())
        } finally { session.cancel(); scope.cancel() }
    }

    @Test fun endpointWaitsForFinalAndEmptyTranscriptCompletesWithoutInventedText() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val response = CompletableDeferred<String>()
        val session = BatchVoiceTurnSession(scope, 16_000, { response.await() })
        try {
            repeat(10) { session.acceptPcm(pcmFrame(1_500)) }
            repeat(150) { session.acceptPcm(pcmFrame(0)) }
            assertTrue(session.state.value.voiceTurn.speechEnded)
            assertFalse(session.state.value.voiceTurn.isComplete)
            session.pauseCapture() // Normal host endpoint ACK must not cancel the pending final.
            response.complete("")
            val result = withTimeout(2_000) { session.state.first { it.voiceTurn.isComplete } }
            assertEquals("", result.voiceTurn.finalText)
        } finally { session.cancel(); scope.cancel() }
    }

    @Test fun longSpeechHasSerialRequestsAndOnlyOneFinalAfterAllSegments() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val calls = AtomicInteger()
        val session = BatchVoiceTurnSession(scope, 16_000, {
            assertEquals(1, active.incrementAndGet())
            val number = calls.incrementAndGet()
            if (number == 1) { entered.complete(Unit); release.await() }
            active.decrementAndGet()
            "segment-$number"
        }, segmentMs = 1_000)
        try {
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            withTimeout(2_000) { entered.await() }
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            assertEquals(1, calls.get())
            assertFalse(session.state.value.voiceTurn.speechEnded)
            repeat(150) { session.acceptPcm(pcmFrame(0)) }
            assertFalse(session.state.value.voiceTurn.isComplete)
            release.complete(Unit)
            val result = withTimeout(2_000) { session.state.first { it.voiceTurn.isComplete } }
            assertEquals("segment-1 segment-2", result.voiceTurn.finalText)
            assertEquals(2, calls.get())
        } finally { session.cancel(); scope.cancel() }
    }

    @Test fun queueOverflowIsExplicitFailureNotPartialFinal() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val session = BatchVoiceTurnSession(scope, 16_000, { entered.complete(Unit); delay(10_000); "late" },
            segmentMs = 1_000, queueCapacity = 1)
        try {
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            withTimeout(2_000) { entered.await() }
            repeat(100) { session.acceptPcm(pcmFrame(1_500)) }
            assertEquals(ASRStatus.Error, session.state.value.status)
            assertTrue(session.state.value.errorMessage.orEmpty().contains("ASR_BATCH_BACKLOG"))
            assertFalse(session.state.value.voiceTurn.isComplete)
        } finally { session.cancel(); scope.cancel() }
    }

    @Test fun muteDropsCaptureAndFencesLateNonCooperativeResult() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val session = BatchVoiceTurnSession(scope, 16_000, {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); returned.complete(Unit) }
            "must not return after mute"
        }, segmentMs = 1_000)
        try {
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            withTimeout(2_000) { entered.await() }
            session.pauseCapture()
            release.complete(Unit)
            withTimeout(2_000) { returned.await() }
            delay(30)
            assertEquals(ASRStatus.Idle, session.state.value.status)
            assertEquals("", session.state.value.transcript)
            assertFalse(session.state.value.voiceTurn.isComplete)
        } finally { release.complete(Unit); session.cancel(); scope.cancel() }
    }

    @Test fun failedLaterSegmentNeverCommitsEarlierPartialTranscript() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val session = BatchVoiceTurnSession(scope, 16_000, {
            if (calls.incrementAndGet() == 1) "first fragment" else error("private response must stay private")
        }, segmentMs = 1_000)
        try {
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            withTimeout(2_000) { session.state.first { it.transcript.isNotEmpty() } }
            repeat(50) { session.acceptPcm(pcmFrame(1_500)) }
            val result = withTimeout(2_000) { session.state.first { it.status == ASRStatus.Error } }
            assertFalse(result.voiceTurn.isComplete)
            assertFalse(result.errorMessage.orEmpty().contains("private"))
        } finally { session.cancel(); scope.cancel() }
    }

    @Test fun disposedEndpointRejectsLateOldFinalWhileNewTurnCompletes() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val waiting = CompletableDeferred<Continuation<String>>()
        val returned = CompletableDeferred<Unit>()
        val old = BatchVoiceTurnSession(scope, 16_000, {
            // Deliberately non-cancellable transport to exercise the owner fence itself.
            val text = suspendCoroutine<String> { waiting.complete(it) }
            returned.complete(Unit)
            text
        })
        val next = BatchVoiceTurnSession(scope, 16_000, { "new turn" })
        try {
            repeat(10) { old.acceptPcm(pcmFrame(1_500)) }
            repeat(150) { old.acceptPcm(pcmFrame(0)) }
            val continuation = withTimeout(2_000) { waiting.await() }
            val oldId = old.state.value.voiceTurn.itemId
            old.cancel()
            repeat(10) { next.acceptPcm(pcmFrame(1_500)) }
            repeat(150) { next.acceptPcm(pcmFrame(0)) }
            val fresh = withTimeout(2_000) { next.state.first { it.voiceTurn.isComplete } }
            assertNotEquals(oldId, fresh.voiceTurn.itemId)
            continuation.resume("old turn must not leak")
            withTimeout(2_000) { returned.await() }
            delay(30)
            assertEquals("", old.state.value.transcript)
            assertFalse(old.state.value.voiceTurn.isComplete)
            assertEquals("new turn", next.state.value.voiceTurn.finalText)
        } finally { old.cancel(); next.cancel(); scope.cancel() }
    }
}
