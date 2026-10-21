package me.rerere.tts.controller

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class VoiceMixerTest {
    @Test fun enabledButNeutralIsStillUnchangedOriginal() {
        val original = VoiceMix(1.15f, 0f, .75f)
        assertEquals(original, VoiceMixerConfig(enabled = true).effective(original.speed, original.volume))
        assertEquals(1f, VoiceMixerConfig(enabled = true).effective().pitch)
    }
    @Test fun savedMixAppliesToFuturePlayersWithoutOpeningMixerPage() {
        val saved = VoiceMixerConfig(true, VoiceMix(.9f, -3f, .8f))
        val restored = Json.decodeFromString<VoiceMixerConfig>(Json.encodeToString(saved))
        assertEquals(saved.effective(), restored.effective())
        assertEquals(saved.effective(1.2f,.5f), restored.effective(1.2f,.5f))
    }
    @Test fun bypassKeepsCallerSpeedAndMute() {
        val mix = VoiceMixerConfig(mix = VoiceMix(.7f, -9f, .8f)).effective(1.3f, 0f)
        assertEquals(1.3f, mix.speed); assertEquals(1f, mix.pitch); assertEquals(0f, mix.volume)
    }
    @Test fun pitchAndSpeedAreIndependent() {
        assertEquals(2f, VoiceMix(semitones = 12f).pitch)
        assertEquals(.5f, VoiceMix(semitones = -12f).pitch)
        assertEquals(1f, VoiceMix(speed = 2f).pitch)
    }
    @Test fun mixerNeverUnmutesPlayer() {
        assertEquals(0f, VoiceMixerConfig(true, VoiceMix(volume = 1f)).effective(baseVolume = 0f).volume)
    }
    @Test fun oldSettingsHaveOriginalAudio() {
        assertFalse(Json.decodeFromString<VoiceMixerConfig>("{}").enabled)
    }
    @Test fun roundTripPresets() {
        val original = VoiceMixerConfig(true, VoiceMix(.9f, -3f), listOf(VoicePreset("夜晚", VoiceMix(1.1f, 2f))))
        assertEquals(original, Json.decodeFromString<VoiceMixerConfig>(Json.encodeToString(original)))
    }
    @Test fun invalidNumbersCannotReachPlayback() {
        listOf(VoiceMix(Float.NaN), VoiceMix(semitones = Float.POSITIVE_INFINITY), VoiceMix(volume = -1f),
            VoiceMix(distance = Float.NaN), VoiceMix(reverb = 1.01f)).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
    }
    @Test fun spatialSettingsRoundTripAndSurviveCallerVolumeOrSpeed() {
        val mix = VoiceMix(.9f, -2f, .8f, VoiceSpace.HALL, .3f, .5f, VoiceFilter.RADIO)
        val saved = VoiceMixerConfig(true, mix, listOf(VoicePreset("回响", mix)))
        val restored = Json.decodeFromString<VoiceMixerConfig>(Json.encodeToString(saved))
        restored.validate()
        assertEquals(saved, restored)
        assertEquals(mix.copy(speed = .9f * 1.2f, volume = 0f), restored.effective(1.2f, 0f))
        assertEquals(VoiceSpace.NONE, restored.copy(enabled = false).effective().space)
        assertEquals(VoiceFilter.NONE, restored.copy(enabled = false).effective().filter)
    }
    @Test fun preSpatialSettingsDecodeWithoutMigrationOrChangingOldSound() {
        val old = Json.decodeFromString<VoiceMixerConfig>(
            """{"enabled":true,"mix":{"speed":0.9,"semitones":-2.0,"volume":0.8},"presets":[]}""")
        old.validate()
        assertEquals(VoiceSpace.NONE, old.mix.space)
        assertEquals(VoiceFilter.NONE, old.mix.filter)
        assertEquals(0f, old.mix.distance)
    }
}
