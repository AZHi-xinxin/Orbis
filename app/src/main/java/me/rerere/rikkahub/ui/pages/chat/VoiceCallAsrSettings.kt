package me.rerere.rikkahub.ui.pages.chat

import me.rerere.asr.ASRProviderSetting

internal const val VOICE_CALL_MIN_SILENCE_MS = 3_000

/**
 * Requests a longer server-side pause for calls, without changing dictation or persisted settings.
 * This is NOT a client-side timer or a guarantee about when a remote server will commit a turn.
 *
 * Qwen ASR documents 200..6000 ms. OpenAI documents this field without numeric bounds.
 * Volcengine documents a minimum of 200 ms, but our shared adapter clamps to 300..5000 ms.
 * Reject values that the documented service or current adapter cannot preserve; never shorten a
 * user's longer call pause silently. Keep the original provider unchanged, including credentials.
 * Sources and untested server behavior are recorded in stage23-voice-silence/VOICE-SILENCE-HANDOFF.md.
 */
internal fun ASRProviderSetting.forVoiceCallSilence(): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(
        silenceDurationMs = silenceDurationMs.coerceAtLeast(VOICE_CALL_MIN_SILENCE_MS),
    )

    is ASRProviderSetting.DashScope -> {
        val callSilence = silenceDurationMs.coerceAtLeast(VOICE_CALL_MIN_SILENCE_MS)
        require(callSilence <= 6_000) {
            "当前 Qwen 实时识别接口的静默等待上限为 6000 毫秒，无法保留更长的通话设置；请调整语音识别配置。"
        }
        copy(silenceDurationMs = callSilence)
    }

    is ASRProviderSetting.Volcengine -> {
        val callSilence = silenceDurationMs.coerceAtLeast(VOICE_CALL_MIN_SILENCE_MS)
        require(callSilence <= 5_000) {
            "当前豆包识别适配器的静默等待上限为 5000 毫秒，无法保留更长的通话设置；请调整语音识别配置。"
        }
        copy(silenceDurationMs = callSilence)
    }

    else -> throw IllegalArgumentException("当前识别服务不支持通话所需的服务端静默断句，请选择支持的实时识别服务。")
}
