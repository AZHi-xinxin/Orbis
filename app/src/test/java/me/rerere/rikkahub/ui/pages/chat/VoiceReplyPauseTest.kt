package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.util.HttpException
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import me.rerere.rikkahub.service.MessageQueuePausedException
import org.junit.Assert.*
import org.junit.Test

/** Local fakes and virtual time: no model, tool, microphone or network requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReplyPauseTest {
    private class Recorder : ASRController {
        override val state = MutableStateFlow(ASRState())
        var disposed = false
        var paused = false
        override fun start(onTranscriptChange: (String) -> Unit) { state.value = ASRState(status = ASRStatus.Listening) }
        override fun pauseCapture() { paused = true }
        override fun stop() { pauseCapture() }
        override fun dispose() { disposed = true; pauseCapture() }
        fun final(text: String) {
            state.value = state.value.copy(transcript = text, voiceTurn = ASRVoiceTurn("turn", true, text))
        }
        fun reset() { state.value = state.value.copy(status = ASRStatus.Error, errorMessage = "Connection reset") }
    }

    private class Rig(scope: CoroutineScope) {
        val recorders = mutableListOf<Recorder>()
        val inputs = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val muted = mutableListOf<Boolean>()
        val ended = mutableListOf<String?>()
        val pauses = mutableListOf<Boolean>()
        var enqueueFailure: Exception? = null
        var openingFailure: Exception? = null
        var openings = 0
        val voice = VoiceSessionController(scope, { it.toString() }) {
            inputs += it
            enqueueFailure?.let { error -> throw error }
            CompletableDeferred<String?>().also(replies::add)
        }

        fun start() = voice.start(
            createAsr = { Recorder().also(recorders::add) },
            speak = { spoken += it }, stopSpeaking = {}, setOutputMuted = { muted += it },
            onEnded = { ended += it }, onReplyPauseChanged = { pauses += it },
            requestOpening = { openings++; openingFailure?.let { throw it }; null },
        )

        fun failReply(error: Exception = HttpException("private upstream payload", httpStatus = 409)) {
            replies.last().completeExceptionally(error)
        }
    }

    @Test fun conflictPausesAudioButKeepsTheCallAndNeverRepeatsAcceptedInput() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("accepted once"); runCurrent()
            val idle = r.recorders.last()
            r.failReply(); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.isActive)
            assertTrue(idle.paused && idle.disposed)
            assertTrue(r.muted.last())
            assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("private upstream payload"))
            idle.final("late old capture"); runCurrent()
            advanceTimeBy(10 * 60 * 1000L); runCurrent()
            assertEquals(listOf("accepted once"), r.inputs)
            assertEquals(2, r.recorders.size)
            assertEquals(listOf(true), r.pauses)
            assertTrue(r.spoken.isEmpty()); assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun pendingToolCanWaitBeyondFiveMinutesAndCompleteWithoutLocalHangup() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("wait for tool"); runCurrent()
            advanceTimeBy(6 * 60 * 1000L); runCurrent()
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.replyBlocked)
            assertEquals(1, r.voice.state.value.pendingReplies)
            assertFalse(r.replies.single().isCancelled)
            r.replies.single().complete("completed result"); runCurrent()
            assertEquals(listOf("completed result"), r.spoken)
            assertEquals(listOf("wait for tool"), r.inputs)
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun queuePauseRequiresExplicitSuccessfulCheckAndResumesOnlyFreshCapture() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("kept by queue"); runCurrent()
            r.failReply(MessageQueuePausedException()); runCurrent()
            assertTrue(r.voice.state.value.replyNotice.orEmpty().contains("MODEL_QUEUE_PAUSED"))
            assertTrue(r.voice.requestReplyResume { false }); runCurrent()
            advanceTimeBy(301); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking)
            assertEquals(2, r.recorders.size)
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertEquals(3, r.recorders.size)
            assertEquals(1, r.openings)
            assertEquals(listOf("kept by queue"), r.inputs)
            r.recorders.last().final("fresh utterance"); runCurrent()
            assertEquals(listOf("kept by queue", "fresh utterance"), r.inputs)
            assertTrue(r.spoken.isEmpty()); assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun focusGainAndMuteControlsCannotReleaseReplyHold() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.failReply(); runCurrent()
            r.voice.setAudioFocusSuspended(true)
            r.voice.setMicrophoneEnabled(false); r.voice.setMicrophoneEnabled(true)
            r.voice.setSpeakerEnabled(false); r.voice.setSpeakerEnabled(true)
            r.voice.setAudioFocusSuspended(false)
            runCurrent(); advanceTimeBy(301); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertTrue(r.muted.last())
            assertEquals(2, r.recorders.size)
            r.voice.setAudioFocusSuspended(true); runCurrent()
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            advanceTimeBy(301); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.audioFocusSuspended)
            assertTrue(r.muted.last()); assertEquals(2, r.recorders.size)
            r.voice.setAudioFocusSuspended(false); runCurrent()
            assertEquals(3, r.recorders.size)
            assertEquals(listOf("hello"), r.inputs)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun lateCheckCannotHoldUpHangupOrUnlockAnotherSession() = runTest {
        val r = Rig(backgroundScope)
        val gate = CompletableDeferred<Boolean>()
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("first call"); runCurrent()
            r.failReply(); runCurrent()
            assertTrue(r.voice.requestReplyResume { withContext(NonCancellable) { gate.await() } })
            runCurrent()
            assertFalse(r.voice.requestReplyResume { true })
            r.voice.stopAndJoin()
            assertEquals(VoicePhase.Off, r.voice.state.value.phase)
            assertEquals(1, r.ended.size)
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.last().final("second call"); runCurrent()
            r.failReply(); runCurrent()
            gate.complete(true); runCurrent(); advanceTimeBy(301); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertEquals(4, r.recorders.size)
            assertEquals(listOf("first call", "second call"), r.inputs)
            assertEquals(1, r.ended.size)
        } finally { gate.complete(false); r.voice.stopAndJoin() }
    }

    @Test fun replyPauseCancelsScheduledAsrRetryAndCannotBeUndoneByItsLateEvent() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.recorders.last().reset(); runCurrent()
            assertTrue(r.voice.state.value.reconnecting)
            r.failReply(); runCurrent()
            advanceTimeBy(20_000); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.reconnecting)
            assertEquals(2, r.recorders.size)
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun synchronousEnqueueAndOpeningFailuresAlsoPreserveCall() = runTest {
        for (opening in listOf(false, true)) {
            val r = Rig(backgroundScope)
            try {
                if (opening) r.openingFailure = IllegalStateException("private opening failure")
                else r.enqueueFailure = MessageQueuePausedException()
                r.start(); advanceTimeBy(201); runCurrent()
                if (!opening) { r.recorders.single().final("accepted locally"); runCurrent() }
                assertTrue(r.voice.state.value.isActive)
                assertTrue(r.voice.state.value.replyBlocked)
                assertTrue(r.recorders.all { it.disposed })
                assertTrue(r.ended.isEmpty())
                assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("private opening failure"))
            } finally { r.voice.stopAndJoin() }
        }
    }

    @Test fun failedRecoveryCheckStaysPausedAndDoesNotExposeItsError() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start(); advanceTimeBy(201); runCurrent()
            r.recorders.single().final("hello"); runCurrent()
            r.failReply(); runCurrent()
            assertTrue(r.voice.requestReplyResume { error("private storage details") }); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking)
            assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("private storage details"))
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }
}
