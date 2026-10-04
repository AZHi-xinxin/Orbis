package me.rerere.rikkahub.ui.pages.chat

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import org.junit.Assert.*
import org.junit.Test

/** Virtual time and fake recorders only. No device audio, sockets, model or tool execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionRecoveryTest {
    private class Recorder(override val finalTranscriptTimeoutMs: Long = 15_000L) : ASRController {
        override val state = MutableStateFlow(ASRState())
        var paused = false
        var disposed = false
        override fun start(onTranscriptChange: (String) -> Unit) { state.value = ASRState(status = ASRStatus.Listening) }
        override fun pauseCapture() { paused = true }
        override fun stop() { pauseCapture() }
        override fun dispose() { disposed = true; pauseCapture() }
        fun reset() { state.value = state.value.copy(status = ASRStatus.Error, errorMessage = "Connection reset") }
        fun final(text: String) { state.value = state.value.copy(transcript = text, voiceTurn = ASRVoiceTurn("synthetic-turn", true, text)) }
    }
    private class Rig(scope: CoroutineScope, clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
        val recorders = mutableListOf<Recorder>()
        val inputs = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val playback = CompletableDeferred<Unit>()
        val ended = mutableListOf<String?>()
        val outputMuted = mutableListOf<Boolean>()
        var connected = 0
        var opening = 0
        var ttsFailure: Exception? = null
        var finalTimeoutMs = 15_000L
        var factoryFailureAfter: Int? = null
        val voice = VoiceSessionController(scope, { it.toString() }, monotonicTimeMs = clock) {
            inputs += it
            CompletableDeferred<String?>().also { reply -> replies += reply }
        }
        fun start(muted: Boolean = false) = voice.start(
            createAsr = {
                check(factoryFailureAfter?.let { recorders.size < it } != false) { "synthetic factory unavailable" }
                Recorder(finalTimeoutMs).also { recorders += it }
            },
            speak = { spoken += it; ttsFailure?.let { failure -> throw failure }; playback.await() },
            stopSpeaking = {}, setOutputMuted = { outputMuted += it },
            onConnected = { connected++ }, onEnded = { ended += it },
            initialMicrophoneEnabled = !muted, requestOpening = { opening++; null },
        )
    }

    @Test fun silentCallHasNoThirtyMinuteOrOvernightLocalDeadline() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            advanceTimeBy(8 * 60 * 60 * 1000L); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertEquals(1, r.recorders.size)
            assertFalse(r.recorders.single().disposed)
            assertTrue(r.inputs.isEmpty()); assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun transientPauseDiscardsOldPartialAndNeverDuplicatesOpening() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            old.state.value = old.state.value.copy(transcript = "unsubmitted partial", voiceTurn = ASRVoiceTurn("partial"))
            runCurrent()
            r.voice.setAudioFocusSuspended(true)
            assertTrue(old.paused); assertTrue(r.outputMuted.last())
            old.final("must never send")
            runCurrent(); advanceTimeBy(60 * 60 * 1000L); runCurrent()
            assertTrue(old.disposed); assertTrue(r.inputs.isEmpty())
            assertEquals(1, r.recorders.size); assertTrue(r.voice.state.value.isActive)
            r.voice.setAudioFocusSuspended(false); runCurrent()
            assertEquals(2, r.recorders.size)
            assertEquals(1, r.connected); assertEquals(1, r.opening)
            assertTrue(r.inputs.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun quickLossAndGainStillFenceQueuedOldFinalBeforeStartingFreshRecorder() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            r.voice.setAudioFocusSuspended(true)
            old.final("stale final")
            r.voice.setAudioFocusSuspended(false)
            runCurrent(); advanceTimeBy(301); runCurrent()
            assertTrue(old.disposed); assertTrue(r.inputs.isEmpty())
            assertEquals(2, r.recorders.size)
            assertTrue(r.voice.state.value.isActive)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun lossDoesNotReplayInterruptedTtsOrResubmitAcceptedReply() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.replies.single().complete("saved answer"); runCurrent()
            assertEquals(listOf("saved answer"), r.spoken)
            r.voice.setAudioFocusSuspended(true); runCurrent()
            r.voice.setAudioFocusSuspended(false); runCurrent(); advanceTimeBy(301); runCurrent()
            assertEquals(listOf("hello"), r.inputs)
            assertEquals(listOf("saved answer"), r.spoken)
            assertEquals("saved answer", r.voice.state.value.lastReplyText)
            assertTrue(r.voice.state.value.isActive)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun mutedMicAndSpeakerChoicesSurviveFocusResume() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(muted = true); advanceTimeBy(201); runCurrent()
            r.voice.setSpeakerEnabled(false)
            r.voice.setAudioFocusSuspended(true); runCurrent()
            r.voice.setAudioFocusSuspended(false); runCurrent(); advanceTimeBy(301); runCurrent()
            assertTrue(r.recorders.isEmpty())
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertFalse(r.voice.state.value.speakerEnabled)
            assertTrue(r.outputMuted.last())
            assertEquals(1, r.connected)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun idleResetUsesExactlyOneThreeEightSecondReconnectsThenWaitsForManualUnmute() = runTest {
        val r = Rig(backgroundScope) { testScheduler.currentTime }
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            for ((index, delay) in listOf(1000L, 3000L, 8000L).withIndex()) {
                r.recorders.last().reset(); runCurrent()
                assertTrue(r.voice.state.value.reconnecting)
                assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("本轮 ${index + 1}/3"))
                advanceTimeBy(delay - 1); runCurrent()
                assertEquals(index + 1, r.recorders.size)
                advanceTimeBy(1); runCurrent()
                assertEquals(index + 2, r.recorders.size)
            }
            assertEquals(1, r.connected); assertEquals(1, r.opening)
            assertTrue(r.inputs.isEmpty()); assertTrue(r.spoken.isEmpty())
            r.recorders.last().reset(); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertTrue(r.ended.isEmpty())
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_LISTEN_NETWORK_RESET"))
            advanceTimeBy(100_000); runCurrent(); assertEquals(4, r.recorders.size)
            r.voice.setMicrophoneEnabled(true); runCurrent()
            assertEquals(5, r.recorders.size)
            assertEquals(1, r.connected); assertEquals(1, r.opening)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun eightHourCallRecoversFromHalfHourlyIdleResetsWithoutExhaustingLifetimeBudget() = runTest {
        val r = Rig(backgroundScope) { testScheduler.currentTime }
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            repeat(16) { index ->
                advanceTimeBy(30 * 60 * 1000L); runCurrent()
                r.recorders.last().reset(); runCurrent()
                assertTrue(r.voice.state.value.isActive)
                assertTrue(r.voice.state.value.reconnecting)
                assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("本轮 1/3"))
                advanceTimeBy(1_000); runCurrent()
                assertEquals(index + 2, r.recorders.size)
                assertTrue(r.voice.state.value.isActive)
                assertFalse(r.voice.state.value.reconnecting)
            }
            assertEquals(1, r.connected); assertEquals(1, r.opening)
            assertTrue(r.inputs.isEmpty()); assertTrue(r.spoken.isEmpty()); assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun resetDuringAnUnsubmittedUtteranceDoesNotAutomaticallyReconnectOrSubmitPartial() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            old.state.value = old.state.value.copy(transcript = "partial private words", voiceTurn = ASRVoiceTurn("partial"))
            runCurrent(); old.reset(); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("本句未提交"))
            assertFalse(r.voice.state.value.recoveryNotice.orEmpty().contains("private words"))
            assertTrue(r.ended.isEmpty())
            assertEquals("", r.voice.state.value.transcript)
            assertEquals(1, r.recorders.size); assertTrue(r.inputs.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun stoppingDuringRetryAndLateFocusGainCannotReviveMicrophone() = runTest {
        val r = Rig(backgroundScope)
        r.start(); advanceTimeBy(201); runCurrent()
        r.recorders.single().reset(); runCurrent()
        r.voice.stopAndJoin()
        r.voice.setAudioFocusSuspended(false)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(VoicePhase.Off, r.voice.state.value.phase)
        assertEquals(1, r.recorders.size)
        assertTrue(r.recorders.all { it.disposed })
        assertEquals(1, r.ended.size)
    }

    @Test fun finalTranscriptTimeoutIsSpecificAndDoesNotResubmitOrReconnect() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            old.state.value = old.state.value.copy(transcript = "unconfirmed partial", voiceTurn = ASRVoiceTurn("partial", true))
            runCurrent(); advanceTimeBy(15_001); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_FINAL_TIMEOUT"))
            assertEquals(1, r.recorders.size); assertTrue(r.inputs.isEmpty())
            assertTrue(old.disposed)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun batchFinalFailureRetainsCallAndOnlyManualUnmuteStartsFreshCapture() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.finalTimeoutMs = 60_000L
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            old.state.value = old.state.value.copy(transcript = "partial", voiceTurn = ASRVoiceTurn("old", true))
            runCurrent()
            old.state.value = old.state.value.copy(errorMessage = "ASR batch HTTP 503 private payload")
            runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertFalse(r.voice.state.value.replyBlocked)
            assertTrue(old.disposed)
            assertTrue(r.ended.isEmpty()); assertTrue(r.inputs.isEmpty())
            assertEquals("", r.voice.state.value.transcript)
            assertFalse(r.voice.state.value.recoveryNotice.orEmpty().contains("private"))
            old.final("late old result"); runCurrent()
            r.voice.setAudioFocusSuspended(true); runCurrent()
            r.voice.setAudioFocusSuspended(false); runCurrent(); advanceTimeBy(301); runCurrent()
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertEquals(1, r.recorders.size)
            assertTrue(r.inputs.isEmpty())
            r.voice.setMicrophoneEnabled(true); runCurrent()
            assertEquals(2, r.recorders.size)
            r.recorders.last().final("human repeated new sentence"); runCurrent()
            assertEquals(listOf("human repeated new sentence"), r.inputs)
            assertEquals(1, r.connected); assertEquals(1, r.opening)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun batchFinalWaitUsesItsOwnSixtySecondBudgetNotRealtimeFifteenSeconds() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.finalTimeoutMs = 60_000L
            r.start(); advanceTimeBy(201); runCurrent()
            val old = r.recorders.single()
            old.state.value = old.state.value.copy(voiceTurn = ASRVoiceTurn("old", true))
            runCurrent(); advanceTimeBy(15_001); runCurrent()
            assertTrue(r.voice.state.value.microphoneEnabled)
            assertEquals(VoicePhase.Transcribing, r.voice.state.value.phase)
            assertFalse(old.disposed)
            advanceTimeBy(45_000); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(old.disposed)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_FINAL_TIMEOUT"))
            assertTrue(r.inputs.isEmpty()); assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun recorderRecreationFailurePausesConnectedCallAndManualResumeKeepsAcceptedInput() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.factoryFailureAfter = 1
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("accepted before recorder failed"); runCurrent()
            assertEquals(listOf("accepted before recorder failed"), r.inputs)
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_START_FAILED"))
            assertTrue(r.ended.isEmpty())
            assertEquals(1, r.replies.size)
            r.voice.setAudioFocusSuspended(true); runCurrent()
            r.voice.setAudioFocusSuspended(false); runCurrent(); advanceTimeBy(301); runCurrent()
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_MANUAL_RESUME_REQUIRED"))
            r.factoryFailureAfter = null
            r.voice.setMicrophoneEnabled(true); runCurrent()
            assertEquals(2, r.recorders.size)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_MANUAL_RESUMED"))
            assertFalse(r.voice.state.value.recoveryNotice.orEmpty().contains("ASR_MANUAL_RESUME_REQUIRED"))
            assertEquals(listOf("accepted before recorder failed"), r.inputs)
            assertEquals(1, r.connected)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun newSessionCannotReceiveOldRetryOrOldFocusFence() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().reset(); runCurrent()
            r.voice.stopAndJoin()
            r.start(); advanceTimeBy(201); runCurrent()
            advanceTimeBy(20_000); runCurrent()
            assertEquals(2, r.recorders.size)
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.recorders.last().disposed)
            assertEquals(2, r.connected); assertEquals(2, r.opening)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun ttsNetworkFailurePreservesCallAndTextWithoutPlaybackRetry() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.ttsFailure = IOException("Connection reset secret-provider-body")
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.replies.single().complete("answer remains visible"); runCurrent(); advanceTimeBy(301); runCurrent()
            assertTrue(r.voice.state.value.isActive); assertTrue(r.ended.isEmpty())
            assertEquals("answer remains visible", r.voice.state.value.lastReplyText)
            assertEquals(listOf("answer remains visible"), r.spoken)
            assertTrue(r.voice.state.value.recoveryNotice.orEmpty().contains("TTS_NETWORK_RESET"))
            assertFalse(r.voice.state.value.recoveryNotice.orEmpty().contains("secret"))
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun modelFailurePausesWithoutEndingCallAndIsNeverResubmitted() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.replies.single().completeExceptionally(IOException("Connection reset private-tool-result"))
            runCurrent(); advanceTimeBy(100_000); runCurrent()
            assertEquals(listOf("hello"), r.inputs); assertEquals(1, r.replies.size)
            assertTrue(r.voice.state.value.isActive)
            assertTrue(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.replyNotice.orEmpty().contains("MODEL_NETWORK_RESET"))
            assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("private-tool-result"))
            assertTrue(r.ended.isEmpty())
            assertTrue(r.recorders.all { it.disposed })
        } finally { r.voice.stopAndJoin() }
    }
}
