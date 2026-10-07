package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val audioFocusSuspended: Boolean = false,
    val reconnecting: Boolean = false,
    val recoveryNotice: String? = null,
    val replyBlocked: Boolean = false,
    val replyResumeChecking: Boolean = false,
    val replyNotice: String? = null,
    /** Listening also includes live human speech; periodic camera work must not use it as idle. */
    val humanTurnInProgress: Boolean = false,
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
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var activeAsr: ASRController? = null
    private var stopCurrentPlayback: (() -> Unit)? = null
    private var muteOutput: ((Boolean) -> Unit)? = null
    private var interruptCurrentAudio: (() -> Unit)? = null
    private var controls: Channel<Event>? = null
    private val microphoneRevision = AtomicLong()
    private val focusRevision = AtomicLong()
    private val replyRevision = AtomicLong()

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
        data object FocusChanged : Event
        data class RetryCapture(val revision: Long) : Event
        data class CheckReplies(val revision: Long, val checkReady: suspend () -> Boolean) : Event
        data class RepliesChecked(val revision: Long, val ready: Boolean) : Event
        data class SupplementaryReply(val reply: Deferred<String?>) : Event
        data class RequestSupplementaryReply(
            val stillCurrent: () -> Boolean,
            val createReply: () -> Deferred<String?>,
            val accepted: CompletableDeferred<Deferred<String?>?>,
        ) : Event
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
        onReplyPauseChanged: (Boolean) -> Unit = {},
        // Readiness only, including call/model ownership before and after checking. This
        // callback must never clear a queue/tool hold, resend input or start model work.
        checkReplyResume: (suspend () -> Boolean)? = null,
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
        interruptCurrentAudio = stopSpeaking
        muteOutput = setOutputMuted
        mutableState.value = VoiceSessionState(phase = VoicePhase.Connecting,
            microphoneEnabled = initialMicrophoneEnabled, speakerEnabled = initialSpeakerEnabled)
        job = scope.launch {
            var terminalError: String? = null
            try {
                stopSpeaking()
                setOutputMuted(!mutableState.value.speakerEnabled || mutableState.value.audioFocusSuspended)
                delay(200)
                runSession(events, createAsr, speak, stopSpeaking, onConnected,
                    initialAssistantText, cancelPendingReply, isHeadsetConnected, requestOpening,
                    correctTranscript, enqueueRecognizedMessage, onReplyPauseChanged, checkReplyResume)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                terminalError = when (e) {
                    is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                    is VoiceSessionFailure -> e.message
                    else -> VoiceRecoveryPolicy.failure(VoiceFailureStage.CONNECT, e).message
                }
                mutableState.update { it.copy(phase = VoicePhase.Error, error = terminalError,
                    canInterruptPlayback = false) }
            } finally {
                stopPlayback()
                if (stopCurrentPlayback === stopPlayback) {
                    stopCurrentPlayback = null
                    muteOutput = null
                    interruptCurrentAudio = null
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
        onReplyPauseChanged: (Boolean) -> Unit,
        checkReplyResume: (suspend () -> Boolean)?,
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
        var observedFocusRevision = focusRevision.get()
        var recoveryJob: Job? = null
        var recoveryRevision = 0L
        val recoveryBudget = VoiceReconnectBudget()
        var awaitingRetry = false
        var replyResumeJob: Job? = null
        val replyCheckMutex = Mutex()

        suspend fun checkRepliesReady(revision: Long, timeoutMs: Long, checkReady: suspend () -> Boolean): Boolean =
            try {
                withTimeout(timeoutMs) {
                    // A cancelled, non-cooperative old probe must not overlap a manual one.
                    // The lock and timeout cover admission as well as the readiness query.
                    replyCheckMutex.withLock {
                        controls === events && revision == replyRevision.get() &&
                            mutableState.value.replyBlocked && checkReady()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                false
            }

        fun scheduleReplyChecks(revision: Long) {
            val checkReady = checkReplyResume ?: return
            // Detached from the session's structured teardown: even a broken external
            // checker cannot delay hang-up. Its old channel/revision cannot regain audio.
            replyResumeJob = scope.launch {
                for (delayMs in longArrayOf(250L, 750L, 1_500L, 3_000L)) {
                    delay(delayMs)
                    if (controls !== events || revision != replyRevision.get() || !mutableState.value.replyBlocked) return@launch
                    if (checkRepliesReady(revision, 2_000L, checkReady)) {
                        events.trySend(Event.RepliesChecked(revision, true))
                        return@launch
                    }
                }
                events.trySend(Event.RepliesChecked(revision, false))
            }
        }

        fun audioAllowed(): Boolean = !mutableState.value.audioFocusSuspended && !mutableState.value.replyBlocked
        fun duplexSafe(): Boolean = audioAllowed() && mutableState.value.microphoneEnabled &&
            asr?.supportsConcurrentPlayback == true &&
            (runCatching(isHeadsetConnected).getOrDefault(false) || asr?.state?.value?.echoCancellationActive == true)

        suspend fun closeCapture() {
            recoveryBudget.stopListening(monotonicTimeMs())
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
        fun cancelRecovery() {
            recoveryRevision++
            recoveryJob?.cancel()
            recoveryJob = null
            awaitingRetry = false
            mutableState.update { it.copy(reconnecting = false) }
        }
        suspend fun pauseRecognition(failure: VoiceSessionFailure) {
            // After connection, a failed utterance must not hang up an otherwise healthy call.
            // Mute is independent of model holds/focus: only the human can enable it again.
            microphoneRevision.incrementAndGet()
            mutableState.update { it.copy(microphoneEnabled = false, canInterruptPlayback = false,
                transcript = "", originalTranscript = null,
                recoveryNotice = "语音识别已暂停，本句未提交；请点开麦后重新说，旧录音不会重发。" +
                    "[ASR_MANUAL_RESUME_REQUIRED] [${failure.code}]") }
            cancelRecovery()
            closeCapture()
            mutableState.update { it.copy(correctionNotice = it.correctionNotice.begin(captureEpoch)) }
        }
        suspend fun pauseReplies(error: Exception) {
            val revision = replyRevision.incrementAndGet()
            replyResumeJob?.cancel()
            replyResumeJob = null
            // Publish the independent fence before suspending cleanup. Audio focus and mute
            // controls must never release a model/tool-result hold.
            mutableState.update { it.copy(replyBlocked = true, replyResumeChecking = false,
                replyNotice = if (error is MessageQueuePausedException)
                    "聊天队列已暂停；通话仍保留。请回聊天处理后点继续回复。[MODEL_QUEUE_PAUSED]"
                else VoiceRecoveryPolicy.failure(VoiceFailureStage.MODEL, error).message,
                pendingReplies = 0, canInterruptPlayback = false, recoveryNotice = null) }
            runCatching { onReplyPauseChanged(true) }
            microphoneRevision.incrementAndGet()
            cancelRecovery()
            runCatching { muteOutput?.invoke(true) }
            closeCapture()
            stopPlayback()
            // Detach local listeners only. Accepted input, generation and tools retain their
            // owners in ChatService; neither failure nor resume replays/cancels their work.
            pending.values.forEach { it.observer?.cancel() }
            pending.clear()
            ready.clear()
            waitForTail()
            // Failure can arrive just before ChatService finishes its exact turn. Allow
            // only a bounded, read-only recheck; unresolved holds still require the human.
            // Keep the manual action available while these background checks are running.
            scheduleReplyChecks(revision)
        }
        suspend fun applyFocusFence() {
            val revision = focusRevision.get()
            if (observedFocusRevision != revision) {
                observedFocusRevision = revision
                cancelRecovery()
                closeCapture()
                stopPlayback()
                // A partially played utterance is never put back in ready; future replies remain.
                waitForTail()
            }
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
                mutableState.value.replyBlocked -> VoicePhase.Listening
                mutableState.value.audioFocusSuspended -> VoicePhase.Listening
                awaitingRetry -> VoicePhase.Connecting
                playing != null -> VoicePhase.Speaking
                transcribing -> VoicePhase.Transcribing
                !connected -> VoicePhase.Connecting
                asr?.state?.value?.status == ASRStatus.Connecting -> VoicePhase.Connecting
                else -> VoicePhase.Listening
            }
            mutableState.update { it.copy(phase = phase, canInterruptPlayback = duplexSafe(),
                pendingReplies = pending.values.count { reply -> !reply.completed },
                humanTurnInProgress = asr?.state?.value?.voiceTurn?.itemId != null) }
        }
        try {
            // Muted acceptance is a connected call without even constructing a recorder.
            if (!mutableState.value.microphoneEnabled) markConnected()
            while (isActive) {
                applyFocusFence()
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
                if (audioAllowed() && connected && !tailWaiting && playing == null && ready.isNotEmpty() &&
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
                if (audioAllowed() && !awaitingRetry && capture == null && !tailWaiting && mutableState.value.microphoneEnabled &&
                    (playing == null || (playing?.id != captureDeferredForPlayback &&
                        runCatching(isHeadsetConnected).getOrDefault(false)))) {
                    val recorder = try { createAsr() } catch (error: Exception) {
                        val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_START, error)
                        if (!connected) throw failure
                        pauseRecognition(failure)
                        continue
                    }
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
                val event = events.receive()
                applyFocusFence()
                when (event) {
                    Event.Connected -> {
                        connected = true
                        if (!openingRequested) {
                            openingRequested = true
                            if (requestOpening != null) {
                                // This is a host-connected event, never a fabricated human utterance.
                                try { requestOpening.invoke()?.let(::observeReply) }
                                catch (error: Exception) {
                                    if (error is CancellationException && !isActive) throw error
                                    pauseReplies(error)
                                }
                            } else initialAssistantText?.takeIf { it.isNotBlank() }?.let { text ->
                                mutableState.update { it.copy(lastReplyText = text) }
                                if (speak != null) ready.addLast(Playback(++sequence, text))
                            }
                        }
                    }
                    is Event.CaptureState -> if (audioAllowed() && event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) {
                        checkAcoustics()
                        if (event.epoch == captureEpoch) {
                            transcribing = event.transcribing
                            if (!event.transcribing && event.state.status == ASRStatus.Listening &&
                                event.state.transcript.isBlank() && event.state.voiceTurn.itemId == null) {
                                recoveryBudget.beginListening(monotonicTimeMs())
                            } else recoveryBudget.stopListening(monotonicTimeMs())
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
                        if (audioAllowed() && event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                            captureMicrophoneRevision == microphoneRevision.get()) {
                            checkAcoustics()
                            if (event.epoch != captureEpoch) {
                                event.acknowledged.cancel()
                                continue
                            }
                            // ACK before the recorder releases AEC. An intentional end must not look
                            // like a recording-time effect failure and destroy its waiting final text.
                            recoveryBudget.stopListening(monotonicTimeMs())
                            interruptForHumanCapture(event.epoch)
                            transcribing = true
                            val transcript = correctTranscript(event.state.transcript)
                            mutableState.update { it.withTranscript(transcript, event.epoch) }
                            event.acknowledged.complete(Unit)
                        } else event.acknowledged.cancel()
                    }
                    is Event.SupplementaryReply -> if (connected && audioAllowed()) observeReply(event.reply)
                        else cancelPendingReply(event.reply)
                    is Event.RequestSupplementaryReply -> {
                        // Admit before creating any model/queue work, in the same event owner
                        // as speech. A late camera capture may not jump into a spoken sentence.
                        if (!event.accepted.isActive) continue
                        try {
                            if (!connected || !audioAllowed() || transcribing || tailWaiting ||
                                asr?.state?.value?.voiceTurn?.itemId != null || playing != null ||
                                pending.isNotEmpty() || ready.isNotEmpty() || !event.stillCurrent()) {
                                event.accepted.complete(null)
                            } else {
                                val reply = event.createReply()
                                observeReply(reply)
                                event.accepted.complete(reply)
                            }
                        } catch (error: Exception) {
                            event.accepted.completeExceptionally(error)
                            if (error is CancellationException && !isActive) throw error
                        }
                    }
                    is Event.Utterance -> if (audioAllowed() && event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) {
                        recoveryBudget.stopListening(monotonicTimeMs())
                        capture?.join() // Recorder is disposed before input reaches the chat queue.
                        capture = null
                        asr = null
                        transcribing = false
                        if (event.text.isNotBlank()) {
                            interruptForHumanCapture(event.epoch)
                            val transcript = correctTranscript(event.text)
                            mutableState.update { it.withTranscript(transcript, event.epoch) }
                            try { observeReply(enqueueRecognizedMessage?.invoke(transcript) ?: enqueueMessage(transcript.corrected)) }
                            catch (error: Exception) {
                                if (error is CancellationException && !isActive) throw error
                                pauseReplies(error)
                            }
                        }
                    }
                    is Event.CaptureFailed -> if (audioAllowed() && event.epoch == captureEpoch && mutableState.value.microphoneEnabled &&
                        captureMicrophoneRevision == microphoneRevision.get()) {
                        val failure = event.error as? VoiceSessionFailure
                            ?: VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_LISTEN, event.error, hadSpeech = true)
                        val retryDelay = recoveryBudget.nextRetry(monotonicTimeMs(), failure, connected)
                        if (retryDelay == null) {
                            if (!connected || failure.stage == VoiceFailureStage.CONNECT) throw failure
                            pauseRecognition(failure)
                            continue
                        }
                        closeCapture()
                        awaitingRetry = true
                        val revision = ++recoveryRevision
                        mutableState.update { it.copy(reconnecting = true,
                            recoveryNotice = "识别连接暂断，正在恢复（本轮 ${recoveryBudget.attemptsUsed}/3）；不会重发旧录音或消息。[ASR_IDLE_RECONNECT]") }
                        recoveryJob = launch { delay(retryDelay); events.send(Event.RetryCapture(revision)) }
                    }
                    is Event.Reply -> pending[event.id]?.let {
                        if (event.error != null) pauseReplies(event.error)
                        else {
                            it.completed = true
                            it.text = event.text
                        }
                    }
                    is Event.PlaybackEnded -> if (playing?.id == event.id) {
                        playing = null
                        playback = null
                        acousticMonitor?.cancel()
                        acousticMonitor = null
                        event.error?.let {
                            val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.TTS, it)
                            if (!failure.network) throw failure
                            runCatching { stopSpeaking() }
                            mutableState.update { state -> state.copy(recoveryNotice = failure.message) }
                        }
                        if (capture == null) waitForTail()
                    }
                    is Event.TailEnded -> if (event.id == tailEpoch) tailWaiting = false
                    is Event.MicrophoneChanged -> {
                        if (!mutableState.value.microphoneEnabled) cancelRecovery()
                        if (capture != null && (!mutableState.value.microphoneEnabled ||
                                captureMicrophoneRevision != microphoneRevision.get())) closeCapture()
                        if (!mutableState.value.microphoneEnabled) {
                            mutableState.update { it.copy(transcript = "", originalTranscript = null) }
                            if (!connected) markConnected()
                        }
                    }
                    Event.SpeakerChanged -> Unit // Gain only: synthesis, queue and timeline continue.
                    is Event.OutputFailed -> throw VoiceRecoveryPolicy.failure(VoiceFailureStage.AUDIO, event.error)
                    Event.CheckAcoustics -> checkAcoustics()
                    Event.FocusChanged -> Unit // The synchronous revision fence is applied above.
                    is Event.RetryCapture -> if (event.revision == recoveryRevision &&
                        audioAllowed() && mutableState.value.microphoneEnabled) {
                        recoveryJob = null
                        awaitingRetry = false
                        mutableState.update { it.copy(reconnecting = false,
                            recoveryNotice = "正在重新连接识别；旧录音没有重发。[ASR_IDLE_RECONNECT]") }
                    }
                    is Event.CheckReplies -> if (event.revision == replyRevision.get() && mutableState.value.replyBlocked) {
                        // A slow external check must not hold microphone/service teardown open.
                        // Its only return path is this old channel plus the revision fence.
                        replyResumeJob?.cancel()
                        replyResumeJob = scope.launch {
                            val ready = checkRepliesReady(event.revision, 60_000L, event.checkReady)
                            events.trySend(Event.RepliesChecked(event.revision, ready))
                        }
                    }
                    is Event.RepliesChecked -> if (event.revision == replyRevision.get() && mutableState.value.replyBlocked) {
                        replyResumeJob = null
                        mutableState.update { it.copy(replyBlocked = !event.ready, replyResumeChecking = false,
                            replyNotice = if (event.ready) null else
                                "回复、队列或工具结果尚待核对；请回聊天处理，等回复结束后再点继续回复。通话仍保留。[MODEL_RESUME_BLOCKED]") }
                        if (event.ready) {
                            // Only future audio is enabled. Old input and playback were detached.
                            muteOutput?.invoke(!mutableState.value.speakerEnabled || mutableState.value.audioFocusSuspended)
                            runCatching { onReplyPauseChanged(false) }
                        }
                    }
                }
            }
        } finally {
            capture?.cancel()
            playback?.cancel()
            acousticMonitor?.cancel()
            recoveryJob?.cancel()
            replyResumeJob?.cancel()
            pending.values.forEach { it.observer?.cancel() }
            // Leaving detaches observers; accepted chat work is not globally canceled.
        }
    }

    private suspend fun listen(asr: ASRController, onConnected: suspend () -> Unit,
        onCaptureEnded: suspend (ASRState) -> Unit,
        onState: (ASRState, Boolean) -> Unit): String {
        var stage = VoiceFailureStage.ASR_START
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also { check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Unable to start speech recognition" } }
            }
            stage = VoiceFailureStage.CONNECT
            onConnected()
            stage = VoiceFailureStage.ASR_LISTEN
            mutableState.update { if (it.recoveryNotice?.contains("[ASR_IDLE_RECONNECT]") == true)
                it.copy(recoveryNotice = "识别连接已恢复；旧录音没有重发。[ASR_RECONNECTED]") else it }
            // Realtime providers use server VAD; batch adapters use local >=3s endpointing.
            val ended = asr.state.onEach {
                check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Speech recognition disconnected" }
                onState(it, false)
            }.first { it.voiceTurn.speechEnded }
            onCaptureEnded(ended)
            stage = VoiceFailureStage.ASR_FINAL
            asr.pauseCapture()
            onState(ended, true)
            return withTimeout(asr.finalTranscriptTimeoutMs) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } catch (error: Exception) {
            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
            val state = asr.state.value
            throw VoiceRecoveryPolicy.failure(stage, error, hadSpeech = stage == VoiceFailureStage.ASR_FINAL ||
                state.transcript.isNotBlank() || state.voiceTurn.itemId != null)
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
            canInterruptPlayback = if (enabled) it.canInterruptPlayback else false,
            recoveryNotice = if (enabled && it.recoveryNotice?.contains("[ASR_MANUAL_RESUME_REQUIRED]") == true)
                "收音已恢复，请重新说刚才那句；旧录音不会重发。[ASR_MANUAL_RESUMED]" else it.recoveryNotice) }
        if (!enabled) runCatching { activeAsr?.pauseCapture() }
        controls?.trySend(Event.MicrophoneChanged(enabled))
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        if (controls == null || !mutableState.value.isActive) return
        try { muteOutput?.invoke(!enabled || mutableState.value.audioFocusSuspended || mutableState.value.replyBlocked) }
        catch (error: Exception) { controls?.trySend(Event.OutputFailed(error)); return }
        mutableState.update { it.copy(speakerEnabled = enabled) }
        controls?.trySend(Event.SpeakerChanged)
    }

    /** Focus loss stops physical audio immediately; logical user mute choices remain unchanged. */
    fun setAudioFocusSuspended(suspended: Boolean) {
        if (controls == null || !mutableState.value.isActive) return
        if (suspended) {
            focusRevision.incrementAndGet()
            microphoneRevision.incrementAndGet()
        }
        mutableState.update { it.copy(audioFocusSuspended = suspended,
            canInterruptPlayback = if (suspended) false else it.canInterruptPlayback,
            recoveryNotice = if (it.recoveryNotice?.contains("[ASR_MANUAL_RESUME_REQUIRED]") == true) it.recoveryNotice
                else if (suspended) "音频已暂停；本句未发送的识别和被打断的朗读不会自动重放。[AUDIO_FOCUS_PAUSED]"
                else if (it.replyBlocked) "系统音频已归还；回复仍暂停，请先核对聊天。[AUDIO_FOCUS_RESUMED]"
                else "音频已恢复；没有重发旧录音、消息或被打断的朗读。[AUDIO_FOCUS_RESUMED]") }
        if (suspended) {
            runCatching { activeAsr?.pauseCapture() }
            runCatching { interruptCurrentAudio?.invoke() }
        }
        try { muteOutput?.invoke(suspended || mutableState.value.replyBlocked || !mutableState.value.speakerEnabled) }
        catch (error: Exception) { controls?.trySend(Event.OutputFailed(error)) }
        controls?.trySend(Event.FocusChanged)
    }

    /** Explicit action only. The asynchronous result belongs to this session's event channel. */
    fun requestReplyResume(checkReady: suspend () -> Boolean): Boolean {
        val events = controls ?: return false
        val current = mutableState.value
        if (!current.isActive || !current.replyBlocked || current.replyResumeChecking) return false
        val revision = replyRevision.incrementAndGet()
        mutableState.update { it.copy(replyResumeChecking = true) }
        return events.trySend(Event.CheckReplies(revision, checkReady)).isSuccess.also { sent ->
            if (!sent && controls === events) mutableState.update { it.copy(replyResumeChecking = false) }
        }
    }

    /** UI acknowledgement only; keeps this utterance's raw/corrected audit intact. */
    fun dismissCorrectionNotice(eventId: Long) {
        mutableState.update { it.copy(correctionNotice = it.correctionNotice.dismiss(eventId)) }
    }

    /** A camera observation uses the same ordered playback and hang-up cancellation as speech. */
    fun acceptSupplementaryReply(reply: Deferred<String?>): Boolean =
        controls?.trySend(Event.SupplementaryReply(reply))?.isSuccess == true

    /** Periodic camera work is created only after the live speech event owner admits it. */
    suspend fun enqueueSupplementaryReply(
        stillCurrent: () -> Boolean,
        createReply: () -> Deferred<String?>,
    ): Deferred<String?>? {
        val events = controls ?: return null
        val accepted = CompletableDeferred<Deferred<String?>?>(currentCoroutineContext()[Job])
        if (!events.trySend(Event.RequestSupplementaryReply(stillCurrent, createReply, accepted)).isSuccess) {
            accepted.complete(null)
        }
        return accepted.await()
    }

    fun stop() {
        val recorder = activeAsr
        microphoneRevision.incrementAndGet()
        replyRevision.incrementAndGet()
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
