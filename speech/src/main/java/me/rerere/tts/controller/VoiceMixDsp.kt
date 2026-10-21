package me.rerere.tts.controller

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Bounded, streaming PCM processing after decoding, before output. Never owns an audio
 * device, focus, synthesis or the media clock. One instance belongs to one player.
 * Mono is duplicated to stereo so room reflections can differ left/right without
 * changing format when the mixer is enabled during a sentence.
 */
internal class VoiceMixDsp(val sampleRate: Int, val inputChannels: Int) {
    init {
        require(sampleRate in 8_000..192_000)
        require(inputChannels in 1..8)
    }

    val outputChannels = inputChannels.coerceAtLeast(2)
    private val ringSize = (sampleRate * .4).roundToInt() + 1
    private val reflections = Array(outputChannels) { FloatArray(ringSize) }
    private val low = FloatArray(outputChannels)
    private val high = FloatArray(outputChannels)
    private var ringPosition = 0
    private var frame = 0L
    private var previous: VoiceMix? = null
    private var wasEnabled = false
    private var effectBlend = 0f
    private val blendStep = 1f / (sampleRate * .02f)
    private var lowAlpha = 1f
    private var highAlpha = 0f
    private var distanceGain = 1f
    private var delayA = 1
    private var delayB = 1
    private var feedback = 0f
    private var wet = 0f

    fun reset() {
        reflections.forEach { it.fill(0f) }
        low.fill(0f)
        high.fill(0f)
        ringPosition = 0
        frame = 0L
        previous = null
        effectBlend = 0f
        wasEnabled = false
    }

    private fun prepare(config: VoiceMixerConfig) {
        val mix = config.mix
        if (!config.enabled) {
            // Never bring the old room tail back when enabling after bypass.
            if (wasEnabled) reset()
            return
        }
        wasEnabled = true
        if (previous == mix) return
        previous = mix
        val cutoff = when (mix.filter) {
            VoiceFilter.TELEPHONE -> 3_300f
            VoiceFilter.RADIO -> 2_400f
            VoiceFilter.SOFT -> 3_800f
            else -> 18_000f
        } * (1f - .65f * mix.distance)
        lowAlpha = if (mix.filter == VoiceFilter.NONE && mix.distance == 0f) 1f
            else (1.0 - exp(-2 * PI * cutoff.coerceAtMost(sampleRate * .45f) / sampleRate)).toFloat()
        val highCutoff = when (mix.filter) {
            VoiceFilter.TELEPHONE -> 300f
            VoiceFilter.RADIO -> 450f
            else -> 0f
        }
        highAlpha = if (highCutoff == 0f) 0f
            else (1.0 - exp(-2 * PI * highCutoff / sampleRate)).toFloat()
        distanceGain = 1f / (1f + 2.7f * mix.distance)
        val room = when (mix.space) {
            VoiceSpace.NONE -> Room(0f, 0f, 0f)
            VoiceSpace.SMALL_ROOM -> Room(.021f, .043f, .2f)
            VoiceSpace.LARGE_ROOM -> Room(.043f, .079f, .3f)
            VoiceSpace.HALL -> Room(.079f, .137f, .42f)
            VoiceSpace.TUNNEL -> Room(.127f, .219f, .47f)
            VoiceSpace.OPEN_AIR -> Room(.163f, .291f, .12f)
            VoiceSpace.FOREST -> Room(.051f, .113f, .14f)
        }
        delayA = (sampleRate * room.a).roundToInt().coerceAtLeast(1)
        delayB = (sampleRate * room.b).roundToInt().coerceAtLeast(1)
        feedback = room.feedback
        wet = if (mix.space == VoiceSpace.NONE) 0f else mix.reverb * .48f
    }

    fun process(input: ByteBuffer, output: ByteBuffer, config: VoiceMixerConfig) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        prepare(config)
        val mix = config.mix
        val processing = config.enabled &&
            (mix.filter != VoiceFilter.NONE || mix.distance > 0f || wet > 0f)
        while (input.remaining() >= inputChannels * 2) {
            effectBlend = (effectBlend + if (processing) blendStep else -blendStep).coerceIn(0f, 1f)
            val mono = if (inputChannels == 1) input.short else 0
            val robot = if (mix.filter == VoiceFilter.ROBOT) (.4 + .6 * cos(2 * PI * 62 * frame / sampleRate)).toFloat() else 1f
            for (channel in 0 until outputChannels) {
                val original = if (inputChannels == 1) mono.toShort() else input.short
                val dry = original / 32768f
                if (!processing && effectBlend == 0f) {
                    output.putShort(original)
                    continue
                }
                low[channel] += lowAlpha * (dry - low[channel])
                high[channel] += highAlpha * (low[channel] - high[channel])
                var filtered = (low[channel] - if (highAlpha > 0f) high[channel] else 0f) * robot
                if (mix.filter == VoiceFilter.RADIO) {
                    // A bounded, mild radio saturation, not a clipped arbitrary volume boost.
                    filtered = filtered * 1.6f / (1f + abs(filtered) * 1.6f)
                }
                val ring = reflections[channel]
                val sideOffset = if (channel % 2 == 0) 0 else sampleRate / 137
                val a = ring[(ringPosition - delayA - sideOffset + ringSize) % ringSize]
                val b = ring[(ringPosition - delayB + sideOffset + ringSize) % ringSize]
                val reflected = a * .65f + b * .35f
                ring[ringPosition] = if (wet > 0f) (filtered + reflected * feedback).coerceIn(-1f, 1f) else 0f
                val effected = (filtered * (1f - wet * .3f) + reflected * wet) * distanceGain
                val value = dry + (effected - dry) * effectBlend
                output.putShort((value * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            ringPosition = (ringPosition + 1) % ringSize
            frame++
        }
    }

    private data class Room(val a: Float, val b: Float, val feedback: Float)
}
