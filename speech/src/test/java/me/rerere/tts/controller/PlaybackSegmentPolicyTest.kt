package me.rerere.tts.controller

import org.junit.Assert.*
import org.junit.Test

class PlaybackSegmentPolicyTest {
    @Test fun savedGlobalRateAndPitchApplyAtTheFirstItemBoundary() {
        val config = VoiceMixerConfig(true, VoiceMix(speed = 1.25f, semitones = -12f))
        assertEquals(PlaybackRate(1.5f, .5f), playbackRateAtBoundary(config, 1.2f, false))
    }

    @Test fun repeatedSliderChangesNeverApplyDuringAnOwnedItem() {
        repeat(100) { step ->
            val config = VoiceMixerConfig(true, VoiceMix(.5f + step / 100f, -12f + step / 5f))
            assertNull(playbackRateAtBoundary(config, 1f, true))
        }
    }

    @Test fun theNextBoundaryUsesTheLatestSettingsNotTheFirstPendingChange() {
        val first = VoiceMixerConfig(true, VoiceMix(.75f, -12f))
        val latest = VoiceMixerConfig(true, VoiceMix(1.5f, 12f))
        assertNull(playbackRateAtBoundary(first, 1f, true))
        assertNull(playbackRateAtBoundary(latest, 1f, true))
        assertEquals(PlaybackRate(1.5f, 2f), playbackRateAtBoundary(latest, 1f, false))
    }

    @Test fun bypassAlsoWaitsForTheBoundaryThenPreservesTheCallerSpeed() {
        val bypass = VoiceMixerConfig(false, VoiceMix(2f, 12f))
        assertNull(playbackRateAtBoundary(bypass, 1.3f, true))
        assertEquals(PlaybackRate(1.3f, 1f), playbackRateAtBoundary(bypass, 1.3f, false))
    }

    @Test fun callerSpeedChangesAreDeferredAlongWithMixerRateChanges() {
        val config = VoiceMixerConfig(true, VoiceMix(1.2f, 0f))
        assertNull(playbackRateAtBoundary(config, 1.5f, true))
        assertEquals(PlaybackRate(1.2f * 1.5f, 1f), playbackRateAtBoundary(config, 1.5f, false))
    }

    @Test fun volumeAndSpatialEditsDoNotRequireAPlayingSinkRateUpdate() {
        val config = VoiceMixerConfig(true, VoiceMix(volume = 0f, space = VoiceSpace.HALL,
            reverb = .8f, filter = VoiceFilter.TELEPHONE))
        assertNull(playbackRateAtBoundary(config, 1f, true))
        assertEquals(PlaybackRate(1f, 1f), playbackRateAtBoundary(config, 1f, false))
    }

    @Test fun combinedRateRemainsBoundedAtTheNewItemBoundary() {
        assertEquals(PlaybackRate(4f, 1f), playbackRateAtBoundary(
            VoiceMixerConfig(true, VoiceMix(speed = 2f)), 3f, false))
        assertEquals(PlaybackRate(.25f, 1f), playbackRateAtBoundary(
            VoiceMixerConfig(true, VoiceMix(speed = .5f)), .1f, false))
    }

    @Test fun stoppedSetupNeverPreparesOrPlaysAReleasedEngine() {
        var owns = true
        val invoked = mutableListOf<String>()
        val completed = runOwnedPlaybackSetup({ owns },
            { invoked += "set-source"; owns = false },
            { invoked += "prepare" },
            { invoked += "play" },
        )
        assertFalse(completed)
        assertEquals(listOf("set-source"), invoked)
    }

    @Test fun alreadyStaleSetupDoesNotEvenCreateAReplacementSink() {
        var created = false
        assertFalse(runOwnedPlaybackSetup({ false }, { created = true }))
        assertFalse(created)
    }

    @Test fun finalPlayCallbackMayCancelOwnershipWithoutStartingAMonitor() {
        var owns = true
        val completed = runOwnedPlaybackSetup({ owns }, {}, { owns = false })
        assertFalse(completed)
    }

    @Test fun anOwnedSetupRunsEachEngineStepExactlyOnce() {
        val invoked = mutableListOf<String>()
        assertTrue(runOwnedPlaybackSetup({ true },
            { invoked += "prepare" }, { invoked += "play" }))
        assertEquals(listOf("prepare", "play"), invoked)
    }

    @Test fun engineSetupFailureNeverFallsThroughToPlay() {
        var played = false
        val failure = IllegalStateException("synthetic preparation failure")
        val actual = runCatching {
            runOwnedPlaybackSetup({ true }, { throw failure }, { played = true })
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(played)
    }
}
