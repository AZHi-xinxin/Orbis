package me.rerere.tts.controller

import org.junit.Assert.*
import org.junit.Test

class PlaybackLivenessPolicyTest {
    private fun PlaybackLivenessPolicy.sample(
        time: Long,
        position: Long = 0,
        phase: PlaybackProgressPhase = PlaybackProgressPhase.READY,
        requested: Boolean = true,
        paused: Boolean = false,
        suppressed: Boolean = false,
    ) = observe(time, position, phase, requested, paused, suppressed)

    @Test fun readyButNotAdvancingEndsWithinTheStallDeadline() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0))
        assertNull(guard.sample(14_999))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(15_000))
    }

    @Test fun preparingLocalAudioHasItsOwnBoundedGracePeriod() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, phase = PlaybackProgressPhase.BUFFERING))
        assertNull(guard.sample(29_999, phase = PlaybackProgressPhase.BUFFERING))
        assertEquals(PlaybackStall.BUFFERING_STALLED, guard.sample(30_000, phase = PlaybackProgressPhase.BUFFERING))
    }

    @Test fun unexpectedIdleReleasesEvenWhenTheEngineClearsPlayWhenReady() {
        for (requested in listOf(false, true)) {
            val guard = PlaybackLivenessPolicy()
            assertNull(guard.sample(0, phase = PlaybackProgressPhase.IDLE, requested = requested))
            assertNull(guard.sample(1_999, phase = PlaybackProgressPhase.IDLE, requested = requested))
            assertEquals(PlaybackStall.UNEXPECTED_IDLE,
                guard.sample(2_000, phase = PlaybackProgressPhase.IDLE, requested = requested))
        }
    }

    @Test fun briefIdleDuringPreparationDoesNotFailTheItem() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, phase = PlaybackProgressPhase.IDLE))
        assertNull(guard.sample(1_000, phase = PlaybackProgressPhase.BUFFERING))
        assertNull(guard.sample(2_000, position = 100))
    }

    @Test fun longSilenceOrOutputMuteIsValidWhenTheMediaClockMoves() {
        val guard = PlaybackLivenessPolicy()
        // Samples contain no volume or PCM loudness. Zero-gain playback has the same clock.
        repeat(1_000) { step -> assertNull(guard.sample(step * 1_000L, step * 500L)) }
    }

    @Test fun explicitPauseHasNoDeadlineAndResumeStartsAFreshDeadline() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0))
        assertNull(guard.sample(14_000, paused = true, requested = false))
        assertNull(guard.sample(600_000, paused = true, requested = false))
        assertNull(guard.sample(600_001))
        assertNull(guard.sample(615_000))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(615_001))
    }

    @Test fun explicitPauseAlsoPreventsAnIdleFalsePositive() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, phase = PlaybackProgressPhase.IDLE, paused = true, requested = false))
        assertNull(guard.sample(600_000, phase = PlaybackProgressPhase.IDLE, paused = true, requested = false))
    }

    @Test fun playbackSuppressionOrFocusPauseDoesNotCauseRecovery() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0))
        assertNull(guard.sample(14_000, suppressed = true))
        assertNull(guard.sample(600_000, suppressed = true))
        assertNull(guard.sample(600_001))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(615_001))
    }

    @Test fun enginePausedReadyStateIsNotConfusedWithAStalledRequestedPlayback() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, requested = false))
        assertNull(guard.sample(600_000, requested = false))
        assertNull(guard.sample(600_001, requested = true))
    }

    @Test fun progressAndSeekingResetTheNoProgressDeadline() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, position = 10_000))
        assertNull(guard.sample(14_000, position = 11_000))
        assertNull(guard.sample(28_000, position = 1_000))
        assertNull(guard.sample(42_999, position = 1_000))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(43_000, position = 1_000))
    }

    @Test fun stateChurnWithoutProgressCannotResetTheOverallDeadline() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0, phase = PlaybackProgressPhase.BUFFERING))
        assertNull(guard.sample(10_000))
        assertNull(guard.sample(14_000, phase = PlaybackProgressPhase.BUFFERING))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(15_000))
    }

    @Test fun endedItemDoesNotBecomeAFaultOrCarryItsClockIntoAnotherItem() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(0))
        assertNull(guard.sample(50_000, phase = PlaybackProgressPhase.ENDED))
        assertNull(guard.sample(600_000, phase = PlaybackProgressPhase.ENDED))
        assertNull(guard.sample(600_001))
    }

    @Test fun newItemDoesNotInheritTheFailedItemsDeadline() {
        val failed = PlaybackLivenessPolicy()
        assertNull(failed.sample(0))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, failed.sample(15_000))
        val next = PlaybackLivenessPolicy()
        assertNull(next.sample(15_001))
        assertNull(next.sample(30_000))
    }

    @Test fun elapsedClockRollbackReanchorsInsteadOfInventingAFailure() {
        val guard = PlaybackLivenessPolicy()
        assertNull(guard.sample(100_000))
        assertNull(guard.sample(1_000))
        assertNull(guard.sample(15_999))
        assertEquals(PlaybackStall.PLAYBACK_STALLED, guard.sample(16_000))
    }
}
