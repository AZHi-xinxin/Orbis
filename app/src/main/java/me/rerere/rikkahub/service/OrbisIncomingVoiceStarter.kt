package me.rerere.rikkahub.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.pages.chat.createVoiceAsr
import me.rerere.asr.correctDeviceAsrTranscript
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Only invoked by the incoming activity after an explicit answer tap. */
internal suspend fun startIncomingVoice(context: Context, lifecycle: Lifecycle, attempt: IncomingCallAttempt) {
    val app = context.applicationContext
    val koin = GlobalContext.get()
    val conversationId = Uuid.parse(attempt.conversationId)
    val conversation = koin.get<ConversationRepository>().getConversationSummaryOfAssistant(
        conversationId, Uuid.parse(attempt.assistantId))
        ?: throw OrbisCallStartException(OrbisCallFailure.CONVERSATION_CHANGED)
    // Settings may change while ringing or while reading the conversation. Recheck the current
    // snapshot before preparing a record, opening a recorder, or making a service request.
    val settings = koin.get<SettingsStore>().settingsFlow.value
    if (!settings.orbisContact.allowIncomingCalls) throw OrbisCallStartException(OrbisCallFailure.INCOMING_DISABLED)
    val assistant = settings.getAssistantById(conversation.assistantId)
        ?: throw OrbisCallStartException(OrbisCallFailure.ASSISTANT_MISSING)
    if (attempt.video) {
        require(me.rerere.ai.provider.Modality.IMAGE in
            settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.inputModalities.orEmpty()) {
            "当前模型不支持图片输入。"
        }
        check(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            "视频接听需要解锁手机并开启相机权限。"
        }
    }
    OrbisVoiceCallPreflight.firstFailure(settings, assistant)?.let { throw OrbisCallStartException(it) }
    val provider = checkNotNull(settings.getSelectedASRProvider())
    val ttsProvider = checkNotNull(settings.getSelectedTTSProvider())
    val chat = koin.get<ChatService>()
    val incoming = OrbisIncomingCallRuntime.get(app)
    val runtime = OrbisVoiceCallRuntime.get(app)
    if (runtime.callState.value.let { it.isActive || it.ending }) throw OrbisCallStartException(OrbisCallFailure.CALL_BUSY)
    var player: TtsController? = null
    var held = false
    var finalized = false
    var startupFailure = OrbisCallFailure.STARTUP_FAILED
    var record: me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord? = null
    fun release() {
        runCatching { player?.dispose() }; player = null
        if (held) { held = false; chat.removeConversationReference(conversationId) }
    }
    suspend fun end(end: OrbisVoiceCallEnd) {
        if (finalized) return
        finalized = true
        try {
            if (end.connectedAtMillis == null) incoming.connectionFailed(attempt.id, startupFailure)
            chat.endVoiceCall(end.callId, if (end.connectedAtMillis == null)
                end.copy(error = startupFailure.explanation) else end)
        } finally { release() }
    }
    try {
        chat.addConversationReference(conversationId); held = true
        val call = chat.prepareVoiceCall(conversationId, video = attempt.video); record = call
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw OrbisCallStartException(OrbisCallFailure.ACTIVITY_INACTIVE)
        if (incoming.state.value?.attempt?.id != attempt.id) throw OrbisCallStartException(OrbisCallFailure.INVITATION_EXPIRED)
        incoming.registerConnectingCall(attempt.id, call.id)
        val ownedPlayer = TtsController(app, koin.get<TTSManager>(), audioUsage = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).also {
            player = it; it.setProvider(ttsProvider); it.setSpeed(settings.defaultTTSPlaybackSpeed)
        }
        if (!runtime.start(callId = call.id, conversationId = conversationId, title = assistant.name,
            createAsr = { createVoiceAsr(app, koin.get<OkHttpClient>(), provider) },
            enqueueMessage = { chat.enqueueVoiceCallUtterance(conversationId, call.id, it) },
            correctTranscript = { correctDeviceAsrTranscript(it, settings.asrCorrections) },
            enqueueRecognizedMessage = { transcript ->
                chat.enqueueVoiceCallUtterance(conversationId, call.id, transcript.corrected,
                    originalTranscript = transcript.original.takeIf { transcript.changed })
            },
            cancelPendingReply = { chat.cancelVoiceCallReply(conversationId, call.id, it) },
            checkReplyResume = { chat.canResumeVoiceCallReplies(conversationId, call.id) },
            speak = { text ->
                val spoken = text.stripMarkdown()
                if (spoken.isNotBlank()) {
                    ownedPlayer.speak(spoken)
                    combine(ownedPlayer.isSpeaking, ownedPlayer.error) { speaking, error ->
                        check(error == null) { "通话朗读失败" }; !speaking
                    }.first { it }
                }
            }, stopSpeaking = ownedPlayer::stop,
            setOutputMuted = ownedPlayer::setOutputMuted,
            initialMicrophoneEnabled = !attempt.mutedAnswer, initialSpeakerEnabled = true,
            requestOpening = { chat.enqueueIncomingOpening(conversationId, call.id, attempt.reason) },
            onConnected = { connectedAt ->
                chat.connectVoiceCall(call.id, connectedAt) {
                    check(incoming.connected(attempt.id, call.id)) { "来电已过期，未建立新通话" }
                }
            }, onEnded = ::end)) throw OrbisCallStartException(OrbisCallFailure.CALL_BUSY)
        if (attempt.video) OrbisVideoCallRuntime.get(app).begin(attempt.assistantId, attempt.conversationId, call.id)
    } catch (error: Exception) {
        if (attempt.video) record?.let { runtime.hangUpCall(it.id) }
        startupFailure = when (error) {
            is OrbisCallStartException -> error.failure
            is TimeoutCancellationException -> OrbisCallFailure.TIMEOUT
            is CancellationException -> OrbisCallFailure.ANSWER_INTERRUPTED
            is SecurityException -> OrbisCallFailure.MICROPHONE_PERMISSION
            else -> OrbisCallFailure.STARTUP_FAILED
        }
        withContext(NonCancellable) {
            try { record?.let { end(OrbisVoiceCallEnd(it.id, OrbisVoiceCallEndReason.STARTUP_FAILED,
                null, System.currentTimeMillis(), null, "来电连接失败")) } }
            finally { release() }
        }
        if (error is CancellationException) throw error
        throw OrbisCallStartException(startupFailure)
    }
}
