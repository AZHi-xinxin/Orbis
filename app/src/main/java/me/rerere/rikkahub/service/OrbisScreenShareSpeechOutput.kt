package me.rerere.rikkahub.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

data class ScreenShareSpeechOutputState(
    val sessionId: String? = null,
    val enabled: Boolean = false,
    val notice: String? = null,
)

/** One output owner, independent of ASR. Neither enabling nor retrying output opens a microphone. */
class OrbisScreenShareSpeechOutput private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: OrbisScreenShareSpeechOutput? = null
        fun get(context: Context): OrbisScreenShareSpeechOutput = instance ?: synchronized(this) {
            instance ?: OrbisScreenShareSpeechOutput(context.applicationContext).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = ScreenShareSpeechPolicy()
    private val mutableState = MutableStateFlow(ScreenShareSpeechOutputState())
    val state = mutableState.asStateFlow()
    private var player: TtsController? = null
    private var worker: Job? = null
    private var voiceObserver: Job? = null
    private var scopeObserver: Job? = null
    private var queue: Channel<String>? = null
    private var focus: AudioFocusRequest? = null
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val voice get() = OrbisVoiceCallRuntime.get(context)

    @MainThread fun begin(owner: String, conversation: String, session: String) {
        policy.sessionId?.let(::stop)
        policy.begin(owner, conversation, session)
        mutableState.value = ScreenShareSpeechOutputState(sessionId = session)
        scopeObserver = scope.launch {
            combine(GlobalContext.get().get<SettingsStore>().settingsFlow,
                GlobalContext.get().get<ChatService>().getConversationFlow(Uuid.parse(conversation))) { _, _ -> liveScope(session) }
                .collect { current -> if (!current && policy.matches(session)) stop(session) }
        }
        // Mirror the same owned voice speaker control, including toggles in its full-screen UI.
        voiceObserver = scope.launch {
            combine(voice.callState, voice.voiceSession.state) { call, speech -> call to speech }
                .collect { (call, speech) ->
                    if (policy.matches(session) && call.callId == policy.voiceCallId && call.isActive &&
                        call.connectedAtMillis != null && speech.isActive && liveScope(session)) {
                        policy.setEnabled(session, speech.speakerEnabled)
                        mutableState.value = mutableState.value.copy(enabled = speech.speakerEnabled)
                    }
                }
        }
    }

    fun ownsConversation(conversation: String): Boolean = policy.sessionId != null && policy.conversation == conversation

    @MainThread fun setEnabled(sessionId: String, enabled: Boolean): Boolean {
        if (!liveScope(sessionId)) return false
        if (enabled) {
            val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
            OrbisVoiceCallPreflight.ttsFailure(settings.getSelectedTTSProvider())?.let {
                mutableState.value = mutableState.value.copy(notice = it.explanation)
                return false
            }
        }
        policy.setEnabled(sessionId, enabled)
        mutableState.value = mutableState.value.copy(enabled = enabled, notice = null)
        if (!enabled) disposeStandalone()
        if (ownedVoiceActive()) voice.setSpeakerEnabled(enabled)
        return true
    }

    @MainThread fun offerFinalReply(sessionId: String, replyId: String, voiceCallId: String?, text: String) {
        if (!liveScope(sessionId)) return
        val call = voice.callState.value
        val route = policy.claim(sessionId, replyId, voiceCallId, call.callId.takeIf { call.isActive }, call.isActive || call.ending)
        val spoken = text.stripMarkdown()
        if (spoken.isBlank() || route == ScreenShareSpeechRoute.SKIP) return
        if (spoken.length > 80_000) { reportFailure(sessionId, "这条回复过长，已保留文字，未自动朗读。"); return }
        when (route) {
            ScreenShareSpeechRoute.OWNED_VOICE -> {
                // Its existing queue owns capture suspension, playback order and focus. Never add a second player.
                val speech = voice.voiceSession.state.value
                if (speech.isActive && !speech.replyBlocked && !speech.audioFocusSuspended && ownedVoiceActive()) {
                    voice.voiceSession.acceptSupplementaryReply(CompletableDeferred(spoken))
                }
            }
            ScreenShareSpeechRoute.STANDALONE -> {
                ensureWorker(sessionId)
                if (queue?.trySend(spoken)?.isSuccess != true) reportFailure(sessionId, "朗读尚未跟上，新回复已保留文字。");
            }
            ScreenShareSpeechRoute.SKIP -> Unit
        }
    }

    @MainThread internal fun prepareVoiceStart(session: String): Boolean {
        if (!liveScope(session) || !policy.prepareVoice(session)) return false
        disposeStandalone()
        return true
    }
    @MainThread internal fun voiceAttached(session: String, call: String) {
        if (policy.attachVoice(session, call) && ownedVoiceActive()) voice.setSpeakerEnabled(policy.enabled)
    }
    @MainThread internal fun voicePrepared(session: String, call: String) { policy.attachVoice(session, call, starting = true) }
    internal fun canSpeakInVoice(session: String, call: String): Boolean =
        policy.voiceCallId == call && policy.enabled && liveScope(session) && ownedVoiceActive()
    @MainThread internal fun voiceDetached(session: String, call: String?) { policy.detachVoice(session, call) }
    @MainThread internal fun reportFailure(session: String, text: String = "朗读暂不可用；文字与画面继续，可以检查语音设置后再试。") {
        if (policy.matches(session)) mutableState.value = mutableState.value.copy(notice = text)
    }

    @MainThread fun stop(sessionId: String) {
        if (!policy.matches(sessionId)) return
        if (ownedVoiceActive()) voice.setSpeakerEnabled(false)
        policy.stop(sessionId)
        voiceObserver?.cancel(); voiceObserver = null
        scopeObserver?.cancel(); scopeObserver = null
        disposeStandalone()
        mutableState.value = ScreenShareSpeechOutputState()
    }

    private fun ownedVoiceActive(): Boolean = policy.voiceCallId != null &&
        voice.callState.value.callId == policy.voiceCallId && voice.callState.value.isActive &&
        voice.callState.value.conversationId?.toString() == policy.conversation

    private fun liveScope(session: String): Boolean = runCatching {
        if (!policy.matches(session)) return@runCatching false
        val share = OrbisScreenShareRuntime.get(context).state.value
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
        share.sessionId == session && share.assistantId == policy.owner && share.conversationId == policy.conversation &&
            settings.assistantId.toString() == policy.owner &&
            settings.getAssistantById(Uuid.parse(checkNotNull(policy.owner))) != null &&
            GlobalContext.get().get<ChatService>().getConversationFlow(Uuid.parse(checkNotNull(policy.conversation)))
                .value.assistantId.toString() == policy.owner
    }.getOrDefault(false)

    private fun ensureWorker(session: String) {
        if (worker?.isActive == true) return
        val pending = Channel<String>(4).also { queue = it }
        worker = scope.launch {
            for (text in pending) {
                if (!liveScope(session) || !policy.enabled || policy.voiceStarting || voice.callState.value.isActive) continue
                var owned: TtsController? = null
                var request: AudioFocusRequest? = null
                try {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
                    if (OrbisVoiceCallPreflight.ttsFailure(settings.getSelectedTTSProvider()) != null) {
                        reportFailure(session); continue
                    }
                    if (audio.mode != AudioManager.MODE_NORMAL) { reportFailure(session); continue }
                    request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener({ change ->
                            if (change != AudioManager.AUDIOFOCUS_GAIN && focus === request && policy.matches(session)) {
                                // Do not auto-resume old audio when another call/alarm releases focus.
                                reportFailure(session, "朗读被其他音频打断；已保留文字，不会重播旧回复。")
                                disposeStandalone()
                            }
                        }, Handler(Looper.getMainLooper())).build()
                    focus = request
                    if (audio.requestAudioFocus(checkNotNull(request)) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        releaseFocus(); reportFailure(session); continue
                    }
                    val currentPlayer = TtsController(context, GlobalContext.get().get<TTSManager>()).also {
                        owned = it
                        player = it; it.setProvider(settings.getSelectedTTSProvider()); it.setSpeed(settings.defaultTTSPlaybackSpeed)
                    }
                    if (!currentPlayer.speakScreenShareText(text)) reportFailure(session)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportFailure(session) }
                finally {
                    // An old cancelled worker may finish after the next output/session has begun.
                    owned?.dispose()
                    if (player === owned) player = null
                    if (focus === request) releaseFocus()
                }
            }
        }
    }

    private fun releaseFocus() { focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null }
    private fun disposeStandalone() {
        queue?.close(); queue = null
        worker?.cancel(); worker = null
        player?.dispose(); player = null
        releaseFocus()
    }
}

/** Provider failures are speech-local, never MODEL_FAILED or a reason to close screen sharing. */
internal suspend fun TtsController.speakScreenShareText(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
    runScreenShareSpeechAttempt(stop = ::stop) {
        speak(text)
        combine(isSpeaking, error) { speaking, failure -> !speaking || failure != null }.first { it }
        error.value == null
    }
}
