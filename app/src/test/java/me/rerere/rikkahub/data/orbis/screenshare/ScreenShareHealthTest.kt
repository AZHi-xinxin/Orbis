package me.rerere.rikkahub.data.orbis.screenshare

import org.junit.Assert.*
import org.junit.Test

class ScreenShareHealthTest {
    private val fresh = ScreenShareHealthInput(active = true, frameAt = 100_000, now = 100_001)
    @Test fun screenToggleAloneIsNotProofOfReady() {
        assertEquals(ScreenShareHealth.CONNECTING, screenShareHealth(fresh.copy(frameAt = 0)).health)
        assertEquals(ScreenShareHealth.READY, screenShareHealth(fresh).health)
        assertEquals("画面就绪", screenShareHealth(fresh).text)
    }
    @Test fun longIdleOrUnchangedScreenDoesNotInventModelDisconnection() {
        assertEquals(ScreenShareHealth.READY, screenShareHealth(fresh.copy(frameAt = 500_000, now = 500_001)).health)
        assertEquals(ScreenShareHealth.STALE, screenShareHealth(fresh.copy(now = 200_000)).health)
        assertEquals(ScreenShareHealth.READY, screenShareHealth(fresh.copy(now = 200_000, intervalSeconds = 120)).health)
    }
    @Test fun realGenerationAndQueueAreBusyNotDisconnected() {
        assertEquals(ScreenShareHealth.BUSY, screenShareHealth(fresh.copy(generationActive = true)).health)
        assertEquals(ScreenShareHealth.BUSY, screenShareHealth(fresh.copy(queued = true)).health)
    }
    @Test fun unresolvedSideEffectsAndVoiceHoldNeverOfferBlindRetry() {
        for (blocked in listOf(fresh.copy(pendingTool = true), fresh.copy(recoveryBlocked = true))) {
            assertFalse(screenShareHealth(blocked).canRetry)
        }
        assertEquals(ScreenShareHealth.PAUSED, screenShareHealth(fresh.copy(paused = true)).health)
        assertTrue(screenShareHealth(fresh.copy(paused = true)).canRetry)
    }
    @Test fun authorizationLossAndExplicitScreenPauseCannotBecomeGreen() {
        assertEquals(ScreenShareHealth.AUTHORIZATION_LOST, screenShareHealth(fresh.copy(authorized = false, active = false)).health)
        assertFalse(screenShareHealth(fresh.copy(authorized = false)).canRetry)
        assertEquals(ScreenShareHealth.PAUSED, screenShareHealth(fresh.copy(screenEnabled = false)).health)
    }
    @Test fun helperAndCaptureFailuresAreLabelledSeparately() {
        assertTrue(screenShareHealth(fresh.copy(helperFailed = true)).text.startsWith("画面就绪"))
        assertFalse(screenShareHealth(fresh.copy(captureFailed = true)).text.startsWith("画面就绪"))
        assertTrue(screenShareHealth(fresh.copy(captureFailed = true, retryScheduled = true)).text.contains("重试"))
        assertEquals(ScreenShareHealth.ERROR, screenShareHealth(fresh.copy(chatFailed = true)).health)
        assertEquals(ScreenShareHealth.BUSY, screenShareHealth(fresh.copy(chatFailed = true, generationActive = true)).health)
    }
    @Test fun retryIsBoundedAndNewManualCycleStartsClean() {
        var retry = ScreenShareRetry()
        for (delay in listOf(3_000L, 5_000L, 15_000L)) {
            retry = retry.failed(100L)
            assertEquals(100L + delay, retry.nextAt)
            assertFalse(retry.permits(100L + delay - 1)); assertTrue(retry.permits(100L + delay))
        }
        retry = retry.failed(100L)
        assertTrue(retry.exhausted); assertFalse(retry.permits(Long.MAX_VALUE))
        assertEquals(retry, retry.failed(200L))
        assertTrue(ScreenShareRetry().permits(0L))
    }
    @Test fun onlyNewRealAssistantBodyIsVisibleNotEventsOrAuxiliarySummaries() {
        val baseline = setOf("old", "old-other-branch")
        fun reply(id: String, text: String = id, assistant: Boolean = true) = ScreenShareReply(id, text, assistant)
        val messages = listOf(reply("old"), reply("new", "真正回复"), reply("event").copy(event = true),
            reply("synthetic").copy(synthetic = true), reply("human", assistant = false))
        assertEquals("真正回复", latestScreenShareReply(messages, baseline, null)?.text)
        assertNull(latestScreenShareReply(listOf(reply("old-other-branch")), baseline, null))
    }
    @Test fun currentStreamingBlankClearsOldReplyAndExistingStreamMayAdvance() {
        val baseline = setOf("stream")
        assertNull(latestScreenShareReply(listOf(ScreenShareReply("stream", "partial", true)), baseline, null))
        assertEquals("partial", latestScreenShareReply(listOf(ScreenShareReply("stream", "partial", true)), baseline, "stream")?.text)
        assertEquals("", latestScreenShareReply(listOf(ScreenShareReply("stream", "partial", true), ScreenShareReply("next", "", true)), baseline, "stream")?.text)
    }
}
