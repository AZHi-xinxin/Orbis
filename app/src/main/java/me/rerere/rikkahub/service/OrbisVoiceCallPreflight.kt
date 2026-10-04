package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import java.net.URI

/** Only fixed, public diagnostics may enter the incoming-call ledger or its UI. */
internal enum class OrbisCallFailure(val code: String, val explanation: String) {
    ASSISTANT_MISSING("assistant_missing", "当前助手已不存在，请重新选择助手。"),
    CONVERSATION_CHANGED("conversation_owner_changed", "会话归属已变化，未建立通话。"),
    INCOMING_DISABLED("incoming_calls_disabled", "主动来电已关闭，未建立通话。"),
    MODEL_MISSING("voice_model_missing", "请先为当前助手选择聊天模型。"),
    MODEL_CONFIGURATION("voice_model_configuration_invalid", "聊天模型名称或服务地址未配置完整，请检查模型设置。"),
    ASR_MISSING("voice_asr_missing", "请先在音色与语音设置中选择语音识别服务。"),
    ASR_UNSUPPORTED("voice_asr_unsupported", "当前识别适配器不支持连续通话，请更换识别服务。"),
    ASR_ENDPOINT("voice_asr_endpoint_invalid", "语音识别地址不完整或协议不正确，请检查识别服务设置。"),
    ASR_CREDENTIAL("voice_asr_credential_missing", "当前官方语音识别服务尚未填写密钥，请检查识别服务设置。"),
    ASR_MODEL("voice_asr_model_missing", "语音识别模型或资源 ID 为空，请检查识别服务设置。"),
    ASR_SAMPLE_RATE("voice_asr_sample_rate_invalid", "语音识别采样率不受当前通话适配器支持，请检查识别服务设置。"),
    TTS_MISSING("voice_tts_missing", "请先在音色与语音设置中选择朗读服务。"),
    TTS_ENDPOINT("voice_tts_endpoint_invalid", "朗读服务地址未配置完整，请检查音色与语音设置。"),
    TTS_CREDENTIAL("voice_tts_credential_missing", "当前官方朗读服务尚未填写密钥，请检查音色与语音设置。"),
    TTS_MODEL("voice_tts_model_or_voice_missing", "朗读模型或音色为空，请检查音色与语音设置。"),
    CALL_BUSY("call_busy", "已有通话正在进行或收尾，请先结束当前通话。"),
    INVITATION_BUSY("another_invitation_active", "已有另一通来电正在等待处理。"),
    AUDIO_BUSY("device_audio_busy", "设备音频正被其他通话、录音或闹钟占用。"),
    COOLDOWN("cooldown", "拒接或未接后的来电冷却尚未结束，不会自动重拨。"),
    NOTIFICATIONS("notification_permission_or_channel_blocked", "来电通知权限或通知频道已关闭，请在系统设置中检查。"),
    RINGTONE("ringtone_unavailable", "来电铃声暂时无法播放，本次来电已停止。"),
    TIMEOUT("connection_timeout", "语音服务未在时限内接通，请检查网络与语音服务设置后手动重试。"),
    ANSWER_INTERRUPTED("answer_interrupted", "接听过程被中断，本次未接通；不会自动重拨。"),
    REQUEST_CANCELLED("request_cancelled", "发起来电的请求已取消，本次来电已停止。"),
    PROCESS_INTERRUPTED("process_interrupted", "应用进程在来电期间结束，本次未确认接通；不会自动重拨。"),
    MICROPHONE_PERMISSION("voice_microphone_permission_missing", "麦克风权限不可用，请开启权限；也可重新选择静音接听。"),
    ACTIVITY_INACTIVE("voice_answer_activity_inactive", "接听时需要保持来电界面可见，请回到应用后手动重试。"),
    INVITATION_EXPIRED("voice_invitation_expired", "这通来电已结束或过期，不会再打开麦克风。"),
    STARTUP_FAILED("voice_startup_failed", "通话未能启动，请检查语音服务与系统音频权限。未自动重试。"),
    LEGACY_CONNECTION_FAILED("connection_failed", "旧版仅记录了连接失败，无法据此判断是配置、权限还是网络问题。"),
    UNKNOWN("incoming_call_failed", "本次来电未能完成。请检查来电日志与语音配置后手动重试。"),
    ;

    companion object {
        fun fromCode(code: String?): OrbisCallFailure = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

internal class OrbisCallStartException(val failure: OrbisCallFailure) : IllegalStateException(failure.code)

/** Pure local checks: no microphone, notification, network probe, or private values in diagnostics. */
internal object OrbisVoiceCallPreflight {
    fun firstFailure(settings: Settings, assistant: Assistant?): OrbisCallFailure? {
        if (assistant == null) return OrbisCallFailure.ASSISTANT_MISSING
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        modelFailure(model, model?.findProvider(settings.providers))?.let { return it }
        asrFailure(settings.getSelectedASRProvider())?.let { return it }
        return ttsFailure(settings.getSelectedTTSProvider())
    }

    fun modelFailure(model: Model?, provider: ProviderSetting?): OrbisCallFailure? {
        if (model == null) return OrbisCallFailure.MODEL_MISSING
        val endpoint = when (provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl
            is ProviderSetting.Google -> provider.baseUrl
            is ProviderSetting.Claude -> provider.baseUrl
            null -> return OrbisCallFailure.MODEL_CONFIGURATION
        }
        // Model authentication can come from custom headers or a local unauthenticated endpoint.
        return if (model.modelId.isBlank() || endpointHost(endpoint, false) == null)
            OrbisCallFailure.MODEL_CONFIGURATION else null
    }

    fun asrFailure(provider: ASRProviderSetting?): OrbisCallFailure? {
        if (provider == null) return OrbisCallFailure.ASR_MISSING
        if (!provider.supportsVoiceCall) return OrbisCallFailure.ASR_UNSUPPORTED
        val endpoint: String
        val key: String
        val model: String
        val sampleRate: Int
        when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                endpoint = provider.websocketUrl; key = provider.apiKey; model = provider.model; sampleRate = provider.sampleRate
            }
            is ASRProviderSetting.DashScope -> {
                endpoint = provider.websocketUrl; key = provider.apiKey; model = provider.model; sampleRate = provider.sampleRate
            }
            is ASRProviderSetting.Volcengine -> {
                endpoint = provider.websocketUrl; key = provider.apiKey; model = provider.resourceId; sampleRate = 16000
            }
            is ASRProviderSetting.MiMo -> {
                endpoint = provider.baseUrl; key = provider.apiKey; model = provider.model; sampleRate = provider.sampleRate
            }
            is ASRProviderSetting.Step -> {
                endpoint = provider.baseUrl; key = provider.apiKey; model = provider.model; sampleRate = provider.sampleRate
            }
        }
        val host = endpointHost(endpoint, provider.supportsServerVadVoiceMode) ?: return OrbisCallFailure.ASR_ENDPOINT
        if (key.isBlank() && host in authenticatedSpeechHosts) return OrbisCallFailure.ASR_CREDENTIAL
        if (model.isBlank()) return OrbisCallFailure.ASR_MODEL
        if (sampleRate <= 0 || (!provider.supportsServerVadVoiceMode && sampleRate !in batchSampleRates))
            return OrbisCallFailure.ASR_SAMPLE_RATE
        return null
    }

    fun ttsFailure(provider: TTSProviderSetting?): OrbisCallFailure? {
        if (provider == null) return OrbisCallFailure.TTS_MISSING
        if (provider is TTSProviderSetting.SystemTTS) return null
        val endpoint: String
        val key: String
        val modelAndVoicePresent: Boolean
        when (provider) {
            is TTSProviderSetting.OpenAI -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voice.isNotBlank() }
            is TTSProviderSetting.Gemini -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voiceName.isNotBlank() }
            is TTSProviderSetting.MiniMax -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voiceId.isNotBlank() }
            is TTSProviderSetting.Qwen -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voice.isNotBlank() }
            is TTSProviderSetting.Groq -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voice.isNotBlank() }
            is TTSProviderSetting.XAI -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.voiceId.isNotBlank() }
            is TTSProviderSetting.MiMo -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voice.isNotBlank() }
            is TTSProviderSetting.ElevenLabs -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voiceId.isNotBlank() }
            is TTSProviderSetting.Step -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() && provider.voice.isNotBlank() }
            // Fish can select a default voice without reference_id.
            is TTSProviderSetting.FishAudio -> { endpoint = provider.baseUrl; key = provider.apiKey; modelAndVoicePresent = provider.model.isNotBlank() }
            is TTSProviderSetting.SystemTTS -> return null
        }
        val host = endpointHost(endpoint, false) ?: return OrbisCallFailure.TTS_ENDPOINT
        if (key.isBlank() && (host in authenticatedSpeechHosts || host.endsWith(".maas.aliyuncs.com")))
            return OrbisCallFailure.TTS_CREDENTIAL
        return if (!modelAndVoicePresent) OrbisCallFailure.TTS_MODEL else null
    }

    private fun endpointHost(value: String, websocket: Boolean): String? = runCatching {
        val uri = URI(value)
        uri.host?.lowercase()?.takeIf { it.isNotBlank() && uri.fragment == null &&
            uri.scheme?.lowercase() in if (websocket) setOf("ws", "wss") else setOf("http", "https") }
    }.getOrNull()

    private val batchSampleRates = setOf(8000, 16000, 24000, 32000, 44100, 48000)
    private val authenticatedSpeechHosts = setOf(
        "api.openai.com", "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "openspeech.bytedance.com",
        "api.xiaomimimo.com", "api.stepfun.com", "generativelanguage.googleapis.com", "api.minimaxi.com",
        "api.minimax.io", "api.minimax.chat", "api.groq.com", "api.x.ai", "api.elevenlabs.io", "api.fish.audio",
    )
}
