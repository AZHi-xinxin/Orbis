package me.rerere.tts.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PlaybackOutputGainTest {
    @Test fun `mute preserves the selected per-player gain`() {
        val gain = PlaybackOutputGain()
        gain.setVolume(0.6f)
        gain.setMuted(true)
        assertEquals(0f, gain.effectiveVolume, 0f)
        gain.setMuted(false)
        assertEquals(0.6f, gain.effectiveVolume, 0f)
    }

    @Test fun `changing gain during mute cannot leak sound`() {
        val gain = PlaybackOutputGain()
        gain.setMuted(true)
        gain.setVolume(0.3f)
        assertEquals(0f, gain.effectiveVolume, 0f)
        gain.setMuted(false)
        assertEquals(0.3f, gain.effectiveVolume, 0f)
    }

    @Test fun `repeated mute unmute is idempotent and clamps finite volume`() {
        val gain = PlaybackOutputGain()
        gain.setVolume(2f)
        repeat(3) { gain.setMuted(true) }
        repeat(3) { gain.setMuted(false) }
        assertEquals(1f, gain.effectiveVolume, 0f)
        gain.setVolume(-1f)
        assertEquals(0f, gain.effectiveVolume, 0f)
    }

    @Test fun `nonfinite gain is rejected without changing previous gain`() {
        val gain = PlaybackOutputGain()
        gain.setVolume(0.4f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            try { gain.setVolume(it); fail("nonfinite volume accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0.4f, gain.effectiveVolume, 0f)
        }
    }
}
