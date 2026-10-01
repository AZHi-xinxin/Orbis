package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.rerere.asr.ASRController
import me.rerere.asr.ASRCorrectionResult
import me.rerere.asr.ASRCorrectionNotice
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
    val microphoneEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val lastReplyText: String = "",
    // Capability only: quiet-voice/AEC quality still requires device acceptance testing.
    val canInterruptPlayback: Boolean = false,
    /** Original ASR only when changed; UI/audit only, never a second model message. */
    val originalTranscript: String? = null,
    val correctionNotice: ASRCorrectionNotice = ASRCorrectionNotice(),
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

private fun VoiceSessionState.withTranscript(value: ASRCorrectionResult, eventId: Long): VoiceSessionState =
    copy(transcript = value.corrected, originalTranscript = value.original.takeIf { value.changed },
        correctionNotice = correctionNotice.accept(eventId, value))

/** One event owner fences capture, generation and playback; a late old turn cannot regain audio. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var activeAsr: ASRController? = null
    private var stopCurrentPlayback: (() -> Unit)? = null
    private var muteOutput: ((Boolean) -> Unit)? = null
    private var controls: Channel<Event>? = null
    private val microphoneRevision = AtomicLong()

    private sealed interface Event {
        data class CaptureState(val epoch: Long, val state: ASRState, val transcribing: Boolean) : Event
        data class CaptureEnded(val epoch: Long, val state: ASRState, val acknowledged: CompletableDeferred<Unit>) : Event
        data class Utterance(val epoch: Long, val text: String) : Event
        data class CaptureFailed(val epoch: Long, val error: Exception) : Event
        data class Reply(val id: Long, val text: String?, val error: Exception? = null) : Event
        data class PlaybackEnded(val id: Long, val error: Exception? = null) : Event
        data class TailEnded(val id: Long) : Event
        data object Connected : Event
        data class MicrophoneChanged(val enabled: Boolean) : Event
        data object SpeakerChanged : Event
        data class OutputFailed(val error: Exception) : Event
        data object CheckAcoustics : Event
    }

    private class Pending(val id: Long, val reply: Deferred<String?>) {
        var observer: Job? = null
        var completed = false
        var text: String? = null
    }
    private data class Playback(val id: Long, val text: String, val reply: Deferred<String?>? = null)

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
        onConnected: suspend () -> Unit = {},
        onEnded: (String?) -> Unit = {},
        initialMicrophoneEnabled: Boolean = true,
        initialSpeakerEnabled: Boolean = true,
        initialAssistantText: String? = null,
        cancelPendingReply: (Deferred<String?>) -> Unit = {},
        isHeadsetConnected: () -> Boolean = { false },
        setOutputMuted: (Boolean) -> Unit = {},
        requestOpening: (suspend () -> Deferred<String?>?)? = null,
        correctTranscript: (String) -> ASRCorrectionResult = { ASRCorrectionResult(it, it) },
        enqueueRecognizedMessage: ((ASRCorrectionResult) -> Deferred<String?>)? = null,
    ) {
        if (job?.isCompleted == false) return
        val events = Channel<Event>(Channel.UNLIMITED)
        controls = events
        val playbackStopped = AtomicBoolean(false)
        val stopPlayback = {
            if (playbackStopped.compareAndSet(false, true)) runCatching { stopSpeaking() }
            Unit
        }
        stopCurrentPlayback = stopPlayback
        muteOutput = setOutputMuted
        mutableState.value = VoiceSessionState(phase = VoicePhase.Connecting,
            microphoneEnabled = initialMicrophoneEnabled, speakerEnabled = initialSpeakerEnabled)
        job = scope.launch {
            var terminalError: String? = null
            try {
                stopSpeaking()
                setOutputMuted(!mutableState.value.speakerEnabled)
                delay(200)
                runSession(events, createAsr, speak, stopSpeaking, onConnected,
                    initialAssistantText, cancelPendingReply, isHeadsetConnected, requestOpening,
                    correctTranscript, enqueueRecognizedMessage)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                terminalError = when (e) {
                    is TimeoutCancellationException -> "Speech recognition timed out. Restart voice mode."
                    is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                    else -> e.message ?: getString(R.string.chat_page_voice_failed)
                }
                mutableState.update { it.copy(phase = VoicePhase.Error, error = terminalError,
                    canInterruptPlayback = false) }
            } finally {
                stopPlayback()
                if (stopCurrentPlayback === stopPlayback) {
                    stopCurrentPlayback = null
                    muteOutput = null
                    controls = null
                }
                events.close()
                onEnded(terminalError)
            }
        }
    }

    private suspend fun runSession(
        events: Channel<Event>, createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?, stopSpeaking: () -> Unit,
        onConnected: suspend () -> Unit, initialAssistantText: String?,
        cancelPendingReply: (Deferred<String?>) -> Unit, isHeadsetConnected: () -> Boolean,
        requestOpening: (suspend () -> Deferred<String?>?)?,
        correctTranscript: (String) -> ASRCorrectionResult,
        enqueueRecognizedMessage: ((ASRCorrectionResult) -> Deferred<String?>)?,
    ) = coroutineScope {
        val pending = linkedMapOf<Long, Pending>()
        val ready = ArrayDeque<Playback>()
        var asr: ASRController? = null
        var capture: Job? = null
        var captureEpoch = 0L
        var captureMicrophoneRevision = microphoneRevision.get()
        var speechStartedId: String? = null
        var interruptedCaptureEpoch: Long? = null
        var connected = false
        val connectionStarted = AtomicBoolean(false)
        val connectionPersisted = CompletableDeferred<Unit>()
        var openingRequested = false
        var sequence = 0L
        var playing: Playback? = null
        var playback: Job? = null
        var acousticMonitor: Job? = null
        var captureDeferredForPlayback: Long? = null
        var tailEpoch = 0L
        var tailWaiting = false
        var transcribing = false

        fun duplexSafe(): Boolean = mutableState.value.microphoneEnabled &&
            asr?.supportsConcurrentPlayback == true &&
            (runCatching(isHeadsetConnected).getOrDefault(false) || asr?.state?.value?.echoCancellationActive == true)

        suspend fun closeCapture() {
            captureEpoch++ // Fence callbacks before dispose can produce more.
            runCatching { asr?.pauseCapture() }
            capture?.cancelAndJoin()
            capture = null
            asr = null
            speechStartedId = null
            transcribing = false
        }
        fun waitForTail() {
            val id = ++tailEpoch
            tailWaiting = true
            launch { delay(300); events.send(Event.TailEnded(id)) }
        }
        suspend fun stopPlayback() {
            playing = null // Fence completion before stopping synthesis/playback.
            acousticMonitor?.cancel()
            acousticMonitor = null
            runCatching { stopSpeaking() }
            playback?.cancelAndJoin()
            playback = null
        }
        suspend fun interruptReply() {
            // Cancel the selected exact voice turn, not unrelated work in the chat queue.
            val target = playing?.reply ?: pending.values.lastOrNull()?.reply ?: ready.lastOrNull()?.reply
            pending.values.forEach { it.observer?.cancel() }
            pending.clear()
            ready.clear()
            if (playing != null) stopPlayback()
            mutableState.update { it.copy(pendingReplies = 0) }
            target?.let { runCatching { cancelPendingReply(it) } }
        }
        suspend fun interruptForHumanCapture(epoch: Long) {
            // A recorder produces one utterance. Start, ended ACK and final text may all
            // describe it, or the provider may conflate start straight into ended/final.
            // Fence once before any suspension so final/duplicate callbacks cannot cancel
            // the new reply submitted for this same human utterance.
            if (interruptedCaptureEpoch == epoch) return
            interruptedCaptureEpoch = epoch
            interruptReply()
        }
        suspend fun checkAcoustics() {
            // Closing the microphone invalidates its capture BEFORE native AEC release.
            // An intentional close is not a route/effect failure and must never stop output.
            if (mutableState.value.microphoneEnabled && captureMicrophoneRevision == microphoneRevision.get() &&
                playing != null && capture != null && !duplexSafe()) {
                // Route/effect loss is not human speech. Never submit that recorder's possible echo.
                closeCapture()
                stopPlayback()
                waitForTail()
            }
        }
        suspend fun markConnected() {
            if (connectionStarted.compareAndSet(false, true)) {
                // The connection handshake belongs to the call, not a recorder that can be muted.
                launch {
                    try {
                        onConnected()
                        events.send(Event.Connected)
                        connectionPersisted.complete(Unit)
                    } catch (error: Exception) {
                        connectionPersisted.completeExceptionally(error)
                    }
                }
            }
            connectionPersisted.await()
        }
        fun observeReply(reply: Deferred<String?>) {
            val item = Pending(++sequence, reply)
            pending[item.id] = item
            item.observer = launch {
                try { events.send(Event.Reply(item.id, item.reply.await())) }
                catch (error: Exception) {
                    if (error is CancellationException && !isActive) throw error
                    events.send(Event.Reply(item.id, null, error))
                }
            }
        }
        fun refreshState() {
            val phase = when {
                playing != null -> VoicePhase.Speaking
                transcribing -> VoicePhase.Transcribing
                !connected -> VoicePhase.Connecting
                asr?.state?.value?.status == ASRStatus.Connecting -> VoicePhase.Connecting
                else -> VoicePhase.Listening
            }
            mutableState.update { it.copy(phase = phase, canInterruptPlayback = duplexSafe(),
                pendingReplies = pending.values.count { reply -> !reply.completed }) }
        }
        try {
            // Muted acceptance is a connected call without even constructing a recorder.
            if (!mutableState.value.microphoneEnabled) markConnected()
            while (isActive) {
                if (capture != null && (!mutableState.value.microphoneEnabled ||
                        captureMicrophoneRevision != microphoneRevision.get())) closeCapture()
                checkAcoustics()
                // Preserve submission order even when generations finish out of order.
                while (pending.values.firstOrNull()?.completed == true) {
                    val reply = pending.remove(pending.keys.first())!!
                    reply.text?.takeIf { it.isNotBlank() }?.let { text ->
                        mutableState.update { it.copy(lastReplyText = text) }
                        if (speak != null) {
                            ready.addLast(Playback(reply.id, text, reply.reply))
                        }
                    }
                }
                val humanTurnInProgress = asr?.state?.value?.voiceTurn?.itemId != null
                if (connected && !tailWaiting && playing == null && ready.isNotEmpty() &&
                    !humanTurnInProgress && speak != null) {
                    if (!duplexSafe()) closeCapture()
                    val item = ready.removeFirst()
                    playing = item
                    refreshState()
                    playback = launch {
                        try { speak(item.text); events.send(Event.PlaybackEnded(item.id)) }
                        catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.PlaybackEnded(item.id, e))
                        }
                    }
                    acousticMonitor = launch {
                        while (isActive) { delay(100); events.send(Event.CheckAcoustics) }
                    }
                }
                if (capture == null && !tailWaiting && mutableState.value.microphoneEnabled &&
                    (playing == null || (playing?.id != captureDeferredForPlayback &&
                        runCatching(isHeadsetConnected).getOrDefault(false)))) {
                    val recorder = createAsr()
                    if (playing != null && !recorder.supportsConcurrentPlayback) {
                        captureDeferredForPlayback = playing?.id
                        recorder.dispose()
                    }
                    else {
                        val epoch = ++captureEpoch
                        captureMicrophoneRevision = microphoneRevision.get()
                        asr = recorder
                        activeAsr = recorder
                        speechStartedId = null
                        mutableState.update { it.copy(transcript = "", originalTranscript = null,
                            correctionNotice = it.correctionNotice.begin(epoch)) }
                        capture = launch {
                            try {
                                events.send(Event.Utterance(epoch, listen(recorder, ::markConnected, { ended ->
                                    val acknowledged = CompletableDeferred<Unit>()
                                    events.send(Event.CaptureEnded(epoch, ended, acknowledged))
                                    acknowledged.await()
                                }) { value, ending ->
                                    events.trySend(Event.CaptureState(epoch, value, ending))
                                }))
                            } catch (e: Exception) {
                                if (e is CancellationException && !isActive) throw e
                                events.send(Event.CaptureFailed(epoch, e))
                            }
                        }
                    }
                }
                refreshState()
                when (val event = events.receive()) {
                    Event.Connected -> {
                        connected = true
                        if (!openingRequested) {
                            openingRequested = true
                            if (requestOpening != null) {
                                // This is a host-connected event, never a fabricated human utterance.
                                requestOpening.invoke()?.let(::observeReply)
                            } else initialAssistantText?.takeIf { it.isNotBlank() }?.let { text ->
                                mutableState.update { it.copy(lastReplyText = text) }
                                if (speak != null) ready.addLast(Playback(++sequence, text))
                            }
                        }
                    }
                    is Event.CaptureState -> if (event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) {
                        checkAcoustics()
                        if (event.epoch == captureEpoch) {
                            transcribing = event.transcribing
                            val transcript = correctTranscript(event.state.transcript)
                            mutableState.update { it.withTranscript(transcript, event.epoch) }
                            val turn = event.state.voiceTurn
                            if (turn.itemId != null && !turn.speechEnded && speechStartedId != turn.itemId) {
                                speechStartedId = turn.itemId
                                interruptForHumanCapture(event.epoch)
                            }
                        }
                    }
                    is Event.CaptureEnded -> {
                        if (event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                            captureMicrophoneRevision == microphoneRevision.get()) {
                            checkAcoustics()
                            if (event.epoch != captureEpoch) {
                                event.acknowledged.cancel()
                                continue
                            }
                            // ACK before the recorder releases AEC. An intentional end must not look
                            // like a recording-time effect failure and destroy its waiting final text.
                            interruptForHumanCapture(event.epoch)
                            transcribing = true
                            val transcript = correctTranscript(event.state.transcript)
                            mutableState.update { it.withTranscript(transcript, event.epoch) }
                            event.acknowledged.complete(Unit)
                        } else event.acknowledged.cancel()
                    }
                    is Event.Utterance -> if (event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) {
                        capture?.join() // Recorder is disposed before input reaches the chat queue.
                        capture = null
                        asr = null
                        transcribing = false
                        if (event.text.isNotBlank()) {
                            interruptForHumanCapture(event.epoch)
                            val transcript = correctTranscript(event.text)
                            mutableState.update { it.withTranscript(transcript, event.epoch) }
                            observeReply(enqueueRecognizedMessage?.invoke(transcript) ?: enqueueMessage(transcript.corrected))
                        }
                    }
                    is Event.CaptureFailed -> if (event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) throw event.error
                    is Event.Reply -> pending[event.id]?.let {
                        event.error?.let { error -> throw error }
                        it.completed = true
                        it.text = event.text
                    }
                    is Event.PlaybackEnded -> if (playing?.id == event.id) {
                        playing = null
                        playback = null
                        acousticMonitor?.cancel()
                        acousticMonitor = null
                        event.error?.let { throw it }
                        if (capture == null) waitForTail()
                    }
                    is Event.TailEnded -> if (event.id == tailEpoch) tailWaiting = false
                    is Event.MicrophoneChanged -> {
                        if (capture != null && (!mutableState.value.microphoneEnabled ||
                                captureMicrophoneRevision != microphoneRevision.get())) closeCapture()
                        if (!mutableState.value.microphoneEnabled) {
                            mutableState.update { it.copy(transcript = "", originalTranscript = null) }
                            if (!connected) markConnected()
                        }
                    }
                    Event.SpeakerChanged -> Unit // Gain only: synthesis, queue and timeline continue.
                    is Event.OutputFailed -> throw event.error
                    Event.CheckAcoustics -> checkAcoustics()
                }
            }
        } finally {
            capture?.cancel()
            playback?.cancel()
            acousticMonitor?.cancel()
            pending.values.forEach { it.observer?.cancel() }
            // Leaving detaches observers; accepted chat work is not globally canceled.
        }
    }

    private suspend fun listen(asr: ASRController, onConnected: suspend () -> Unit,
        onCaptureEnded: suspend (ASRState) -> Unit,
        onState: (ASRState, Boolean) -> Unit): String {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also { check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Unable to start speech recognition" } }
            }
            onConnected()
            // No local short-pause heuristic: provider call silence remains >=3s.
            val ended = asr.state.onEach {
                check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Speech recognition disconnected" }
                onState(it, false)
            }.first { it.voiceTurn.speechEnded }
            onCaptureEnded(ended)
            asr.pauseCapture()
            onState(ended, true)
            return withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } finally {
            try { asr.dispose() } finally { if (activeAsr === asr) activeAsr = null }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (controls == null || !mutableState.value.isActive) return
        if (!enabled) {
            microphoneRevision.incrementAndGet()
        }
        mutableState.update { it.copy(microphoneEnabled = enabled,
            canInterruptPlayback = if (enabled) it.canInterruptPlayback else false) }
        if (!enabled) runCatching { activeAsr?.pauseCapture() }
        controls?.trySend(Event.MicrophoneChanged(enabled))
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        if (controls == null || !mutableState.value.isActive) return
        try { muteOutput?.invoke(!enabled) }
        catch (error: Exception) { controls?.trySend(Event.OutputFailed(error)); return }
        mutableState.update { it.copy(speakerEnabled = enabled) }
        controls?.trySend(Event.SpeakerChanged)
    }

    /** UI acknowledgement only; keeps this utterance's raw/corrected audit intact. */
    fun dismissCorrectionNotice(eventId: Long) {
        mutableState.update { it.copy(correctionNotice = it.correctionNotice.dismiss(eventId)) }
    }

    fun stop() {
        val recorder = activeAsr
        microphoneRevision.incrementAndGet()
        // Fence/cancel observers before native stop can synchronously emit old ASR/TTS callbacks.
        job?.cancel()
        runCatching { recorder?.pauseCapture() }
        stopCurrentPlayback?.invoke()
        mutableState.value = VoiceSessionState()
    }

    suspend fun stopAndJoin() {
        val stoppedJob = job
        stop()
        stoppedJob?.join()
    }
}
