package me.rerere.tts.controller

import me.rerere.tts.provider.TTSProviderSetting

/** Immutable playback inputs, captured before synthesis, not when its response arrives. */
data class VoicePlaybackSnapshot(
    val config: VoiceMixerConfig,
    val baseSpeed: Float,
) {
    init {
        config.validate()
        require(baseSpeed.isFinite() && baseSpeed > 0f)
    }

    companion object {
        fun capture(
            baseSpeed: Float = 1f,
            config: VoiceMixerConfig = VoiceMixer.config.value,
        ) = VoicePlaybackSnapshot(config.copy(presets = emptyList()), baseSpeed)
    }
}

internal data class TtsRound(
    val provider: TTSProviderSetting,
    val playback: VoicePlaybackSnapshot,
)

internal data class QueuedTtsChunk(val chunk: TtsChunk, val round: TtsRound) {
    val id get() = chunk.id
    val index get() = chunk.index
}

/** Keep an utterance intact unless the selected adapter has an established input limit. */
internal fun planTtsRound(
    text: String,
    provider: TTSProviderSetting,
    playback: VoicePlaybackSnapshot,
    systemInputLimit: Int,
): List<QueuedTtsChunk> {
    if (text.isBlank()) return emptyList()
    val round = TtsRound(provider.copyProvider(), playback)
    val limit = when (provider) {
        is TTSProviderSetting.SystemTTS -> systemInputLimit.also { require(it >= 2) }
        is TTSProviderSetting.ElevenLabs -> if (provider.model == "eleven_v4") 2_000 else null
        else -> null
    }
    val pieces = if (limit == null) listOf(text) else splitAtInputLimit(text, limit)
    return pieces.mapIndexed { index, piece -> QueuedTtsChunk(TtsChunk(index = index, text = piece), round) }
}

private fun splitAtInputLimit(text: String, limit: Int): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length) {
            // Never cut a UTF-16 surrogate pair; prefer a nearby sentence/word boundary.
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val boundary = (end - 1 downTo start + (end - start) / 2).firstOrNull {
                text[it].isWhitespace() || text[it] in "。！？.!?;；"
            }
            if (boundary != null) end = boundary + 1
        }
        add(text.substring(start, end))
        start = end
    }
}
