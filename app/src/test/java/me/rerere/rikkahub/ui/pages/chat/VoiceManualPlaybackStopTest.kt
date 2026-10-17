package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceManualPlaybackStopTest {
    private class Recorder : ASRController {
        override val state = MutableStateFlow(ASRState())
        var disposed = false
        override fun start(onTranscriptChange: (String) -> Unit) { state.value = ASRState(status = ASRStatus.Listening) }
        override fun pauseCapture() {}
        override fun stop() {}
        override fun dispose() { disposed = true }
        fun finish(text: String) { state.value = state.value.copy(voiceTurn = ASRVoiceTurn("synthetic", true, text)) }
    }

    private class Rig(scope: TestScope) {
        val recorders = mutableListOf<Recorder>()
        val spoken = mutableListOf<String>()
        val generated = mutableListOf<String>()
        var stopped = 0
        var cancelledModel = 0
        val voice = VoiceSessionController(scope.backgroundScope, { it.toString() }) {
            generated += it
            CompletableDeferred<String?>()
        }
        fun start(microphone: Boolean = false, text: String? = "synthetic reply",
            speak: suspend (String) -> Unit = { spoken += it; awaitCancellation() },
            stop: () -> Unit = { stopped++ }) = voice.start(
            createAsr = { Recorder().also { recorders += it } }, speak = speak,
            stopSpeaking = stop, initialMicrophoneEnabled = microphone, initialAssistantText = text,
            cancelPendingReply = { cancelledModel++ })
    }

    @Test fun `muted call stops current synthesis keeps text and microphone choice without model action`() = runTest {
        val rig = Rig(this)
        try {
            rig.start(); advanceTimeBy(201); runCurrent()
            val token = requireNotNull(rig.voice.state.value.playbackToken)
            val before = rig.stopped
            assertTrue(rig.voice.requestStopPlayback(token)); runCurrent()
            assertEquals(before + 1, rig.stopped)
            assertNull(rig.voice.state.value.playbackToken)
            assertEquals("synthetic reply", rig.voice.state.value.lastReplyText)
            assertFalse(rig.voice.state.value.microphoneEnabled)
            assertTrue(rig.voice.state.value.speakerEnabled)
            assertTrue(rig.voice.state.value.isActive)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(listOf("synthetic reply"), rig.spoken)
            assertTrue(rig.recorders.isEmpty())
            assertTrue(rig.generated.isEmpty())
            assertEquals(0, rig.cancelledModel)
        } finally { rig.voice.stopAndJoin() }
    }

    @Test fun `speakerphone stop resumes capture after tail and accepts next human utterance once`() = runTest {
        val rig = Rig(this)
        try {
            rig.start(microphone = true); advanceTimeBy(201); runCurrent()
            val token = requireNotNull(rig.voice.state.value.playbackToken)
            assertFalse(rig.voice.state.value.canInterruptPlayback)
            assertTrue(rig.recorders.all { it.disposed })
            assertTrue(rig.voice.requestStopPlayback(token)); runCurrent()
            advanceTimeBy(301); runCurrent()
            val recorder = rig.recorders.last()
            assertFalse(recorder.disposed)
            assertEquals(VoicePhase.Listening, rig.voice.state.value.phase)
            recorder.finish("synthetic next human turn"); runCurrent()
            assertEquals(listOf("synthetic next human turn"), rig.generated)
            assertEquals(0, rig.cancelledModel)
            assertTrue(rig.voice.state.value.isActive)
        } finally { rig.voice.stopAndJoin() }
    }

    @Test fun `repeated old taps and queued completion cannot stop or replay the next response`() = runTest {
        val rig = Rig(this)
        val release = CompletableDeferred<Unit>()
        var releaseOnStop = false
        try {
            rig.start(speak = { rig.spoken += it; if (it == "synthetic reply") release.await() else awaitCancellation() },
                stop = { rig.stopped++; if (releaseOnStop) release.complete(Unit) })
            advanceTimeBy(201); runCurrent()
            val old = requireNotNull(rig.voice.state.value.playbackToken)
            assertTrue(rig.voice.acceptSupplementaryReply(CompletableDeferred<String?>("next response")))
            runCurrent()
            releaseOnStop = true
            assertTrue(rig.voice.requestStopPlayback(old))
            assertTrue(rig.voice.requestStopPlayback(old)) // Both queued before processing.
            val before = rig.stopped
            runCurrent(); advanceTimeBy(301); runCurrent()
            assertEquals(before + 1, rig.stopped)
            val next = requireNotNull(rig.voice.state.value.playbackToken)
            assertNotEquals(old, next)
            assertFalse(rig.voice.requestStopPlayback(old)); runCurrent()
            assertEquals(next, rig.voice.state.value.playbackToken)
            assertEquals(listOf("synthetic reply", "next response"), rig.spoken)
            assertEquals(0, rig.cancelledModel)
        } finally { rig.voice.stopAndJoin() }
    }

    @Test fun `old call token cannot stop a new call even if reply counters restart`() = runTest {
        val rig = Rig(this)
        try {
            rig.start(); advanceTimeBy(201); runCurrent()
            val old = requireNotNull(rig.voice.state.value.playbackToken)
            rig.voice.stopAndJoin()
            assertFalse(rig.voice.requestStopPlayback(old))
            rig.start(); advanceTimeBy(201); runCurrent()
            val next = requireNotNull(rig.voice.state.value.playbackToken)
            assertNotEquals(old, next)
            val before = rig.stopped
            assertFalse(rig.voice.requestStopPlayback(old)); runCurrent()
            assertEquals(before, rig.stopped)
            assertEquals(next, rig.voice.state.value.playbackToken)
        } finally { rig.voice.stopAndJoin() }
    }

    @Test fun `idle stop is a no op and does not cancel a future reply`() = runTest {
        val rig = Rig(this)
        try {
            assertFalse(rig.voice.requestStopPlayback("not-a-playback"))
            rig.start(text = null); advanceTimeBy(201); runCurrent()
            assertFalse(rig.voice.requestStopPlayback("not-a-playback"))
            val pending = CompletableDeferred<String?>()
            assertTrue(rig.voice.acceptSupplementaryReply(pending)); runCurrent()
            assertFalse(rig.voice.requestStopPlayback("not-a-playback"))
            pending.complete("kept future reply"); runCurrent()
            assertEquals(listOf("kept future reply"), rig.spoken)
            assertEquals(0, rig.cancelledModel)
        } finally { rig.voice.stopAndJoin() }
    }

    @Test fun `focus loss winning a stop race neither replays nor ends the call`() = runTest {
        val rig = Rig(this)
        try {
            rig.start(); advanceTimeBy(201); runCurrent()
            val token = requireNotNull(rig.voice.state.value.playbackToken)
            rig.voice.setAudioFocusSuspended(true)
            rig.voice.requestStopPlayback(token); runCurrent()
            assertNull(rig.voice.state.value.playbackToken)
            assertTrue(rig.voice.state.value.isActive)
            assertTrue(rig.voice.state.value.audioFocusSuspended)
            rig.voice.setAudioFocusSuspended(false)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf("synthetic reply"), rig.spoken)
            assertEquals("synthetic reply", rig.voice.state.value.lastReplyText)
            assertEquals(0, rig.cancelledModel)
        } finally { rig.voice.stopAndJoin() }
    }
}
