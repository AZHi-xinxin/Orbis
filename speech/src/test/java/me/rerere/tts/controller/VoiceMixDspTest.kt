package me.rerere.tts.controller

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceMixDspTest {
    private val rate = 16_000
    private fun tone(hz: Int = 440, frames: Int = rate): ShortArray =
        ShortArray(frames) { (sin(2 * PI * hz * it / rate) * 10_000).toInt().toShort() }
    private fun apply(dsp: VoiceMixDsp, samples: ShortArray, config: VoiceMixerConfig): ShortArray {
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(input::putShort)
        input.flip()
        val output = ByteBuffer.allocate(samples.size / dsp.inputChannels * dsp.outputChannels * 2)
        dsp.process(input, output, config)
        assertEquals(0, input.remaining())
        output.flip()
        return ShortArray(output.remaining() / 2) { output.short }
    }
    private fun energy(samples: ShortArray): Double =
        samples.drop(rate / 4).map { it.toDouble() * it }.average()

    @Test fun disabledMonoIsExactDualMonoIncludingFullScale() {
        val input = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val actual = apply(VoiceMixDsp(rate, 1), input,
            VoiceMixerConfig(false, VoiceMix(space = VoiceSpace.TUNNEL, filter = VoiceFilter.ROBOT)))
        input.forEachIndexed { i, value -> assertEquals(value, actual[i * 2]); assertEquals(value, actual[i * 2 + 1]) }
    }

    @Test fun enabledNeutralStereoIsBitExactAndKeepsChannelOrder() {
        val input = shortArrayOf(-123, 456, 1000, -2000, Short.MIN_VALUE, Short.MAX_VALUE)
        assertArrayEquals(input, apply(VoiceMixDsp(rate, 2), input, VoiceMixerConfig(true)))
    }

    @Test fun distanceActuallyReducesLevelAndHighFrequencies() {
        val original = apply(VoiceMixDsp(rate, 1), tone(), VoiceMixerConfig())
        val far = apply(VoiceMixDsp(rate, 1), tone(), VoiceMixerConfig(true, VoiceMix(distance = 1f)))
        assertTrue(energy(far) < energy(original) * .15)
    }

    @Test fun telephoneReallyFiltersLowAndHighFrequenciesRatherThanChangingOnlyLabel() {
        val config = VoiceMixerConfig(true, VoiceMix(filter = VoiceFilter.TELEPHONE))
        val low = energy(apply(VoiceMixDsp(rate, 1), tone(60), config))
        val speechBand = energy(apply(VoiceMixDsp(rate, 1), tone(1_000), config))
        val high = energy(apply(VoiceMixDsp(rate, 1), tone(7_000), config))
        assertTrue(low < speechBand * .15)
        assertTrue(high < speechBand * .65)
    }

    @Test fun eachRoomProducesDelayedStereoReflectionsAndNoneDoesNot() {
        val impulse = ShortArray(rate / 2).also { it[rate / 10] = 24_000 }
        val none = apply(VoiceMixDsp(rate, 1), impulse, VoiceMixerConfig(true))
        assertTrue(none.drop(rate / 5 + 2).all { it.toInt() == 0 })
        VoiceSpace.entries.filterNot { it == VoiceSpace.NONE }.forEach { room ->
            val actual = apply(VoiceMixDsp(rate, 1), impulse,
                VoiceMixerConfig(true, VoiceMix(space = room, reverb = 1f)))
            assertTrue("$room must generate a tail", actual.drop(rate / 5 + 2).any { it.toInt() != 0 })
            assertTrue("$room must have distinct left/right reflections",
                (0 until actual.size / 2).any { actual[it * 2] != actual[it * 2 + 1] })
        }
    }

    @Test fun zeroReverbDoesNotPretendToHaveASpaceEffect() {
        val input = tone()
        val output = apply(VoiceMixDsp(rate, 1), input,
            VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.TUNNEL, reverb = 0f)))
        input.forEachIndexed { i, value -> assertEquals(value, output[i * 2]) }
    }

    @Test fun robotAndRadioAndSoftFilterEachChangeTheActualSamples() {
        val input = tone(2_200)
        val dry = apply(VoiceMixDsp(rate, 1), input, VoiceMixerConfig())
        listOf(VoiceFilter.ROBOT, VoiceFilter.RADIO, VoiceFilter.SOFT).forEach { filter ->
            val output = apply(VoiceMixDsp(rate, 1), input, VoiceMixerConfig(true, VoiceMix(filter = filter)))
            assertFalse("$filter must process samples", dry.contentEquals(output))
            assertTrue(output.any { abs(it.toInt()) > 200 })
        }
    }

    @Test fun arbitraryChunkingProducesTheSameAudioAndDoesNotChangeFrameCount() {
        val samples = tone(frames = 9_003)
        val config = VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.HALL, reverb = .8f,
            filter = VoiceFilter.TELEPHONE, distance = .2f))
        val expected = apply(VoiceMixDsp(rate, 1), samples, config)
        val dsp = VoiceMixDsp(rate, 1)
        val chunks = samples.toList().chunked(137).flatMap { apply(dsp, it.toShortArray(), config).toList() }.toShortArray()
        assertArrayEquals(expected, chunks)
    }

    @Test fun liveBypassIsExactAndOldRoomTailDoesNotReturnAfterEnable() {
        val dsp = VoiceMixDsp(rate, 1)
        val wet = VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.HALL, reverb = 1f))
        apply(dsp, tone(frames = rate / 2), wet)
        val dryInput = shortArrayOf(10, -321, 100)
        val dry = apply(dsp, dryInput, VoiceMixerConfig(false, wet.mix))
        dryInput.forEachIndexed { i, value -> assertEquals(value, dry[i * 2]) }
        val silence = apply(dsp, ShortArray(rate / 2), wet)
        assertTrue(silence.all { it.toInt() == 0 })
    }

    @Test fun playersDoNotShareEchoHistoryAndResetClearsPrivateAudio() {
        val config = VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.TUNNEL, reverb = 1f))
        val first = VoiceMixDsp(rate, 1)
        apply(first, tone(), config)
        val second = apply(VoiceMixDsp(rate, 1), ShortArray(rate), config)
        assertTrue(second.all { it.toInt() == 0 })
        first.reset()
        assertTrue(apply(first, ShortArray(rate), config).all { it.toInt() == 0 })
    }

    @Test fun allEffectsHandleExtremeInputWithoutOverflowOrGrowingFeedback() {
        val config = VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.TUNNEL, reverb = 1f,
            filter = VoiceFilter.RADIO))
        val dsp = VoiceMixDsp(rate, 1)
        val output = apply(dsp, ShortArray(rate) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }, config)
        assertEquals(rate * 2, output.size)
        repeat(8) { apply(dsp, ShortArray(rate), config) }
        assertTrue(apply(dsp, ShortArray(rate), config).all { abs(it.toInt()) <= 1 })
    }
}
