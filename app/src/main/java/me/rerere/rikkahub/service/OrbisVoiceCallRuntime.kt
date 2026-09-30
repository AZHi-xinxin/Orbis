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
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionController
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
    ): Boolean {
        if (current != null) return false
        val session = Session(callId, conversationId, enqueueMessage, onEnded)
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
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { change ->
                        if (change < 0) scope.launch { if (current === session) finish(session, OrbisVoiceCallEndReason.ERROR, "通话被其他音频中断") }
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
                )
            } catch (e: CancellationException) {
                if (!session.ending && current === session) {
                    finish(session, OrbisVoiceCallEndReason.STARTUP_FAILED, "语音服务未能及时启动")
                }
            } catch (e: Exception) {
                finish(session, OrbisVoiceCallEndReason.STARTUP_FAILED, e.message ?: "无法启动后台语音服务")
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
        current?.takeIf { it.token == token }?.let { finish(it, OrbisVoiceCallEndReason.STARTUP_FAILED, error) }
    }

    internal fun serviceDestroyed(token: String) {
        current?.takeIf { it.token == token && !it.ending }?.let {
            it.service = null
            finish(it, OrbisVoiceCallEndReason.SERVICE_STOPPED, "系统结束了语音服务")
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
                    mutableCallState.value = mutableCallState.value.copy(error = e.message ?: "通话已挂断，记录待恢复")
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
