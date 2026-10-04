package me.rerere.rikkahub.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRController
import me.rerere.asr.ASRCorrectionResult
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionController
import me.rerere.rikkahub.ui.pages.chat.VoiceAudioFocusState
import me.rerere.rikkahub.ui.pages.chat.VoiceAudioFocusEvent
import me.rerere.rikkahub.ui.pages.chat.nextVoiceAudioFocusState
import kotlin.uuid.Uuid

enum class OrbisVoiceCallEndReason { USER, AI_END, ERROR, SERVICE_STOPPED, STARTUP_FAILED }

data class OrbisVoiceCallEnd(
    val callId: String,
    val reason: OrbisVoiceCallEndReason,
    val connectedAtMillis: Long?,
    val endedAtMillis: Long,
    val durationMillis: Long?,
    val error: String? = null,
    val endReasonText: String? = null,
)

data class OrbisVoiceCallState(
    val callId: String? = null,
    val conversationId: Uuid? = null,
    val title: String = "语音通话",
    val connectedAtMillis: Long? = null,
    val connectedElapsedMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val durationMillis: Long? = null,
    val ending: Boolean = false,
    val error: String? = null,
    val returnScreenDismissed: Boolean = false,
    val audioInterruption: String? = null,
    val canResumeAudio: Boolean = false,
    val reviewingReplies: Boolean = false,
) {
    val isActive: Boolean get() = callId != null && endedAtMillis == null
}

/** One process-owned call. Neither activity recreation nor leaving the chat owns its lifetime. */
class OrbisVoiceCallRuntime private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: OrbisVoiceCallRuntime? = null

        fun get(context: Context): OrbisVoiceCallRuntime = instance ?: synchronized(this) {
            instance ?: OrbisVoiceCallRuntime(context.applicationContext).also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableCallState = MutableStateFlow(OrbisVoiceCallState())
    val callState = mutableCallState.asStateFlow()
    private var current: Session? = null
    private var displayedToken: String? = null

    /** Presentation only. Never cancel archiving or reopen the microphone from the return screen. */
    @MainThread
    fun dismissReturnScreen(callId: String) {
        val state = mutableCallState.value
        if (state.callId == callId && !state.isActive) {
            mutableCallState.value = state.copy(returnScreenDismissed = true)
        }
    }

    val voiceSession = VoiceSessionController(scope, context::getString) { text ->
        val session = checkNotNull(current) { "Voice call has ended" }
        check(!session.ending && session.connectedAtMillis != null) { "Voice call is not connected" }
        session.enqueueMessage(text)
    }

    private class Session(
        val callId: String,
        val conversationId: Uuid,
        val enqueueMessage: (String) -> Deferred<String?>,
        val onEnded: suspend (OrbisVoiceCallEnd) -> Unit,
        val checkReplyResume: suspend () -> Boolean,
    ) {
        // A fresh token also rejects delayed notification actions from a previous call ID.
        val token = Uuid.random().toString()
        val foregroundReady = CompletableDeferred<Unit>()
        var startupJob: Job? = null
        var service: OrbisVoiceCallForegroundService? = null
        var connectedAtMillis: Long? = null
        var connectedElapsedMillis: Long? = null
        var ending = false
        var focus: AudioFocusRequest? = null
        var previousAudioMode: Int? = null
        var focusState = VoiceAudioFocusState.AVAILABLE
    }

    /**
     * Must be called from a visible activity after RECORD_AUDIO permission is granted.
     * True transfers cleanup ownership to onEnded, including asynchronous startup failure.
     * False means another call owns the runtime and no callback will be made for this request.
     */
    @MainThread
    fun start(
        callId: String,
        conversationId: Uuid,
        title: String,
        createAsr: () -> ASRController,
        enqueueMessage: (String) -> Deferred<String?>,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
        initialMicrophoneEnabled: Boolean = true,
        initialSpeakerEnabled: Boolean = true,
        initialAssistantText: String? = null,
        cancelPendingReply: (Deferred<String?>) -> Unit = {},
        onConnected: suspend (Long) -> Unit = {},
        onEnded: suspend (OrbisVoiceCallEnd) -> Unit,
        setOutputMuted: (Boolean) -> Unit = {},
        requestOpening: (suspend () -> Deferred<String?>?)? = null,
        correctTranscript: (String) -> ASRCorrectionResult = { ASRCorrectionResult(it, it) },
        enqueueRecognizedMessage: ((ASRCorrectionResult) -> Deferred<String?>)? = null,
        checkReplyResume: suspend () -> Boolean = { false },
    ): Boolean {
        if (current != null) return false
        val session = Session(callId, conversationId, enqueueMessage, onEnded, checkReplyResume)
        current = session
        displayedToken = session.token
        mutableCallState.value = OrbisVoiceCallState(callId, conversationId, title)
        if (initialMicrophoneEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(session, OrbisVoiceCallEndReason.STARTUP_FAILED, "需要麦克风权限才能开始通话")
            return true
        }
        session.startupJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                OrbisVoiceCallForegroundService.start(context, session.token)
                // ASR must not open its microphone until Android has accepted the foreground type.
                withTimeout(10_000) { session.foregroundReady.await() }
                if (current !== session || session.ending) return@launch
                val audio = context.getSystemService(AudioManager::class.java)
                check(audio.mode == AudioManager.MODE_NORMAL) { "手机正在处理其他语音通话" }
                // Do not take over ordinary dictation or another recorder, including on a
                // muted answer: its microphone switch must not imply that old capture stopped.
                check(audio.activeRecordingConfigurations.isEmpty()) { "请先结束当前录音或听写" }
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener { change ->
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { handleAudioFocusChange(session, change) }
                    }.build()
                check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "暂时无法取得通话音频" }
                session.focus = focus
                session.previousAudioMode = audio.mode
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                voiceSession.start(
                    createAsr = createAsr,
                    speak = speak,
                    stopSpeaking = stopSpeaking,
                    initialMicrophoneEnabled = initialMicrophoneEnabled,
                    initialSpeakerEnabled = initialSpeakerEnabled,
                    initialAssistantText = initialAssistantText,
                    cancelPendingReply = cancelPendingReply,
                    setOutputMuted = setOutputMuted,
                    requestOpening = requestOpening?.let { opening -> {
                        check(current === session && !session.ending && session.connectedAtMillis != null) {
                            "Voice call has ended"
                        }
                        opening()
                    } },
                    correctTranscript = correctTranscript,
                    enqueueRecognizedMessage = { transcript ->
                        check(current === session && !session.ending && session.connectedAtMillis != null) {
                            "Voice call has ended"
                        }
                        enqueueRecognizedMessage?.invoke(transcript) ?: session.enqueueMessage(transcript.corrected)
                    },
                    isHeadsetConnected = { confirmedHeadsetRoute(audio) },
                    onConnected = {
                        check(current === session && !session.ending) { "Voice call has ended" }
                        val connectedAt = System.currentTimeMillis()
                        session.connectedAtMillis = connectedAt
                        session.connectedElapsedMillis = SystemClock.elapsedRealtime()
                        mutableCallState.value = mutableCallState.value.copy(connectedAtMillis = connectedAt,
                            connectedElapsedMillis = session.connectedElapsedMillis)
                        session.service?.refresh(session.token)
                        // Await the durable start marker before VoiceSessionController can enqueue.
                        onConnected(connectedAt)
                    },
                    onEnded = { error ->
                        if (current === session && !session.ending) {
                            finish(session, OrbisVoiceCallEndReason.ERROR, error ?: "语音会话意外停止")
                        }
                    },
                    onReplyPauseChanged = { if (current === session && !session.ending) session.service?.refresh(session.token) },
                )
                if (session.focusState != VoiceAudioFocusState.AVAILABLE) voiceSession.setAudioFocusSuspended(true)
            } catch (e: CancellationException) {
                if (!session.ending && current === session) {
                    finish(session, OrbisVoiceCallEndReason.STARTUP_FAILED, "语音服务未能及时启动")
                }
            } catch (e: Exception) {
                finish(session, OrbisVoiceCallEndReason.STARTUP_FAILED, "无法启动后台语音服务，请检查录音权限和其他音频占用。[CALL_STARTUP_FAILED]")
            }
        }
        return true
    }

    @MainThread
    fun hangUp() {
        current?.let { finish(it, OrbisVoiceCallEndReason.USER) }
    }

    /** Caller has durably checked assistant ownership; never end a different or future call. */
    @MainThread
    internal fun endFromAssistant(callId: String, conversationId: String, reason: String?): Boolean {
        val session = current?.takeIf { it.callId == callId &&
            it.conversationId.toString() == conversationId && !it.ending && it.connectedAtMillis != null }
            ?: return false
        require(reason == null || reason.length <= 2000)
        finish(session, OrbisVoiceCallEndReason.AI_END, endReasonText = reason)
        return true
    }

    @MainThread
    fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        val session = current?.takeUnless { it.ending } ?: return false
        if (enabled) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            if (session.service?.enableMicrophone(session.token) != true) return false
        }
        voiceSession.setMicrophoneEnabled(enabled)
        return true
    }

    @MainThread
    fun setSpeakerEnabled(enabled: Boolean) { voiceSession.setSpeakerEnabled(enabled) }

    /** Check host recovery/queue state without replaying work or changing audio-focus ownership. */
    @MainThread
    fun resumeReplies(callId: String): Boolean {
        val session = current?.takeIf { it.callId == callId && !it.ending && it.connectedAtMillis != null }
            ?: return false
        return voiceSession.requestReplyResume {
            if (current !== session || session.ending) false
            else session.checkReplyResume() && current === session && !session.ending
        }
    }

    @MainThread
    fun setReviewingReplies(callId: String, reviewing: Boolean) {
        if (current?.let { it.callId == callId && !it.ending } != true) return
        mutableCallState.value = mutableCallState.value.copy(reviewingReplies = reviewing)
    }

    /** Explicit human action only. A stale overlay cannot acquire focus for another call. */
    @MainThread
    fun resumeAudio(callId: String): Boolean {
        val session = current?.takeIf { it.callId == callId && !it.ending &&
            it.focusState != VoiceAudioFocusState.AVAILABLE } ?: return false
        val audio = context.getSystemService(AudioManager::class.java)
        if (!safeToResumeAudio(audio)) {
            publishFocusPause(session, "其他通话或录音仍在使用音频，请结束后再点击恢复。[AUDIO_BUSY]", manual = true)
            return false
        }
        val focus = session.focus ?: return false
        if (runCatching { audio.requestAudioFocus(focus) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publishFocusPause(session, "音频仍被占用，未开始收音；请稍后点击恢复。[AUDIO_FOCUS_DENIED]", manual = true)
            return false
        }
        if (current !== session || session.ending) return false
        return resumeGrantedAudio(session, audio)
    }

    private fun safeToResumeAudio(audio: AudioManager): Boolean = runCatching {
        (!voiceSession.state.value.microphoneEnabled ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) &&
            audio.mode != AudioManager.MODE_IN_CALL && audio.mode != AudioManager.MODE_RINGTONE &&
            audio.activeRecordingConfigurations.isEmpty()
    }.getOrDefault(false)

    private fun resumeGrantedAudio(session: Session, audio: AudioManager): Boolean {
        if (current !== session || session.ending) return false
        if (!runCatching {
                if (audio.mode == AudioManager.MODE_NORMAL) audio.mode = AudioManager.MODE_IN_COMMUNICATION
                true
            }.getOrDefault(false)) {
            publishFocusPause(session, "音频模式未能恢复，麦克风保持暂停。请稍后重试。[AUDIO_ROUTE_UNAVAILABLE]", manual = true)
            return false
        }
        session.focusState = VoiceAudioFocusState.AVAILABLE
        mutableCallState.value = mutableCallState.value.copy(audioInterruption = null, canResumeAudio = false)
        voiceSession.setAudioFocusSuspended(false)
        session.service?.refresh(session.token)
        return true
    }

    private fun publishFocusPause(session: Session, message: String, manual: Boolean) {
        if (current !== session || session.ending) return
        if (manual) session.focusState = VoiceAudioFocusState.MANUAL_PAUSE
        voiceSession.setAudioFocusSuspended(true)
        mutableCallState.value = mutableCallState.value.copy(audioInterruption = message, canResumeAudio = manual)
        session.service?.refresh(session.token)
    }

    private fun handleAudioFocusChange(session: Session, change: Int) {
        if (current !== session || session.ending) return
        val event = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> VoiceAudioFocusEvent.PERMANENT_LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> VoiceAudioFocusEvent.TRANSIENT_LOSS
            AudioManager.AUDIOFOCUS_GAIN -> VoiceAudioFocusEvent.GAIN
            else -> return
        }
        val before = session.focusState
        val after = nextVoiceAudioFocusState(before, event)
        if (event == VoiceAudioFocusEvent.GAIN && after == before) return
        session.focusState = after
        if (after != VoiceAudioFocusState.AVAILABLE) {
            val manual = after == VoiceAudioFocusState.MANUAL_PAUSE
            publishFocusPause(session, if (manual) "被其他音频占用，麦克风和朗读已暂停。请点击恢复。[AUDIO_FOCUS_LOSS]"
                else "其他音频暂时占用，麦克风和朗读已暂停，等待系统归还。[AUDIO_FOCUS_TRANSIENT]", manual)
            return
        }
        val audio = context.getSystemService(AudioManager::class.java)
        if (!safeToResumeAudio(audio)) {
            publishFocusPause(session, "音频尚未空闲，麦克风保持暂停。请稍后点击恢复。[AUDIO_BUSY]", manual = true)
            return
        }
        // The system granted focus; never request it again to compete with another application.
        resumeGrantedAudio(session, audio)
    }

    @MainThread
    fun hangUpCall(callId: String) { current?.takeIf { it.callId == callId }?.let { finish(it, OrbisVoiceCallEndReason.STARTUP_FAILED, "来电请求已结束") } }

    /** Notification actions are generation-specific, so an old notification cannot end a new call. */
    @MainThread
    internal fun hangUp(token: String) {
        current?.takeIf { it.token == token }?.let { finish(it, OrbisVoiceCallEndReason.USER) }
    }

    internal fun acceptsService(token: String): Boolean = current?.let { it.token == token && !it.ending } == true

    internal fun serviceReady(token: String, service: OrbisVoiceCallForegroundService) {
        current?.takeIf { it.token == token && !it.ending }?.let {
            it.service = service
            it.foregroundReady.complete(Unit)
        }
    }

    internal fun serviceFailed(token: String, error: String) {
        current?.takeIf { it.token == token }?.let {
            finish(it, if (it.connectedAtMillis == null) OrbisVoiceCallEndReason.STARTUP_FAILED else OrbisVoiceCallEndReason.SERVICE_STOPPED, error)
        }
    }

    internal fun serviceDestroyed(token: String) {
        current?.takeIf { it.token == token && !it.ending }?.let {
            it.service = null
            finish(it, OrbisVoiceCallEndReason.SERVICE_STOPPED, "系统结束了语音服务。[FOREGROUND_STOPPED]")
        }
    }

    private fun finish(session: Session, reason: OrbisVoiceCallEndReason, error: String? = null,
        endReasonText: String? = null) {
        if (current !== session || session.ending) return
        session.ending = true
        val endedAt = System.currentTimeMillis()
        val duration = session.connectedElapsedMillis?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
        val ended = OrbisVoiceCallEnd(session.callId, reason, session.connectedAtMillis, endedAt, duration, error, endReasonText)
        // This is deliberately synchronous: summary/archive work never keeps the microphone open.
        voiceSession.stop()
        val audio = context.getSystemService(AudioManager::class.java)
        session.previousAudioMode?.let { previous -> runCatching { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previous } }
        session.focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        session.focus = null; session.previousAudioMode = null
        session.service?.stopForCall(session.token)
        session.service = null
        // Publish the return-screen state only after capture and playback have stopped.
        mutableCallState.value = mutableCallState.value.copy(
            endedAtMillis = endedAt, durationMillis = duration, ending = true, error = error,
            audioInterruption = null, canResumeAudio = false,
        )
        scope.launch {
            session.startupJob?.cancelAndJoin()
            voiceSession.stopAndJoin()
            if (current === session) current = null
            try {
                session.onEnded(ended)
            } catch (e: Exception) {
                // The archive owner keeps its recovery state; failure must never look like success.
                if (displayedToken == session.token && current == null) {
                    mutableCallState.value = mutableCallState.value.copy(error = "通话已挂断，记录待恢复。[CALL_END_PERSIST_FAILED]")
                }
            } finally {
                if (displayedToken == session.token && current == null) {
                    mutableCallState.value = mutableCallState.value.copy(ending = false)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmedHeadsetRoute(audio: AudioManager): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        audio.communicationDevice?.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID)
    } else audio.isWiredHeadsetOn || audio.isBluetoothScoOn
}
