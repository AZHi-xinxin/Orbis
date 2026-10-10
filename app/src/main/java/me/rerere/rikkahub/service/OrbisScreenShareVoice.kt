package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.pages.chat.createVoiceAsr
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

internal suspend fun startScreenShareVoice(context: Context) {
    val share = OrbisScreenShareRuntime.get(context)
    val current = share.state.value
    val shareSession = checkNotNull(current.sessionId)
    val output = OrbisScreenShareSpeechOutput.get(context)
    val koin = GlobalContext.get()
    val settings = koin.get<SettingsStore>().settingsFlow.value
    val assistant = settings.getAssistantById(Uuid.parse(checkNotNull(current.assistantId)))
    check(settings.assistantId.toString() == current.assistantId)
    checkNotNull(assistant) { OrbisCallFailure.ASSISTANT_MISSING.explanation }
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    OrbisVoiceCallPreflight.modelFailure(model, model?.findProvider(settings.providers))?.let { error(it.explanation) }
    OrbisVoiceCallPreflight.asrFailure(settings.getSelectedASRProvider())?.let { error(it.explanation) }
    if (output.state.value.enabled) {
        OrbisVoiceCallPreflight.ttsFailure(settings.getSelectedTTSProvider())?.let { error(it.explanation) }
    }
    val voice = OrbisVoiceCallRuntime.get(context)
    if (current.voiceCallId != null && voice.callState.value.callId == current.voiceCallId && voice.callState.value.isActive) {
        check(voice.setMicrophoneEnabled(true)); return
    }
    check(!voice.callState.value.isActive && !voice.callState.value.ending)
    val chat = koin.get<ChatService>()
    val conversation = Uuid.parse(checkNotNull(current.conversationId))
    var record: me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord? = null
    var player: TtsController? = null
    var finalized = false
    var held = false
    fun release() {
        runCatching { player?.dispose() }; player = null
        output.voiceDetached(shareSession, record?.id)
        if (held) { held = false; chat.removeConversationReference(conversation) }
    }
    suspend fun end(end: OrbisVoiceCallEnd) {
        if (finalized) return
        finalized = true
        try { chat.endVoiceCall(end.callId, end) } finally { release() }
    }
    try {
        check(output.prepareVoiceStart(shareSession))
        chat.addConversationReference(conversation); held = true
        val call = chat.prepareVoiceCall(conversation); record = call
        output.voicePrepared(shareSession, call.id)
        val ownedPlayer = TtsController(context.applicationContext, koin.get<TTSManager>(),
            audioUsage = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).also {
            player = it; it.setProvider(settings.getSelectedTTSProvider()); it.setSpeed(settings.defaultTTSPlaybackSpeed)
        }
        check(share.state.value.sessionId == current.sessionId)
        check(voice.start(call.id, conversation, assistant?.name.orEmpty(),
            createAsr = { createVoiceAsr(context.applicationContext, koin.get<OkHttpClient>(), checkNotNull(settings.getSelectedASRProvider())) },
            enqueueMessage = { chat.enqueueVoiceCallUtterance(conversation, call.id, it) },
            speak = { text ->
                val spoken = text.stripMarkdown()
                if (spoken.isNotBlank() && output.canSpeakInVoice(shareSession, call.id)) {
                    // Speech settings may have been repaired while the microphone stayed connected.
                    try {
                        val liveSettings = koin.get<SettingsStore>().settingsFlow.value
                        val provider = liveSettings.getSelectedTTSProvider()
                        if (OrbisVoiceCallPreflight.ttsFailure(provider) != null) {
                            output.reportFailure(shareSession)
                        } else {
                            ownedPlayer.setProvider(provider)
                            ownedPlayer.setSpeed(liveSettings.defaultTTSPlaybackSpeed)
                            if (!ownedPlayer.speakScreenShareText(spoken)) output.reportFailure(shareSession)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { runCatching { ownedPlayer.stop() }; output.reportFailure(shareSession) }
                }
            }, stopSpeaking = ownedPlayer::stop, setOutputMuted = ownedPlayer::setOutputMuted,
            initialSpeakerEnabled = output.state.value.enabled,
            cancelPendingReply = { chat.cancelVoiceCallReply(conversation, call.id, it) },
            checkReplyResume = { chat.canResumeVoiceCallReplies(conversation, call.id) },
            onConnected = { chat.connectVoiceCall(call.id, it) }, onEnded = ::end))
        share.attachVoice(call.id)
        output.voiceAttached(shareSession, call.id)
    } catch (error: Exception) {
        record?.let { voice.hangUpCall(it.id) }
        withContext(NonCancellable) {
            try { record?.let { end(OrbisVoiceCallEnd(it.id, OrbisVoiceCallEndReason.STARTUP_FAILED,
                null, System.currentTimeMillis(), null, "共享麦克风未能启动")) } }
            finally { release() }
        }
        throw error
    }
}
