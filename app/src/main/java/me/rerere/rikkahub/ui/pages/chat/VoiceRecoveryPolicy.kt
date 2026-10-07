package me.rerere.rikkahub.ui.pages.chat

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

internal enum class VoiceFailureStage { ASR_START, ASR_LISTEN, ASR_FINAL, CONNECT, MODEL, TTS, AUDIO }

/** Only host-authored text crosses into a call record. Never retain provider bodies/URLs/keys. */
internal class VoiceSessionFailure(
    val stage: VoiceFailureStage,
    val code: String,
    val network: Boolean = false,
    val hadSpeech: Boolean = false,
) : IllegalStateException(when (stage) {
    VoiceFailureStage.ASR_START -> "语音识别连接未能建立"
    VoiceFailureStage.ASR_LISTEN -> if (hadSpeech) "识别连接中断；本句未发送，请重新说一次" else "语音识别连接中断"
    VoiceFailureStage.ASR_FINAL -> "未收到本句完整转写；本句未发送，请重新说一次"
    VoiceFailureStage.CONNECT -> "通话开始记录未能确认，未继续收音"
    VoiceFailureStage.MODEL -> "本次回复未能完成，通话仍保留；收音与朗读已暂停，请回聊天处理后点继续回复"
    VoiceFailureStage.TTS -> "本段朗读未完成；文字已保留，没有自动从头重播"
    VoiceFailureStage.AUDIO -> "音频输出暂不可用"
} + " [$code]")

internal object VoiceRecoveryPolicy {
    private val retryDelays = longArrayOf(1_000, 3_000, 8_000)

    fun retryDelay(attemptsAlreadyUsed: Int, failure: VoiceSessionFailure, connected: Boolean): Long? =
        retryDelays.getOrNull(attemptsAlreadyUsed)?.takeIf {
            connected && !failure.hadSpeech && failure.network &&
                failure.stage in setOf(VoiceFailureStage.ASR_START, VoiceFailureStage.ASR_LISTEN)
        }

    fun failure(stage: VoiceFailureStage, error: Throwable, hadSpeech: Boolean = false): VoiceSessionFailure {
        if (error is VoiceSessionFailure) return error
        val description = error.message.orEmpty().lowercase()
        val timeout = error is TimeoutCancellationException || error is SocketTimeoutException ||
            description.contains("timed out") || description.contains("timeout")
        val reset = description.contains("connection reset") || description.contains("broken pipe")
        val disconnected = description == "speech recognition disconnected" ||
            description == "asr websocket failed" || description.contains("socket closed") ||
            description.contains("network is unreachable") || description.contains("unable to resolve host")
        val network = timeout || reset || disconnected || error is IOException
        val suffix = when { reset -> "NETWORK_RESET"; timeout -> "TIMEOUT"; network -> "NETWORK_FAILED"; else -> "FAILED" }
        return VoiceSessionFailure(stage, "${stage.name}_$suffix", network, hadSpeech)
    }
}

/** A new socket is not proof of recovery. Only five continuous idle-listening minutes renew the budget. */
internal class VoiceReconnectBudget {
    companion object { const val STABLE_LISTENING_MILLIS = 5 * 60 * 1_000L }
    var attemptsUsed: Int = 0
        private set
    private var listeningSinceMillis: Long? = null

    fun beginListening(nowMillis: Long) {
        if (listeningSinceMillis == null) listeningSinceMillis = nowMillis
    }

    fun stopListening(nowMillis: Long) {
        val since = listeningSinceMillis
        if (since != null && nowMillis >= since && nowMillis - since >= STABLE_LISTENING_MILLIS) {
            attemptsUsed = 0
        }
        listeningSinceMillis = null
    }

    fun nextRetry(nowMillis: Long, failure: VoiceSessionFailure, connected: Boolean): Long? {
        stopListening(nowMillis)
        return VoiceRecoveryPolicy.retryDelay(attemptsUsed, failure, connected)?.also { attemptsUsed++ }
    }
}

/** Permanent loss is never healed by an unsolicited late GAIN: only explicit human resume. */
internal enum class VoiceAudioFocusState { AVAILABLE, TRANSIENT_PAUSE, MANUAL_PAUSE }
internal enum class VoiceAudioFocusEvent { GAIN, TRANSIENT_LOSS, PERMANENT_LOSS }
internal fun nextVoiceAudioFocusState(current: VoiceAudioFocusState, event: VoiceAudioFocusEvent): VoiceAudioFocusState = when (event) {
    VoiceAudioFocusEvent.PERMANENT_LOSS -> VoiceAudioFocusState.MANUAL_PAUSE
    VoiceAudioFocusEvent.TRANSIENT_LOSS -> if (current == VoiceAudioFocusState.MANUAL_PAUSE) current else VoiceAudioFocusState.TRANSIENT_PAUSE
    VoiceAudioFocusEvent.GAIN -> if (current == VoiceAudioFocusState.TRANSIENT_PAUSE) VoiceAudioFocusState.AVAILABLE else current
}
