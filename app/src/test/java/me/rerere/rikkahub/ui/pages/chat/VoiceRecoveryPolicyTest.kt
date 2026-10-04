package me.rerere.rikkahub.ui.pages.chat

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VoiceRecoveryPolicyTest {
    @Test fun permanentFocusLossRequiresExplicitHumanActionEvenAfterLateGain() {
        val lost = nextVoiceAudioFocusState(VoiceAudioFocusState.AVAILABLE, VoiceAudioFocusEvent.PERMANENT_LOSS)
        assertEquals(VoiceAudioFocusState.MANUAL_PAUSE, lost)
        assertEquals(lost, nextVoiceAudioFocusState(lost, VoiceAudioFocusEvent.GAIN))
        assertEquals(lost, nextVoiceAudioFocusState(lost, VoiceAudioFocusEvent.TRANSIENT_LOSS))
    }

    @Test fun transientFocusLossMayResumeOnlyFromSystemGain() {
        val lost = nextVoiceAudioFocusState(VoiceAudioFocusState.AVAILABLE, VoiceAudioFocusEvent.TRANSIENT_LOSS)
        assertEquals(VoiceAudioFocusState.TRANSIENT_PAUSE, lost)
        assertEquals(VoiceAudioFocusState.AVAILABLE, nextVoiceAudioFocusState(lost, VoiceAudioFocusEvent.GAIN))
    }

    @Test fun onlyThreeIdleAsrRetriesAreAllowedWithExactBackoff() {
        val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_LISTEN, IOException("Connection reset"))
        assertEquals(listOf(1000L, 3000L, 8000L, null), (0..3).map { VoiceRecoveryPolicy.retryDelay(it, failure, true) })
        assertNull(VoiceRecoveryPolicy.retryDelay(0, failure, false))
        assertNull(VoiceRecoveryPolicy.retryDelay(0, VoiceSessionFailure(VoiceFailureStage.ASR_LISTEN, "ASR_LISTEN_NETWORK_RESET", true, true), true))
    }

    @Test fun modelToolsTtsAndFinalTranscriptionAreNeverRetriedByAsrPolicy() {
        for (stage in listOf(VoiceFailureStage.MODEL, VoiceFailureStage.TTS, VoiceFailureStage.ASR_FINAL, VoiceFailureStage.CONNECT)) {
            assertNull(VoiceRecoveryPolicy.retryDelay(0, VoiceRecoveryPolicy.failure(stage, IOException("Connection reset")), true))
        }
    }

    @Test fun retryBudgetOnlyRenewsAfterFullFiveMinutesOfConfirmedListening() {
        val budget = VoiceReconnectBudget()
        val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_LISTEN, IOException("Connection reset"))
        assertEquals(1000L, budget.nextRetry(0, failure, true))
        budget.beginListening(1_000)
        assertEquals(3000L, budget.nextRetry(300_999, failure, true))
        assertEquals(2, budget.attemptsUsed)
        budget.beginListening(304_000)
        assertEquals(1000L, budget.nextRetry(604_000, failure, true))
        assertEquals(1, budget.attemptsUsed)
    }

    @Test fun reconnectWaitingAndDisconnectedTimeNeverRenewBudget() {
        val budget = VoiceReconnectBudget()
        val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_LISTEN, IOException("Connection reset"))
        assertEquals(1000L, budget.nextRetry(0, failure, true))
        budget.beginListening(1_000)
        budget.stopListening(2_000)
        // Long focus pause / connecting time has no Listening observation.
        assertEquals(3000L, budget.nextRetry(8 * 60 * 60 * 1000L, failure, true))
        budget.beginListening(30_000_000)
        budget.stopListening(30_100_000)
        budget.beginListening(30_200_000)
        assertEquals(8000L, budget.nextRetry(30_400_000, failure, true))
        assertNull(budget.nextRetry(30_400_001, failure, true))
    }

    @Test fun diagnosticsHaveStageButNeverProviderTextOrCause() {
        val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.TTS,
            IOException("Connection reset; Authorization Bearer secret-private-token https://secret.example/private-message"))
        assertEquals("TTS_NETWORK_RESET", failure.code)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("secret"))
        assertFalse(failure.toString().contains("private-message"))
    }

    @Test fun authenticationAndRecorderFailuresAreNotNetworkReconnectSignals() {
        for (text in listOf("HTTP 401 unauthorized", "Microphone permission is required", "AudioRecord read error: -6")) {
            val failure = VoiceRecoveryPolicy.failure(VoiceFailureStage.ASR_LISTEN, IllegalStateException(text))
            assertFalse(failure.network)
            assertNull(VoiceRecoveryPolicy.retryDelay(0, failure, true))
        }
    }
}
