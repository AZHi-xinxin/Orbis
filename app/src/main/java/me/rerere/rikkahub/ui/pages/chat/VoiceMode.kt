package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.R
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.OrbisVoiceCallEnd
import me.rerere.rikkahub.service.OrbisVoiceCallEndReason
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean

// Process-wide, so rapid taps in separate windows/recreated compositions cannot prepare two calls.
private val voiceCallPreparing = AtomicBoolean(false)

/** Lives above adaptive drawer branches, so resizing does not recreate the voice session. */
@Composable
fun rememberVoiceModeStarter(vm: ChatVM, settings: Settings): () -> Unit {
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val toaster = LocalToaster.current
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permission)
    val runtime = vm.voiceRuntime
    val chatService = koinInject<ChatService>()
    val appScope = koinInject<AppScope>()
    val ttsManager = koinInject<TTSManager>()
    val provider = settings.getSelectedASRProvider()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Service/runtime, not composition or the chat ViewModel, owns the call.

    val start: () -> Unit = {
        val conversation = vm.conversation.value
        val assistant = settings.getAssistantById(conversation.assistantId)
        val blocked = when {
            voiceCallPreparing.get() -> "正在准备语音通话，请稍候。"
            runtime.callState.value.isActive || runtime.callState.value.ending -> "已有通话正在进行或收尾，请先结束当前通话。"
            provider == null -> context.getString(R.string.chat_page_voice_configure_asr)
            !provider.supportsServerVadVoiceMode -> context.getString(R.string.chat_page_voice_unsupported_asr)
            settings.findModelById(assistant?.chatModelId ?: settings.chatModelId) == null -> context.getString(R.string.chat_page_voice_select_model)
            settings.getSelectedTTSProvider() == null -> "请先在音色与语音设置中选择朗读服务。"
            asr.state.value.isRecording -> context.getString(R.string.chat_page_voice_finish_dictation)
            vm.messageQueue.value.paused ->
                context.getString(R.string.chat_page_voice_resume_queue)
            vm.conversation.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            } -> context.getString(R.string.chat_page_voice_pending_tools)
            else -> null
        }
        when {
            blocked != null -> toaster.show(message = blocked)
            !permission.allRequiredPermissionsGranted -> permission.requestPermissions()
            else -> {
                if (voiceCallPreparing.compareAndSet(false, true)) appScope.launch {
                    var player: TtsController? = null
                    var record: me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord? = null
                    var referenceHeld = false
                    var endHandled = false
                    fun releaseReference() {
                        if (referenceHeld) {
                            referenceHeld = false
                            chatService.removeConversationReference(conversation.id)
                        }
                    }
                    fun disposePlayer() {
                        val ownedPlayer = player
                        player = null
                        runCatching { ownedPlayer?.dispose() }
                    }
                    suspend fun finishOnce(end: OrbisVoiceCallEnd) {
                        if (endHandled) return
                        endHandled = true
                        disposePlayer()
                        try { chatService.endVoiceCall(end.callId, end) }
                        finally { releaseReference() }
                    }
                    try {
                        tts.stop()
                        chatService.addConversationReference(conversation.id)
                        referenceHeld = true
                        val call = chatService.prepareVoiceCall(conversation.id)
                        record = call
                        // Preparing/persisting may suspend. Android must still see the originating
                        // activity when its microphone foreground service is created.
                        check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            "通话尚未接通时已离开界面，请返回后重新发起。"
                        }
                        // The dedicated player cannot be disposed by an Activity rotation/ON_STOP.
                        val callPlayer = TtsController(context, ttsManager, audioUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION).also {
                            player = it
                            it.setProvider(settings.getSelectedTTSProvider())
                            it.setSpeed(settings.defaultTTSPlaybackSpeed)
                        }
                        val accepted = runtime.start(
                            callId = call.id, conversationId = conversation.id,
                            title = assistant?.name?.ifBlank { "语音通话" } ?: "语音通话",
                            createAsr = { createVoiceAsr(context, client, checkNotNull(provider)) },
                            enqueueMessage = { chatService.enqueueVoiceCallUtterance(conversation.id, call.id, it) },
                            cancelPendingReply = { chatService.cancelVoiceCallReply(conversation.id, call.id, it) },
                            speak = { reply ->
                                var spoken = reply
                                if (settings.displaySetting.ttsOnlyReadQuoted) spoken = spoken.extractQuotedContentAsText() ?: spoken
                                if (settings.displaySetting.ttsOnlyReadOutsideBrackets) spoken = spoken.removeBracketedContent() ?: spoken
                                spoken = spoken.stripMarkdown()
                                if (spoken.isNotBlank()) {
                                    callPlayer.speak(spoken)
                                    combine(callPlayer.isSpeaking, callPlayer.error) { speaking, error ->
                                        check(error == null) { error.orEmpty() }
                                        !speaking
                                    }.first { it }
                                }
                            },
                            stopSpeaking = callPlayer::stop,
                            setOutputMuted = callPlayer::setOutputMuted,
                            onConnected = { chatService.connectVoiceCall(call.id, it) },
                            onEnded = ::finishOnce,
                        )
                        check(accepted) { "已有另一通电话进行中。" }
                    } catch (error: Exception) {
                        // Startup and runtime callbacks share one finalizer. Even a failed archive
                        // write or player disposal cannot double-release the conversation reference.
                        withContext(NonCancellable) {
                            try {
                                record?.let { call -> runCatching {
                                    finishOnce(OrbisVoiceCallEnd(call.id,
                                        OrbisVoiceCallEndReason.STARTUP_FAILED, null, System.currentTimeMillis(), null,
                                        error.message))
                                } }
                            } finally {
                                disposePlayer()
                                releaseReference()
                            }
                        }
                        if (error is CancellationException) throw error
                        toaster.show(message = error.message ?: "通话未能启动，原聊天未修改。")
                    } finally {
                        voiceCallPreparing.set(false)
                    }
                } else toaster.show(message = "正在准备语音通话，请稍候。")
            }
        }
    }
    return start
}

internal fun createVoiceAsr(context: Context, client: OkHttpClient, provider: ASRProviderSetting): ASRController {
    // A call uses its own immutable copy. Dictation and the saved provider keep their settings.
    val callProvider = provider.forVoiceCallSilence()
    val delegate = when (callProvider) {
        is ASRProviderSetting.OpenAIRealtime -> {
            check(callProvider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            OpenAIRealtimeASRController(context, client, callProvider, enableEchoCancellation = true)
        }
        is ASRProviderSetting.DashScope -> {
            check(callProvider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            DashScopeASRController(context, client, callProvider, enableEchoCancellation = true)
        }
        is ASRProviderSetting.Volcengine -> {
            check(callProvider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            VolcengineASRController(context, client, callProvider, enableEchoCancellation = true)
        }
        else -> error(context.getString(R.string.chat_page_voice_no_endpointing))
    }
    // Audio focus and communication mode belong to the whole call, not each ASR turn.
    return delegate
}
