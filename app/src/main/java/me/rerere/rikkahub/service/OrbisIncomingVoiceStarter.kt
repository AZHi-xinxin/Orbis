package me.rerere.rikkahub.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.pages.chat.createVoiceAsr
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
    val settings = koin.get<SettingsStore>().settingsFlow.value
    check(settings.orbisContact.allowIncomingCalls) { "主动来电已关闭" }
    val conversationId = Uuid.parse(attempt.conversationId)
    val conversation = checkNotNull(koin.get<ConversationRepository>().getConversationSummaryOfAssistant(
        conversationId, Uuid.parse(attempt.assistantId))) { "会话归属已变化" }
    val assistant = checkNotNull(settings.getAssistantById(conversation.assistantId))
    val provider = checkNotNull(settings.getSelectedASRProvider()) { "请先配置实时语音识别" }
    check(provider.supportsServerVadVoiceMode) { "识别服务暂不支持连续通话" }
    check(settings.findModelById(assistant.chatModelId ?: settings.chatModelId) != null) { "尚未选择模型" }
    val ttsProvider = checkNotNull(settings.getSelectedTTSProvider()) { "请先配置朗读服务" }
    val chat = koin.get<ChatService>()
    val incoming = OrbisIncomingCallRuntime.get(app)
    val runtime = OrbisVoiceCallRuntime.get(app)
    check(!runtime.callState.value.isActive && !runtime.callState.value.ending) { "已有通话正在进行" }
    var player: TtsController? = null
    var held = false
    var finalized = false
    var record: me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord? = null
    fun release() {
        runCatching { player?.dispose() }; player = null
        if (held) { held = false; chat.removeConversationReference(conversationId) }
    }
    suspend fun end(end: OrbisVoiceCallEnd) {
        if (finalized) return
        finalized = true
        try {
            if (end.connectedAtMillis == null) incoming.connectionFailed(attempt.id)
            chat.endVoiceCall(end.callId, end)
        } finally { release() }
    }
    try {
        chat.addConversationReference(conversationId); held = true
        val call = chat.prepareVoiceCall(conversationId); record = call
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "接听时需要打开来电界面" }
        check(incoming.state.value?.attempt?.id == attempt.id) { "来电已结束" }
        incoming.registerConnectingCall(attempt.id, call.id)
        val ownedPlayer = TtsController(app, koin.get<TTSManager>(), audioUsage = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).also {
            player = it; it.setProvider(ttsProvider); it.setSpeed(settings.defaultTTSPlaybackSpeed)
        }
        check(runtime.start(callId = call.id, conversationId = conversationId, title = assistant.name,
            createAsr = { createVoiceAsr(app, koin.get<OkHttpClient>(), provider) },
            enqueueMessage = { chat.enqueueVoiceCallUtterance(conversationId, call.id, it) },
            cancelPendingReply = { chat.cancelVoiceCallReply(conversationId, call.id, it) },
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
            }, onEnded = ::end)) { "已有另一通电话" }
    } catch (error: Exception) {
        withContext(NonCancellable) {
            try { record?.let { end(OrbisVoiceCallEnd(it.id, OrbisVoiceCallEndReason.STARTUP_FAILED,
                null, System.currentTimeMillis(), null, "来电连接失败")) } }
            finally { release() }
        }
        if (error is CancellationException) throw error
        throw IllegalStateException("来电未能接通，请检查语音配置与权限")
    }
}
