package me.rerere.tts.controller

/** Per-player gain only. Muting never owns, pauses, resets or seeks the playback timeline. */
internal class PlaybackOutputGain {
    private var volume = 1f
    private var muted = false

    val effectiveVolume: Float get() = if (muted) 0f else volume

    fun setVolume(value: Float) {
        require(value.isFinite()) { "Output volume must be finite" }
        volume = value.coerceIn(0f, 1f)
    }

    fun setMuted(value: Boolean) { muted = value }
}
