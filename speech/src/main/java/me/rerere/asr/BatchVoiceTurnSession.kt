package me.rerere.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Pure single-utterance owner, shared by the Android microphone and synthetic PCM tests. */
internal class BatchVoiceTurnSession(
    scope: CoroutineScope,
    sampleRate: Int,
    private val transcribe: suspend (ByteArray) -> String,
    segmentMs: Int = 15_000,
    queueCapacity: Int = 2,
) {
    private val lock = Any()
    private val detector = LocalVoiceEndpointDetector(sampleRate, segmentMs = segmentMs)
    private val segments = Channel<ByteArray>(queueCapacity)
    private val id = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(ASRState(status = ASRStatus.Listening, isAvailable = true))
    val state = mutableState.asStateFlow()
    private var live = true
    private var capturePaused = false
    private val transcript = StringBuilder()
    private val worker: Job = scope.launch(Dispatchers.IO) {
        try {
            for (pcm in segments) {
                val text = try { transcribe(pcm).trim() } finally { pcm.fill(0) }
                synchronized(lock) {
                    if (!live) return@launch // Also rejects a non-cooperative, late transport callback.
                    check(transcript.length + text.length <= 64_000) { "ASR_TEXT_LIMIT" }
                    if (text.isNotEmpty()) {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(text)
                    }
                    mutableState.value = mutableState.value.copy(transcript = transcript.toString())
                }
            }
            synchronized(lock) {
                if (live && mutableState.value.voiceTurn.speechEnded) {
                    mutableState.value = mutableState.value.copy(
                        voiceTurn = mutableState.value.voiceTurn.completed(id, transcript.toString()))
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail(if (error.message == "ASR_TEXT_LIMIT")
                "本句识别内容过长，未提交不完整结果；请分几句重说。[ASR_TEXT_LIMIT]"
            else (error as? BatchVoiceRecognitionException)?.message
                ?: "语音识别未完成，本句未提交；请检查识别服务后重试。[ASR_BATCH_FAILED]")
        }
    }

    val isCaptureOpen: Boolean get() = synchronized(lock) { live && !capturePaused && !detector.ended }

    fun acceptPcm(bytes: ByteArray, count: Int = bytes.size) = synchronized(lock) {
        if (!live || capturePaused || detector.ended) return@synchronized
        applyEvents(detector.accept(bytes, count))
    }

    private fun applyEvents(events: List<LocalVoiceEndpointDetector.Event>) {
        for (event in events) {
            if (!live) return
            when (event) {
                LocalVoiceEndpointDetector.Event.Started -> mutableState.value = mutableState.value.copy(
                    voiceTurn = mutableState.value.voiceTurn.started(id))
                is LocalVoiceEndpointDetector.Event.Segment -> if (!segments.trySend(event.pcm).isSuccess) {
                    event.pcm.fill(0)
                    fail("识别服务处理较慢，本句未提交不完整结果；请稍后分句重说。[ASR_BATCH_BACKLOG]")
                }
                LocalVoiceEndpointDetector.Event.Ended -> {
                    mutableState.value = mutableState.value.copy(voiceTurn = mutableState.value.voiceTurn.stopped(id))
                    segments.close() // Drains every accepted segment in order, then emits exactly one final.
                }
            }
        }
    }

    fun finish() = synchronized(lock) {
        if (live && !capturePaused) applyEvents(detector.finish())
    }

    /** Normal endpoint ACK retains the HTTP final; any earlier pause discards this utterance. */
    fun pauseCapture() = synchronized(lock) {
        capturePaused = true
        if (!mutableState.value.voiceTurn.speechEnded) cancel()
    }

    fun fail(message: String) = synchronized(lock) {
        if (!live) return@synchronized
        live = false
        detector.clear()
        drainAndCancel()
        mutableState.value = mutableState.value.copy(status = ASRStatus.Error, errorMessage = message)
    }

    fun cancel() = synchronized(lock) {
        live = false
        detector.clear()
        drainAndCancel()
        transcript.clear()
        mutableState.value = ASRState(isAvailable = true)
    }

    private fun drainAndCancel() {
        segments.close()
        while (true) (segments.tryReceive().getOrNull() ?: break).fill(0)
        worker.cancel()
    }
}

/** Safe public message only: no raw response, endpoint, credentials or transcript in diagnostics. */
internal class BatchVoiceRecognitionException(message: String) : Exception(message)
