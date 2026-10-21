package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.tts.model.TTSResponse

internal enum class VoicePreviewStage { IDLE, REQUESTING, PLAYING, FAILED }
internal data class VoicePreviewState(
    val stage: VoicePreviewStage = VoicePreviewStage.IDLE,
    val notice: String? = null,
) {
    val busy: Boolean get() = stage == VoicePreviewStage.REQUESTING || stage == VoicePreviewStage.PLAYING
}

/** Owns only an explicit preview, never the chat queue or a call's player. Main-thread confined. */
internal class VoiceMixerPreview(
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> TTSResponse,
    private val play: suspend (TTSResponse) -> Unit,
    private val stopPlayer: () -> Unit,
    private val canPreview: () -> Boolean,
    private val requestTimeoutMs: Long = 180_000,
) {
    private val mutable = MutableStateFlow(VoicePreviewState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L
    private var closed = false

    fun start(text: String): Boolean {
        if (closed || mutable.value.busy) return false
        if (!canPreview()) return false
        val phrase = text.trim()
        if (phrase.isEmpty()) {
            mutable.value = VoicePreviewState(VoicePreviewStage.FAILED, "请先输入试听文字。")
            return false
        }
        val ticket = ++epoch
        mutable.value = VoicePreviewState(VoicePreviewStage.REQUESTING)
        job = scope.launch {
            try {
                // One explicit request. No retry, provider fallback, private conversation or cache.
                val response = withTimeout(requestTimeoutMs) { synthesize(phrase) }
                ensureActive()
                if (ticket != epoch || !canPreview()) return@launch
                require(response.audioData.isNotEmpty())
                mutable.value = VoicePreviewState(VoicePreviewStage.PLAYING)
                play(response)
            } catch (_: TimeoutCancellationException) {
                if (ticket == epoch) mutable.value = VoicePreviewState(VoicePreviewStage.FAILED,
                    "本次 TTS 试听等待超时，未自动重试；可以检查语音服务后重新试听。")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Provider messages can include headers or credentials; do not surface them here.
                if (ticket == epoch) mutable.value = VoicePreviewState(VoicePreviewStage.FAILED,
                    "本次 TTS 合成或播放未完成，请检查当前语音服务后重试。聊天和通话未被修改。")
            } finally {
                if (ticket == epoch) {
                    if (mutable.value.stage != VoicePreviewStage.FAILED) mutable.value = VoicePreviewState()
                    job = null
                }
            }
        }
        return true
    }

    fun stop() {
        epoch++
        job?.cancel()
        job = null
        runCatching(stopPlayer)
        mutable.value = VoicePreviewState()
    }

    fun close() { closed = true; stop() }
}
