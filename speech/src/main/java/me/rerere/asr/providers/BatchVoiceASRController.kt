package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRPcmObserver
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.BatchVoiceTurnSession
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.OkHttpClient

/**
 * Continuous-call adapter for batch-only APIs. The call owns/recreates one adapter per
 * utterance; local endpointing submits after a >=3s pause. Upload/recognition adds latency.
 * Half-duplex deliberately: unlike a streaming microphone, this does not promise barge-in/AEC.
 */
class BatchVoiceASRController(
    private val context: Context,
    client: OkHttpClient,
    provider: ASRProviderSetting,
) : ASRController {
    private val sampleRate = when (provider) {
        is ASRProviderSetting.MiMo -> provider.sampleRate
        is ASRProviderSetting.Step -> provider.sampleRate
        else -> error("Not a batch voice provider")
    }
    // At most two queued segments plus the in-flight request, each bounded to 18 seconds.
    override val finalTranscriptTimeoutMs = 60_000L
    override val supportsConcurrentPlayback = false
    private val transcriber = BatchVoiceTranscriber(client, provider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val pcmObserver = ASRPcmObserver()
    private val mutableState = MutableStateFlow(ASRState(isAvailable = true))
    override val state = mutableState.asStateFlow()
    private var session: BatchVoiceTurnSession? = null
    private var recorder: AudioRecord? = null
    private var recording: Job? = null
    private var disposed = false
    private var ready = false
    private var paused = false

    override fun setPcmObserver(observer: ((ByteArray, Int) -> Unit)?) = pcmObserver.set(observer)

    override fun start(onTranscriptChange: (String) -> Unit) = synchronized(lock) {
        if (disposed || session != null) return@synchronized
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = ASRState(status = ASRStatus.Error, isAvailable = true,
                errorMessage = "请允许麦克风权限后重新发起通话。[ASR_MIC_PERMISSION]")
            return@synchronized
        }
        val owner = try { BatchVoiceTurnSession(scope, sampleRate, transcriber::transcribe) }
        catch (_: IllegalArgumentException) {
            mutableState.value = ASRState(status = ASRStatus.Error, isAvailable = true,
                errorMessage = "语音采样率配置无效，请在识别设置中选择有效采样率。[ASR_SAMPLE_RATE]")
            return@synchronized
        }
        session = owner
        mutableState.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        scope.launch {
            var previousTranscript = ""
            owner.state.collect { value -> synchronized(lock) {
                if (!disposed && session === owner && ready) {
                    mutableState.value = value.copy(amplitudes = mutableState.value.amplitudes)
                    if (value.transcript != previousTranscript) {
                        previousTranscript = value.transcript
                        onTranscriptChange(value.transcript)
                    }
                    if (!owner.isCaptureOpen) releaseRecorder()
                }
            } }
        }
        startRecorder(owner)
    }

    override fun pauseCapture() = synchronized(lock) {
        paused = true
        pcmObserver.set(null)
        session?.pauseCapture()
        recording?.cancel()
        releaseRecorder()
    }

    override fun stop() = synchronized(lock) {
        session?.finish()
        pauseCapture()
    }

    override fun dispose() = synchronized(lock) {
        disposed = true // Fence before either cancellation or AudioRecord can complete a callback.
        paused = true
        pcmObserver.set(null)
        session?.cancel()
        session = null
        recording?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(owner: BatchVoiceTurnSession) {
        val pcmEpoch = pcmObserver.captureEpoch()
        recording = scope.launch(Dispatchers.IO) {
            var ownedRecorder: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val bufferSize = maxOf(minimum, sampleRate / 10 * 2, 4096)
                val device = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
                ownedRecorder = device
                synchronized(lock) {
                    ensureActive()
                    check(!disposed && !paused && session === owner)
                    check(device.state == AudioRecord.STATE_INITIALIZED)
                    recorder = device
                    device.startRecording()
                    check(device.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    ready = true
                    mutableState.value = owner.state.value
                }
                val buffer = ByteArray(sampleRate / 50 * 2)
                while (isActive && owner.isCaptureOpen) {
                    val count = device.read(buffer, 0, buffer.size)
                    if (count < 0) error("AudioRecord read failed")
                    if (count > 0) synchronized(lock) {
                        if (!disposed && !paused && session === owner && owner.isCaptureOpen) {
                            pcmObserver.emit(pcmEpoch, buffer, count, sampleRate)
                            owner.acceptPcm(buffer, count)
                            mutableState.value = mutableState.value.copy(amplitudes =
                                mutableState.value.amplitudes.appendAmplitude(calculateRmsAmplitude(buffer, count)))
                        }
                    }
                }
                buffer.fill(0)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(lock) {
                    if (!disposed && !paused && session === owner && owner.isCaptureOpen) {
                        ready = true
                        owner.fail("麦克风无法继续录音，本句未提交；请检查系统音频和权限后重试。[ASR_MIC_CAPTURE]")
                        mutableState.value = owner.state.value
                    }
                }
            } finally {
                synchronized(lock) { releaseRecorder(ownedRecorder) }
                // An object constructed before cancellation may never have been attached.
                runCatching { ownedRecorder?.stop() }
                runCatching { ownedRecorder?.release() }
            }
        }
    }

    private fun releaseRecorder(expected: AudioRecord? = null) {
        if (expected != null && recorder !== expected) return
        val previous = recorder
        recorder = null
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
    }
}
