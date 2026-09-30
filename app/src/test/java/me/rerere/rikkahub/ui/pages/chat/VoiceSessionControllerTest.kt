package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueue
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionControllerTest {
    private class FakeAsr(
        override val supportsConcurrentPlayback: Boolean = false,
        private val echoActive: Boolean = false,
        private val releaseEchoOnPause: Boolean = false,
    ) : ASRController {
        override val state = MutableStateFlow(ASRState())
        var disposed = false
        var paused = false
        var emitEndedOnPause = false
        var emitErrorOnPause = false
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening,
                echoCancellationAvailable = echoActive, echoCancellationActive = echoActive)
        }
        override fun pauseCapture() {
            paused = true
            if (releaseEchoOnPause) state.value = state.value.copy(echoCancellationActive = false)
            if (emitEndedOnPause) state.value = state.value.copy(voiceTurn = ASRVoiceTurn("closing-recorder", true))
            if (emitErrorOnPause) state.value = state.value.copy(errorMessage = "intentional capture close")
        }
        override fun stop() {}
        override fun dispose() { disposed = true; if (releaseEchoOnPause) pauseCapture() }
        fun begin() {
            state.value = state.value.copy(voiceTurn = ASRVoiceTurn("a"))
        }
        fun end(text: String? = null) {
            state.value = state.value.copy(voiceTurn = ASRVoiceTurn("a", true, text))
        }
    }

    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = MessageQueue()
        val asrs = mutableListOf<FakeAsr>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val outputMutes = mutableListOf<Boolean>()
        val interrupted = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
        val playback = CompletableDeferred<Unit>()
        var stopCount = 0
        var failPlayback = false
        val voice = VoiceSessionController(scope, getString = { it.toString() }) { text ->
            assertTrue(asrs.last().disposed)
            CompletableDeferred<String?>().also {
                replies.add(it)
                queue.enqueue(listOf(UIMessagePart.Text(text)), reply = it)
            }
        }
        fun start(duplex: Boolean = false, echoActive: Boolean = false,
            headset: () -> Boolean = { false }, microphoneEnabled: Boolean = true,
            speakerEnabled: Boolean = true, initialText: String? = null,
            releaseEchoOnPause: Boolean = false) = voice.start(
            { FakeAsr(duplex, echoActive, releaseEchoOnPause).also { asrs.add(it) } },
            {
                assertTrue(asrs.all { asr -> asr.disposed || (duplex && (echoActive || headset())) })
                spoken.add(it)
                if (failPlayback) error("playback failed")
                playback.await()
            },
            { stopCount++ },
            initialMicrophoneEnabled = microphoneEnabled,
            initialSpeakerEnabled = speakerEnabled,
            initialAssistantText = initialText,
            cancelPendingReply = { interrupted.add(it) },
            isHeadsetConnected = headset,
            setOutputMuted = { outputMutes.add(it) },
        )
        suspend fun recorder(after: FakeAsr? = null): FakeAsr {
            awaitCondition {
                asrs.lastOrNull()?.let { it !== after && !it.disposed && it.state.value.status == ASRStatus.Listening } == true
            }
            return asrs.last()
        }
        fun close() { voice.stop(); scope.cancel() }
    }

    @Test fun `without TTS replies do not interrupt listening`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
            )
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 }
            assertFalse(second.disposed)
            assertFalse(second.paused)
            assertEquals(VoicePhase.Listening, rig.voice.state.value.phase)
            second.end("second")
            rig.recorder(second)
            assertEquals(2, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `new complete utterances supersede old voice replies without globally deleting queued messages`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            val generating = rig.queue.takeNext()!!
            assertFalse(generating.reply!!.isCompleted)
            second.end("second")
            val third = rig.recorder(second)
            third.end("third")
            rig.recorder(third)
            assertEquals(listOf("second", "third"), rig.queue.state.value.messages.map {
                (it.parts.single() as UIMessagePart.Text).text
            })
            assertEquals(1, rig.voice.state.value.pendingReplies)
            assertEquals(listOf(rig.replies[0], rig.replies[1]), rig.interrupted)
            assertFalse(rig.replies[0].isCancelled)
            assertFalse(rig.replies[1].isCancelled)
            assertTrue(rig.spoken.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `speech stop waits for final text before enqueueing`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertTrue(asr.paused)
            assertTrue(rig.replies.isEmpty())
            asr.end("final")
            rig.recorder(asr)
            assertEquals("final", (rig.queue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
            // Repeated/stale callbacks from a disposed recorder cannot enqueue another message.
            asr.end("duplicate")
            assertEquals(1, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `speech start cancels its exact older generation and late reply never speaks`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val speaking = rig.recorder(first)
            speaking.begin()
            awaitCondition { rig.interrupted.size == 1 }
            assertSame(rig.replies.first(), rig.interrupted.single())
            rig.replies.first().complete("reply one")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 }
            assertFalse(speaking.disposed)
            assertTrue(rig.spoken.isEmpty())
            speaking.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertTrue(rig.spoken.isEmpty())
            speaking.end("second")
            rig.recorder(speaking)
            assertTrue(rig.spoken.isEmpty())
            rig.replies[1].complete("reply to second")
            awaitCondition { rig.spoken.size == 1 }
            assertEquals(2, rig.replies.size)
            assertTrue(speaking.disposed)
            assertEquals(VoicePhase.Speaking, rig.voice.state.value.phase)
            assertEquals(listOf("reply to second"), rig.spoken)
            rig.playback.complete(Unit)
            rig.recorder(speaking)
        } finally { rig.close() }
    }

    @Test fun `latest complete utterance speaks without waiting for superseded reply and capture resumes after playback`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            second.end("second")
            val idle = rig.recorder(second)
            rig.replies[1].complete("reply two")
            awaitCondition { rig.spoken.size == 1 }
            assertTrue(idle.disposed)
            assertEquals(listOf("reply two"), rig.spoken)
            assertSame(rig.replies[0], rig.interrupted.single())
            rig.replies[0].complete("late reply one")
            assertEquals(listOf("reply two"), rig.spoken)
            assertEquals(VoicePhase.Speaking, rig.voice.state.value.phase)
            rig.playback.complete(Unit)
            rig.recorder(idle)
            assertEquals(listOf("reply two"), rig.spoken)
        } finally { rig.close() }
    }

    @Test fun `withdrawing a queued utterance does not stop voice mode or speak it`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            second.end("withdraw")
            rig.recorder(second)
            val removed = rig.queue.state.value.messages.last()
            rig.queue.remove(removed.id)
            assertNull(rig.replies[1].await())
            rig.playback.complete(Unit)
            rig.replies[0].complete("reply one")
            awaitCondition { rig.voice.state.value.pendingReplies == 0 && rig.voice.state.value.phase == VoicePhase.Listening }
            assertTrue(rig.spoken.isEmpty())
            assertSame(rig.replies[0], rig.interrupted.single())
            assertTrue(rig.voice.state.value.isActive)
        } finally { rig.close() }
    }

    @Test fun `queue pause resolves its localized message`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("hello")
            rig.recorder(first)
            rig.queue.pause()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Error }
            assertEquals(R.string.chat_page_voice_queue_paused.toString(), rig.voice.state.value.error)
        } finally { rig.close() }
    }

    @Test fun `empty utterance never enters queue`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end("")
            rig.recorder(asr)
            assertTrue(rig.replies.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `ASR generation and playback errors stop capture and pause voice mode`() = runBlocking<Unit> {
        for (failure in listOf("asr", "generation", "playback")) {
            val rig = Rig()
            try {
                rig.start()
                val asr = rig.recorder()
                if (failure == "asr") {
                    asr.state.value = asr.state.value.copy(errorMessage = "offline")
                } else {
                    asr.end("hello")
                    rig.recorder(asr)
                    if (failure == "generation") rig.replies[0].completeExceptionally(IllegalStateException("failed"))
                    else {
                        rig.failPlayback = true
                        rig.replies[0].complete("reply")
                    }
                }
                awaitCondition { rig.voice.state.value.phase == VoicePhase.Error }
                if (failure == "asr") assertEquals("offline", rig.voice.state.value.error)
                assertTrue(rig.asrs.all { it.disposed })
            } finally { rig.close() }
        }
    }

    @Test fun `ending voice mode preserves accepted queued messages and detaches late replies`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("first")
            val second = rig.recorder(first)
            rig.voice.stop()
            awaitCondition { second.disposed }
            assertFalse(rig.replies.single().isCancelled)
            assertEquals(1, rig.queue.state.value.messages.size)
            rig.replies.single().complete("late reply")
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
            assertTrue(rig.spoken.isEmpty())
        } finally { rig.close() }
    }

    @Test fun `ending during playback does not reopen the microphone`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val asr = rig.recorder()
            asr.end("hello")
            rig.recorder(asr)
            rig.replies.single().complete("reply")
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Speaking }
            val recorderCount = rig.asrs.size
            rig.voice.stop()
            awaitCondition { rig.stopCount == 2 }
            rig.playback.complete(Unit)
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
            assertTrue(rig.asrs.all { it.disposed })
            assertEquals(recorderCount, rig.asrs.size)
        } finally { rig.close() }
    }

    @Test fun `connected callback is awaited once before any utterance is enqueued`() = runBlocking<Unit> {
        val rig = Rig()
        val connected = CompletableDeferred<Unit>()
        var connectedCount = 0
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
                onConnected = { connectedCount++; connected.await() },
            )
            val first = rig.recorder()
            first.end("first")
            assertEquals(1, connectedCount)
            assertTrue(rig.replies.isEmpty())
            connected.complete(Unit)
            val second = rig.recorder(first)
            assertEquals(1, rig.replies.size)
            second.end("second")
            rig.recorder(second)
            assertEquals(1, connectedCount)
            assertEquals(2, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `stop immediately pauses capture and reports ended only after disposal`() = runBlocking<Unit> {
        val rig = Rig()
        var endedCount = 0
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = { rig.stopCount++ },
                onEnded = {
                    assertNull(it)
                    assertTrue(rig.asrs.all { asr -> asr.disposed })
                    endedCount++
                },
            )
            val recorder = rig.recorder()
            rig.voice.stop()
            assertTrue(recorder.paused)
            assertEquals(2, rig.stopCount)
            rig.voice.stopAndJoin()
            assertEquals(1, endedCount)
            assertEquals(2, rig.stopCount)
            assertEquals(VoicePhase.Off, rig.voice.state.value.phase)
        } finally { rig.close() }
    }

    @Test fun `terminal error callback reports failure after microphone cleanup`() = runBlocking<Unit> {
        val rig = Rig()
        val ended = CompletableDeferred<String?>()
        try {
            rig.voice.start(
                createAsr = { FakeAsr().also { rig.asrs.add(it) } },
                speak = null,
                stopSpeaking = {},
                onEnded = {
                    assertTrue(rig.asrs.all { asr -> asr.disposed })
                    ended.complete(it)
                },
            )
            val recorder = rig.recorder()
            recorder.state.value = recorder.state.value.copy(errorMessage = "microphone unavailable")
            assertEquals("microphone unavailable", withTimeout(3000) { ended.await() })
            assertEquals(VoicePhase.Error, rig.voice.state.value.phase)
        } finally { rig.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a connected silent call stays listening beyond two minutes`() = runTest {
        val recorder = FakeAsr()
        val voice = VoiceSessionController(backgroundScope, getString = { it.toString() }) {
            error("Silence must not submit a user message")
        }
        try {
            voice.start(createAsr = { recorder }, speak = null, stopSpeaking = {})
            advanceTimeBy(201)
            runCurrent()
            assertEquals(VoicePhase.Listening, voice.state.value.phase)
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()
            assertEquals(VoicePhase.Listening, voice.state.value.phase)
            assertFalse(recorder.disposed)
            assertNull(voice.state.value.error)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `muted answer persists connected before reason and never constructs ASR`() = runTest {
        var created = 0
        var connections = 0
        val connected = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("no human speech") }
        try {
            voice.start(createAsr = { created++; FakeAsr() }, speak = { spoken.add(it) },
                stopSpeaking = {}, onConnected = { connections++; connected.await() },
                initialMicrophoneEnabled = false, initialAssistantText = "I wanted to check on you")
            advanceTimeBy(201); runCurrent()
            assertEquals(1, connections)
            assertEquals(0, created)
            assertTrue(spoken.isEmpty())
            connected.complete(Unit); runCurrent()
            assertEquals(listOf("I wanted to check on you"), spoken)
            assertEquals("I wanted to check on you", voice.state.value.lastReplyText)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(0, created)
            assertFalse(voice.state.value.microphoneEnabled)
            assertEquals(1, connections)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `explicit initial text advances silently with speaker off and is not replayed`() = runTest {
        var connected = false
        val spoken = mutableListOf<String>()
        val gains = mutableListOf<Boolean>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("no input") }
        try {
            voice.start(createAsr = { error("Muted answer must not create a recorder") },
                speak = { spoken.add(it) }, stopSpeaking = {}, onConnected = { connected = true },
                initialMicrophoneEnabled = false, initialSpeakerEnabled = false,
                initialAssistantText = "incoming reason", setOutputMuted = { gains.add(it) })
            advanceTimeBy(201); runCurrent()
            assertTrue(connected)
            assertEquals("incoming reason", voice.state.value.lastReplyText)
            assertEquals(listOf("incoming reason"), spoken)
            assertEquals(listOf(true), gains)
            voice.setSpeakerEnabled(true); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertEquals(listOf("incoming reason"), spoken)
            assertEquals(listOf(true, false), gains)
            assertTrue(voice.state.value.speakerEnabled)
        } finally { voice.stopAndJoin() }
    }

    @Test fun `microphone mute immediately pauses and discards unsubmitted partial input`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.begin()
            rig.voice.setMicrophoneEnabled(false)
            assertTrue(first.paused)
            awaitCondition { first.disposed }
            first.end("late private speech")
            assertTrue(rig.replies.isEmpty())
            assertFalse(rig.voice.state.value.microphoneEnabled)
            rig.voice.setMicrophoneEnabled(true)
            val next = rig.recorder(first)
            next.end("new permitted speech")
            rig.recorder(next)
            assertEquals(1, rig.replies.size)
            assertEquals("new permitted speech", (rig.queue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
        } finally { rig.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `rapid mute unmute fences the old recorder before queued callbacks`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val inputs = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            inputs.add(it); CompletableDeferred<String?>()
        }
        try {
            voice.start(createAsr = { FakeAsr().also { recorders.add(it) } }, speak = null, stopSpeaking = {})
            advanceTimeBy(201); runCurrent()
            val old = recorders.single()
            old.end("already queued callback")
            voice.setMicrophoneEnabled(false)
            voice.setMicrophoneEnabled(true)
            runCurrent()
            assertTrue(old.disposed)
            assertTrue(inputs.isEmpty())
            assertTrue(recorders.size >= 2)
            recorders.last().end("fresh")
            runCurrent()
            assertEquals(listOf("fresh"), inputs)
        } finally { voice.stopAndJoin() }
    }

    @Test fun `microphone mute cannot turn synchronous AEC release and ended callback into a playback interruption`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(duplex = true, echoActive = true, releaseEchoOnPause = true)
            val first = rig.recorder()
            first.end("accepted input")
            val idle = rig.recorder(first)
            rig.replies.single().complete("keep this reply playing")
            awaitCondition { rig.spoken.size == 1 }
            val stops = rig.stopCount
            idle.emitEndedOnPause = true
            rig.voice.setMicrophoneEnabled(false)
            awaitCondition { idle.disposed }
            delay(150) // Includes the queued periodic acoustic check after native AEC release.
            assertFalse(idle.state.value.echoCancellationActive)
            assertFalse(rig.voice.state.value.microphoneEnabled)
            assertEquals(VoicePhase.Speaking, rig.voice.state.value.phase)
            assertEquals(stops, rig.stopCount)
            assertTrue(rig.interrupted.isEmpty())
            assertEquals(1, rig.replies.size)
            assertEquals("keep this reply playing", rig.voice.state.value.lastReplyText)
            rig.playback.complete(Unit)
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Listening }
            assertEquals(2, rig.asrs.size)
        } finally { rig.close() }
    }

    @Test fun `microphone mute ignores its recorder failure and preserves pending generation`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(duplex = true, echoActive = true, releaseEchoOnPause = true)
            val first = rig.recorder()
            first.end("accepted before mute")
            val idle = rig.recorder(first)
            val stops = rig.stopCount
            idle.emitErrorOnPause = true
            rig.voice.setMicrophoneEnabled(false)
            awaitCondition { idle.disposed }
            assertTrue(rig.voice.state.value.isActive)
            assertFalse(rig.replies.single().isCancelled)
            rig.replies.single().complete("generation survived input mute")
            awaitCondition { rig.spoken.size == 1 }
            assertEquals(stops, rig.stopCount)
            assertTrue(rig.interrupted.isEmpty())
            assertEquals("generation survived input mute", rig.voice.state.value.lastReplyText)
        } finally { rig.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `muted answer requests one free opening after connection without fabricating human input`() = runTest {
        val connected = CompletableDeferred<Unit>()
        val opening = CompletableDeferred<String?>()
        val spoken = mutableListOf<String>()
        var connections = 0
        var requests = 0
        var humanInputs = 0
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            humanInputs++; error("opening is not a human utterance")
        }
        try {
            voice.start(createAsr = { error("muted answer must not construct ASR") },
                speak = { spoken.add(it) }, stopSpeaking = {}, initialMicrophoneEnabled = false,
                initialAssistantText = "call reason must not also be spoken",
                onConnected = { connections++; connected.await() },
                requestOpening = { requests++; opening })
            advanceTimeBy(201); runCurrent()
            assertEquals(1, connections)
            assertEquals(0, requests)
            assertTrue(spoken.isEmpty())
            connected.complete(Unit); runCurrent()
            assertEquals(1, requests)
            voice.setMicrophoneEnabled(false); runCurrent()
            opening.complete("model selected its own opening"); runCurrent()
            advanceTimeBy(500); runCurrent()
            assertEquals(listOf("model selected its own opening"), spoken)
            assertEquals("model selected its own opening", voice.state.value.lastReplyText)
            assertEquals(1, connections)
            assertEquals(1, requests)
            assertEquals(0, humanInputs)
            assertFalse(voice.state.value.microphoneEnabled)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `muting during suspended connection cannot duplicate or cancel the call handshake or opening`() = runTest {
        val connected = CompletableDeferred<Unit>()
        val recorders = mutableListOf<FakeAsr>()
        val spoken = mutableListOf<String>()
        var connections = 0
        var requests = 0
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("no human input") }
        try {
            voice.start(createAsr = { FakeAsr(true, true, true).also { recorders.add(it) } },
                speak = { spoken.add(it) }, stopSpeaking = {},
                onConnected = { connections++; connected.await() },
                requestOpening = { requests++; CompletableDeferred<String?>("free opening") })
            advanceTimeBy(201); runCurrent()
            assertEquals(1, connections)
            voice.setMicrophoneEnabled(false); runCurrent()
            assertTrue(recorders.single().disposed)
            assertEquals(1, connections)
            assertEquals(0, requests)
            connected.complete(Unit); runCurrent()
            assertEquals(1, requests)
            assertEquals(listOf("free opening"), spoken)
            assertTrue(voice.state.value.isActive)
            assertEquals(1, connections)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `ended or failed connection never requests or plays an opening`() = runTest {
        var openings = 0
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("no input") }
        val connection = CompletableDeferred<Unit>()
        voice.start(createAsr = { error("muted") }, speak = { error("not connected") }, stopSpeaking = {},
            initialMicrophoneEnabled = false, onConnected = { connection.await() },
            requestOpening = { openings++; CompletableDeferred<String?>("must not be requested") })
        advanceTimeBy(201); runCurrent()
        voice.stopAndJoin()
        connection.complete(Unit); runCurrent()
        assertEquals(0, openings)

        val failed = VoiceSessionController(backgroundScope, { it.toString() }) { error("no input") }
        try {
            failed.start(createAsr = { error("muted") }, speak = { error("not connected") }, stopSpeaking = {},
                initialMicrophoneEnabled = false, onConnected = { error("connection rejected") },
                requestOpening = { openings++; null })
            advanceTimeBy(201); runCurrent()
            assertEquals(VoicePhase.Error, failed.state.value.phase)
            assertEquals(0, openings)
        } finally { failed.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `real human speech still interrupts the exact generated opening`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val opening = CompletableDeferred<String?>()
        val interrupted = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("not a final utterance") }
        try {
            voice.start(createAsr = { FakeAsr(true, true).also { recorders.add(it) } },
                speak = { error("interrupted opening must not play") }, stopSpeaking = {},
                requestOpening = { opening }, cancelPendingReply = { interrupted.add(it) })
            advanceTimeBy(201); runCurrent()
            recorders.last().begin(); runCurrent()
            assertEquals(listOf(opening), interrupted)
            opening.complete("late opening"); runCurrent()
            assertEquals("", voice.state.value.lastReplyText)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `conflated speech end cancels pending opening once and late opening never precedes human reply`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val opening = CompletableDeferred<String?>()
        val humanReply = CompletableDeferred<String?>()
        val interrupted = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
        val inputs = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            inputs.add(it); humanReply
        }
        try {
            voice.start(createAsr = { FakeAsr(true, true, true).also { recorders.add(it) } },
                speak = { spoken.add(it) }, stopSpeaking = {}, requestOpening = { opening },
                cancelPendingReply = { interrupted.add(it) })
            advanceTimeBy(201); runCurrent()
            val recorder = recorders.single()
            recorder.end(); runCurrent() // No begin callback: provider conflated the speech start.
            assertEquals(listOf(opening), interrupted)
            assertTrue(inputs.isEmpty())
            assertFalse(opening.isCancelled) // Simulate a provider returning after exact-turn cancellation.
            opening.complete("late opening must not speak"); runCurrent()
            assertTrue(spoken.isEmpty())
            recorder.end("human speaks first"); runCurrent()
            assertEquals(listOf("human speaks first"), inputs)
            assertEquals(listOf(opening), interrupted)
            recorder.end("duplicate final"); runCurrent()
            assertFalse(humanReply.isCancelled)
            assertEquals(listOf(opening), interrupted)
            humanReply.complete("reply to the human"); runCurrent()
            assertEquals(listOf("reply to the human"), spoken)
            assertEquals("reply to the human", voice.state.value.lastReplyText)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `conflated complete utterance discards an opening already queued for playback`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val opening = CompletableDeferred<String?>()
        val humanReply = CompletableDeferred<String?>()
        val interrupted = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { humanReply }
        try {
            voice.start(createAsr = { FakeAsr(true, true).also { recorders.add(it) } },
                speak = { spoken.add(it) }, stopSpeaking = {}, requestOpening = { opening },
                cancelPendingReply = { interrupted.add(it) })
            advanceTimeBy(201); runCurrent()
            // Queue Reply first, but expose the complete human turn before the event loop resumes.
            // It must not start the ready opening while the capture ACK/final is pending.
            opening.complete("ready old opening")
            recorders.single().end("complete human turn")
            runCurrent()
            assertEquals(listOf(opening), interrupted)
            assertTrue(spoken.isEmpty())
            assertFalse(humanReply.isCancelled)
            humanReply.complete("current answer"); runCurrent()
            assertEquals(listOf("current answer"), spoken)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `speaker mute advances the same playback clock without stopping or restarting`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val answer = CompletableDeferred<String?>()
        val spoken = mutableListOf<String>()
        val gains = mutableListOf<Boolean>()
        var stops = 0
        var ticks = 0
        var completed = false
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { answer }
        try {
            voice.start(createAsr = { FakeAsr(true, true).also { recorders.add(it) } },
                speak = { spoken.add(it); repeat(10) { delay(100); ticks++ }; completed = true },
                stopSpeaking = { stops++ }, setOutputMuted = { gains.add(it) })
            advanceTimeBy(201); runCurrent()
            recorders.last().end("one"); runCurrent()
            answer.complete("today we are eating pizza"); runCurrent()
            val stopsBefore = stops
            advanceTimeBy(201); runCurrent()
            val positionBeforeMute = ticks
            voice.setSpeakerEnabled(false); runCurrent()
            advanceTimeBy(400); runCurrent()
            assertTrue(ticks > positionBeforeMute)
            assertFalse(completed)
            assertEquals(stopsBefore, stops)
            assertEquals(VoicePhase.Speaking, voice.state.value.phase)
            assertEquals("today we are eating pizza", voice.state.value.lastReplyText)
            val positionAtUnmute = ticks
            voice.setSpeakerEnabled(true); runCurrent()
            assertEquals(positionAtUnmute, ticks)
            advanceTimeBy(500); runCurrent()
            assertTrue(completed)
            assertEquals(10, ticks)
            assertEquals(listOf("today we are eating pizza"), spoken)
            assertEquals(listOf(false, true, false), gains)
            assertEquals(stopsBefore, stops)
            assertFalse(answer.isCancelled)
        } finally { voice.stopAndJoin() }
    }

    @Test fun `streaming capability alone does not permit unsafe simultaneous playback`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(duplex = true)
            val first = rig.recorder()
            first.end("hello")
            val idle = rig.recorder(first)
            rig.replies.single().complete("reply")
            awaitCondition { rig.spoken.size == 1 }
            assertTrue(idle.disposed)
            assertFalse(rig.voice.state.value.canInterruptPlayback)
        } finally { rig.close() }
    }

    @Test fun `active AEC keeps capture alive and speech start stops only its playback turn`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(duplex = true, echoActive = true)
            val first = rig.recorder()
            first.end("hello")
            val idle = rig.recorder(first)
            rig.replies.single().complete("old spoken reply")
            awaitCondition { rig.spoken.size == 1 }
            assertFalse(idle.disposed)
            assertTrue(rig.voice.state.value.canInterruptPlayback)
            val before = rig.stopCount
            idle.begin()
            awaitCondition { rig.interrupted.size == 1 }
            assertSame(rig.replies[0], rig.interrupted.single())
            assertTrue(rig.stopCount > before)
            assertFalse(idle.disposed)
            assertEquals(VoicePhase.Listening, rig.voice.state.value.phase)
            idle.end()
            awaitCondition { rig.voice.state.value.phase == VoicePhase.Transcribing }
            assertEquals(1, rig.replies.size)
            idle.end("quiet interruption")
            rig.recorder(idle)
            assertEquals(2, rig.replies.size)
            rig.playback.complete(Unit) // Old await completion cannot revive old playback.
            rig.replies[1].complete("new answer")
            awaitCondition { rig.spoken.size == 2 }
            assertEquals(listOf("old spoken reply", "new answer"), rig.spoken)
        } finally { rig.close() }
    }

    @Test fun `headset permits duplex without AEC but disconnect stops audio without an AI cancellation`() = runBlocking<Unit> {
        var headset = true
        val rig = Rig()
        try {
            rig.start(duplex = true, headset = { headset })
            val first = rig.recorder()
            first.end("hello")
            val idle = rig.recorder(first)
            rig.replies.single().complete("reply")
            awaitCondition { rig.spoken.size == 1 }
            assertFalse(idle.disposed)
            assertTrue(rig.voice.state.value.canInterruptPlayback)
            val before = rig.stopCount
            headset = false
            awaitCondition { idle.disposed && rig.stopCount > before }
            idle.end("possible echo")
            val restored = rig.recorder(idle)
            assertFalse(restored.disposed)
            assertFalse(rig.voice.state.value.canInterruptPlayback)
            assertTrue(rig.interrupted.isEmpty())
            assertEquals(1, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `AEC loss with a speech callback is not misclassified as human interruption`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start(duplex = true, echoActive = true)
            val first = rig.recorder()
            first.end("hello")
            val idle = rig.recorder(first)
            rig.replies.single().complete("reply")
            awaitCondition { rig.spoken.size == 1 }
            idle.state.value = idle.state.value.copy(echoCancellationActive = false,
                voiceTurn = ASRVoiceTurn("echo"))
            awaitCondition { idle.disposed }
            rig.recorder(idle)
            assertTrue(rig.interrupted.isEmpty())
            assertEquals(1, rig.replies.size)
        } finally { rig.close() }
    }

    @Test fun `interruption selects latest pending reply without cancelling unrelated earlier deferred`() = runBlocking<Unit> {
        val rig = Rig()
        try {
            rig.start()
            val first = rig.recorder()
            first.end("one")
            val second = rig.recorder(first)
            second.end("two")
            val third = rig.recorder(second)
            third.begin()
            awaitCondition { rig.interrupted.size == 2 }
            assertEquals(listOf(rig.replies[0], rig.replies[1]), rig.interrupted)
            assertFalse(rig.replies[0].isCancelled)
            rig.replies[0].complete("late older result")
            rig.replies[1].complete("late interrupted result")
            delay(30)
            assertTrue(rig.spoken.isEmpty())
            assertEquals(0, rig.voice.state.value.pendingReplies)
        } finally { rig.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `speech start and a short pause never prematurely submit or hand the turn to TTS`() = runTest {
        val recorder = FakeAsr(true, true)
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("not final") }
        try {
            voice.start(createAsr = { recorder }, speak = { error("not the assistant turn") }, stopSpeaking = {})
            advanceTimeBy(201); runCurrent()
            recorder.begin(); runCurrent()
            advanceTimeBy(2_900); runCurrent()
            assertEquals(VoicePhase.Listening, voice.state.value.phase)
            assertFalse(recorder.paused)
            recorder.end(); runCurrent()
            assertEquals(VoicePhase.Transcribing, voice.state.value.phase)
            advanceTimeBy(2_900); runCurrent()
            assertEquals(VoicePhase.Transcribing, voice.state.value.phase)
            assertFalse(recorder.disposed)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `natural ended ACK precedes native AEC release so a conflated final is retained`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val inputs = mutableListOf<String>()
        val playback = CompletableDeferred<Unit>()
        val interrupted = mutableListOf<kotlinx.coroutines.Deferred<String?>>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            inputs.add(it); CompletableDeferred<String?>().also { reply -> replies.add(reply) }
        }
        try {
            voice.start(createAsr = { FakeAsr(true, true, true).also { recorders.add(it) } },
                speak = { spoken.add(it); playback.await() }, stopSpeaking = {},
                cancelPendingReply = { interrupted.add(it) })
            advanceTimeBy(201); runCurrent()
            recorders.last().end("first"); runCurrent()
            replies.single().complete("old answer"); runCurrent()
            val current = recorders.last()
            assertEquals(VoicePhase.Speaking, voice.state.value.phase)
            // Provider's start frame is conflated, then pauseCapture synchronously releases AEC.
            current.end(); runCurrent()
            assertFalse(current.state.value.echoCancellationActive)
            assertFalse(current.disposed)
            assertEquals(VoicePhase.Transcribing, voice.state.value.phase)
            assertSame(replies[0], interrupted.single())
            advanceTimeBy(500); runCurrent()
            current.end("kept final"); runCurrent()
            assertEquals(listOf("first", "kept final"), inputs)
            assertTrue(current.disposed)
            assertTrue(voice.state.value.isActive)
            assertEquals(listOf("old answer"), spoken)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `rapid speaker off on retains a completed but not yet observed reply`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            CompletableDeferred<String?>().also { reply -> replies.add(reply) }
        }
        try {
            voice.start(createAsr = { FakeAsr(true, true).also { recorders.add(it) } },
                speak = { spoken.add(it) }, stopSpeaking = {})
            advanceTimeBy(201); runCurrent()
            recorders.last().end("before mute"); runCurrent()
            replies[0].complete("old queued reply")
            voice.setSpeakerEnabled(false)
            voice.setSpeakerEnabled(true)
            runCurrent()
            assertEquals(listOf("old queued reply"), spoken)
            assertEquals("old queued reply", voice.state.value.lastReplyText)
            recorders.last().end("after unmute"); runCurrent()
            replies[1].complete("new reply"); runCurrent()
            assertEquals(listOf("old queued reply", "new reply"), spoken)
        } finally { voice.stopAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `speaker mute preserves current synthesis and playback without reviving superseded sentences`() = runTest {
        val recorders = mutableListOf<FakeAsr>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val playback = CompletableDeferred<Unit>()
        var mayFinishPlayback = false
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) {
            CompletableDeferred<String?>().also { reply -> replies.add(reply) }
        }
        try {
            voice.start(createAsr = { FakeAsr(true, true).also { recorders.add(it) } },
                speak = { spoken.add(it); playback.await() },
                stopSpeaking = { if (mayFinishPlayback) playback.complete(Unit) })
            advanceTimeBy(201); runCurrent()
            recorders.last().end("first"); runCurrent()
            recorders.last().end("second"); runCurrent()
            replies[1].complete("second old sentence")
            replies[0].complete("first old sentence"); runCurrent()
            assertEquals(listOf("second old sentence"), spoken)
            mayFinishPlayback = true
            voice.setSpeakerEnabled(false)
            voice.setSpeakerEnabled(true)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertFalse(playback.isCompleted)
            assertEquals(listOf("second old sentence"), spoken)
            assertEquals("second old sentence", voice.state.value.lastReplyText)
            playback.complete(Unit); runCurrent()
            assertEquals(listOf("second old sentence"), spoken)
        } finally { voice.stopAndJoin() }
    }

    companion object {
        private suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(3000) { while (!predicate()) delay(1) }
        }
    }
}
