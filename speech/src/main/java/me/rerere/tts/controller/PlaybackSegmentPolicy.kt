package me.rerere.tts.controller

internal data class PlaybackRate(val speed: Float, val pitch: Float)

/** Rate/pitch changes may drain or reconfigure an AudioSink. Only change them between items. */
internal fun playbackRateAtBoundary(
    config: VoiceMixerConfig,
    baseSpeed: Float,
    segmentInProgress: Boolean,
): PlaybackRate? {
    if (segmentInProgress) return null
    val mix = config.effective(baseSpeed)
    return PlaybackRate(mix.speed, mix.pitch)
}

/** Engine calls may synchronously deliver callbacks which stop or replace their owner. */
internal fun runOwnedPlaybackSetup(ownsPlayback: () -> Boolean, vararg steps: () -> Unit): Boolean {
    for (step in steps) {
        if (!ownsPlayback()) return false
        step()
    }
    return ownsPlayback()
}

internal enum class PlaybackProgressPhase { IDLE, BUFFERING, READY, ENDED }
internal enum class PlaybackStall { UNEXPECTED_IDLE, BUFFERING_STALLED, PLAYBACK_STALLED }

/**
 * Wall-clock liveness of one local audio item, not an audible-sound detector. Silence and
 * output mute are valid while the media clock advances. Pauses/suppression reset the deadline;
 * this policy never resumes or replays audio and never owns focus or the surrounding call.
 */
internal class PlaybackLivenessPolicy(
    private val stalledMillis: Long = 15_000,
    private val bufferingMillis: Long = 30_000,
    private val idleMillis: Long = 2_000,
) {
    private var monitoring = false
    private var lastPosition = 0L
    private var progressAt = 0L
    private var previousTime = 0L
    private var idleAt: Long? = null

    init { require(stalledMillis > 0 && bufferingMillis > 0 && idleMillis > 0) }

    fun observe(
        nowMillis: Long,
        positionMillis: Long,
        phase: PlaybackProgressPhase,
        playWhenReady: Boolean,
        explicitlyPaused: Boolean,
        suppressed: Boolean,
    ): PlaybackStall? {
        // A deliberate stop removes the item's owner before this policy is called. IDLE with
        // a still-owned item is unexpected even if the engine also cleared playWhenReady.
        if (explicitlyPaused || suppressed || phase == PlaybackProgressPhase.ENDED ||
            (!playWhenReady && phase != PlaybackProgressPhase.IDLE)) {
            monitoring = false
            idleAt = null
            return null
        }
        if (!monitoring || positionMillis != lastPosition || nowMillis < previousTime) {
            monitoring = true
            progressAt = nowMillis
            idleAt = null
        }
        previousTime = nowMillis
        lastPosition = positionMillis
        if (phase == PlaybackProgressPhase.IDLE) {
            val since = idleAt ?: nowMillis.also { idleAt = it }
            return if (nowMillis - since >= idleMillis) PlaybackStall.UNEXPECTED_IDLE else null
        }
        idleAt = null
        val elapsed = nowMillis - progressAt
        return when {
            phase == PlaybackProgressPhase.BUFFERING && elapsed >= bufferingMillis -> PlaybackStall.BUFFERING_STALLED
            phase == PlaybackProgressPhase.READY && elapsed >= stalledMillis -> PlaybackStall.PLAYBACK_STALLED
            else -> null
        }
    }
}

internal class PlaybackStalledException(val stall: PlaybackStall) : IllegalStateException(
    "音频播放未能继续，已停止本段。[AUDIO_${stall.name}]",
)
