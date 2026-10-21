package me.rerere.tts.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.pow

@Serializable
enum class VoiceSpace { NONE, SMALL_ROOM, LARGE_ROOM, HALL, TUNNEL, OPEN_AIR, FOREST }

@Serializable
enum class VoiceFilter { NONE, TELEPHONE, RADIO, ROBOT, SOFT }

/** Playback-only settings. No service credentials, audio copies, synthesis or microphone work. */
@Serializable
data class VoiceMix(
    val speed: Float = 1f,
    val semitones: Float = 0f,
    val volume: Float = 1f,
    val space: VoiceSpace = VoiceSpace.NONE,
    val distance: Float = 0f,
    val reverb: Float = .25f,
    val filter: VoiceFilter = VoiceFilter.NONE,
) {
    fun validate() {
        require(speed.isFinite() && speed in .5f..2f)
        require(semitones.isFinite() && semitones in -12f..12f)
        require(volume.isFinite() && volume in 0f..1f)
        require(distance.isFinite() && distance in 0f..1f)
        require(reverb.isFinite() && reverb in 0f..1f)
    }
    val pitch: Float get() = 2.0.pow(semitones / 12.0).toFloat()
}

@Serializable
data class VoicePreset(val name: String, val mix: VoiceMix)

@Serializable
data class VoiceMixerConfig(
    val enabled: Boolean = false,
    val mix: VoiceMix = VoiceMix(),
    val presets: List<VoicePreset> = emptyList(),
) {
    fun validate() {
        mix.validate()
        require(presets.size <= 100 && presets.map { it.name }.distinct().size == presets.size)
        presets.forEach { require(it.name.isNotBlank() && it.name.length <= 40 && it.name.none(Char::isISOControl)); it.mix.validate() }
    }
    fun effective(baseSpeed: Float = 1f, baseVolume: Float = 1f): VoiceMix {
        // Bypass preserves the pre-existing caller's speed/volume, not a forced 1x/reset.
        return if (!enabled) VoiceMix(baseSpeed, 0f, baseVolume)
        else mix.copy(speed = (baseSpeed * mix.speed).coerceIn(.25f, 4f), volume = baseVolume * mix.volume)
    }
}

/** A small process-local signal; persistence belongs to the host SettingsStore. */
object VoiceMixer {
    private val mutable = MutableStateFlow(VoiceMixerConfig())
    val config = mutable.asStateFlow()
    fun apply(value: VoiceMixerConfig) { value.validate(); mutable.value = value }
}
