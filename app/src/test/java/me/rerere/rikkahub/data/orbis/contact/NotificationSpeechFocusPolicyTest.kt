package me.rerere.rikkahub.data.orbis.contact

import org.junit.Assert.*
import org.junit.Test

class NotificationSpeechFocusPolicyTest {
    private fun active(budget: Long = 1_000) = NotificationSpeechFocusPolicy(budget).also {
        it.beginRequest()
        it.requestReturned(NotificationSpeechFocusResult.GRANTED, 0)
    }

    @Test fun onlyGrantedOrLaterGainCanAuthorizePlayback() {
        val policy = NotificationSpeechFocusPolicy()
        policy.change(NotificationSpeechFocusChange.GAIN, 0)
        assertFalse(policy.canPlay)
        policy.beginRequest()
        policy.requestReturned(NotificationSpeechFocusResult.DELAYED, 0)
        assertFalse(policy.canPlay)
        policy.change(NotificationSpeechFocusChange.GAIN, 999)
        assertTrue(policy.canPlay)
    }

    @Test fun transientAndDuckPauseInsteadOfTerminating() {
        for (change in listOf(NotificationSpeechFocusChange.TRANSIENT_LOSS, NotificationSpeechFocusChange.DUCK)) {
            val policy = active()
            policy.change(change, 10)
            assertFalse(policy.canPlay)
            assertFalse(policy.finished)
            assertNull(policy.reasonCode)
            policy.change(NotificationSpeechFocusChange.GAIN, 500)
            assertTrue(policy.canPlay)
        }
    }

    @Test fun repeatedLossDoesNotMoveDeadline() {
        val policy = active()
        policy.change(NotificationSpeechFocusChange.DUCK, 10)
        policy.change(NotificationSpeechFocusChange.TRANSIENT_LOSS, 900)
        policy.tick(1_010)
        assertEquals("audio_focus_timeout", policy.reasonCode)
        policy.change(NotificationSpeechFocusChange.GAIN, 1_011)
        assertFalse(policy.canPlay)
    }

    @Test fun gainsDoNotRefillCumulativeWaitingBudget() {
        val policy = active()
        policy.change(NotificationSpeechFocusChange.DUCK, 100)
        policy.change(NotificationSpeechFocusChange.GAIN, 700) // 600ms used
        policy.change(NotificationSpeechFocusChange.TRANSIENT_LOSS, 10_000)
        policy.change(NotificationSpeechFocusChange.GAIN, 10_400)
        assertEquals("audio_focus_timeout", policy.reasonCode)
        assertFalse(policy.canPlay)
    }

    @Test fun delayedGrantConsumesTheSameWaitingBudget() {
        val policy = NotificationSpeechFocusPolicy(1_000)
        policy.beginRequest()
        policy.requestReturned(NotificationSpeechFocusResult.DELAYED, 0)
        policy.change(NotificationSpeechFocusChange.GAIN, 600)
        policy.change(NotificationSpeechFocusChange.DUCK, 900)
        policy.tick(1_300)
        assertEquals("audio_focus_timeout", policy.reasonCode)
    }

    @Test fun permanentLossAndDenialCannotBeRevived() {
        val loss = active()
        loss.change(NotificationSpeechFocusChange.LOSS, 1)
        val denied = NotificationSpeechFocusPolicy().also {
            it.beginRequest(); it.requestReturned(NotificationSpeechFocusResult.DENIED, 0)
        }
        for (policy in listOf(loss, denied)) {
            val reason = policy.reasonCode
            policy.change(NotificationSpeechFocusChange.GAIN, 2)
            policy.cancel("different")
            assertFalse(policy.canPlay)
            assertEquals(reason, policy.reasonCode)
        }
    }

    @Test fun synchronousLossCallbackWinsOverGrantedReturn() {
        val policy = NotificationSpeechFocusPolicy()
        policy.beginRequest()
        policy.change(NotificationSpeechFocusChange.DUCK, 5)
        policy.requestReturned(NotificationSpeechFocusResult.GRANTED, 6)
        assertFalse(policy.canPlay)
        policy.change(NotificationSpeechFocusChange.GAIN, 7)
        assertTrue(policy.canPlay)
    }

    @Test fun finishedPolicyRejectsAllLateCallbacks() {
        val policy = active()
        policy.finish()
        NotificationSpeechFocusChange.entries.forEach { policy.change(it, 500) }
        assertFalse(policy.canPlay)
        assertNull(policy.reasonCode)
        assertEquals(NotificationSpeechFocusPolicy.Phase.FINISHED, policy.phase)
    }

    @Test fun focusCannotBeRequestedTwice() {
        val policy = active()
        assertTrue(runCatching { policy.beginRequest() }.exceptionOrNull() is IllegalStateException)
    }
}
