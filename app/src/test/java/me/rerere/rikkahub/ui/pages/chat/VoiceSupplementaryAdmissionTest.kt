package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/** No camera/model calls. The factory represents the exact moment a frame enters ChatService. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSupplementaryAdmissionTest {
    private class Recorder : ASRController {
        override val state = MutableStateFlow(ASRState())
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun pauseCapture() = Unit
        override fun stop() = Unit
        override fun dispose() = Unit
        fun speech(ended: Boolean = false) {
            state.value = state.value.copy(transcript = "synthetic speech",
                voiceTurn = ASRVoiceTurn("utterance", ended, "synthetic speech"))
        }
    }

    private class Rig(scope: CoroutineScope) {
        val recorders = mutableListOf<Recorder>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        var submittedFrames = 0
        val voice = VoiceSessionController(scope, { it.toString() }) {
            CompletableDeferred<String?>().also(replies::add)
        }
        fun start(muted: Boolean = false) = voice.start(
            createAsr = { Recorder().also(recorders::add) },
            speak = { spoken += it }, stopSpeaking = {}, initialMicrophoneEnabled = !muted,
        )
        fun frame() = CompletableDeferred<String?>().also { submittedFrames++ }
    }

    @Test fun frameCapturedDuringSpeechIsRejectedBeforeAnyQueueOrModelWork() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().speech(); runCurrent()
            assertEquals(VoicePhase.Listening, r.voice.state.value.phase)
            assertTrue(r.voice.state.value.humanTurnInProgress)
            val result = async { r.voice.enqueueSupplementaryReply({ true }, r::frame) }
            runCurrent()
            assertNull(result.await())
            assertEquals(0, r.submittedFrames)
            assertFalse(r.voice.state.value.replyBlocked)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun eventAdmissionReadsLiveRecorderEvenBeforeItsSpeechEventIsConsumed() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                r.voice.enqueueSupplementaryReply({ true }, r::frame)
            }
            // Admission was queued first, but the native recorder already reports speech.
            r.recorders.single().speech()
            runCurrent()
            assertNull(result.await())
            assertEquals(0, r.submittedFrames)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun pendingHumanReplyAndPausedReplyBothRejectCameraFactory() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().speech(ended = true); runCurrent()
            assertEquals(1, r.voice.state.value.pendingReplies)
            val busy = async { r.voice.enqueueSupplementaryReply({ true }, r::frame) }
            runCurrent(); assertNull(busy.await())
            r.replies.single().completeExceptionally(IllegalStateException("synthetic unknown tool receipt"))
            runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            val paused = async { r.voice.enqueueSupplementaryReply({ true }, r::frame) }
            runCurrent(); assertNull(paused.await())
            assertEquals(0, r.submittedFrames)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun cancelledCaptureOrStaleCallNeverCreatesAFrameRequest() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(muted = true); advanceTimeBy(201); runCurrent()
            val cancelled = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                r.voice.enqueueSupplementaryReply({ true }, r::frame)
            }
            cancelled.cancel(); runCurrent()
            assertTrue(cancelled.isCancelled)
            val stale = async { r.voice.enqueueSupplementaryReply({ false }, r::frame) }
            runCurrent(); assertNull(stale.await())
            assertEquals(0, r.submittedFrames)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun admittedFrameUsesOrderedPlaybackAndRealFailureStillPausesAudio() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(muted = true); advanceTimeBy(201); runCurrent()
            val success = CompletableDeferred<String?>()
            val accepted = async { r.voice.enqueueSupplementaryReply({ true }) { r.submittedFrames++; success } }
            runCurrent(); assertSame(success, accepted.await())
            success.complete("synthetic camera reply"); runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("synthetic camera reply"), r.spoken)
            val failure = CompletableDeferred<String?>()
            val second = async { r.voice.enqueueSupplementaryReply({ true }) { r.submittedFrames++; failure } }
            runCurrent(); assertSame(failure, second.await())
            failure.completeExceptionally(IllegalStateException("synthetic unknown tool result")); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.isActive)
            assertEquals(2, r.submittedFrames)
        } finally { r.voice.stopAndJoin() }
    }
}
