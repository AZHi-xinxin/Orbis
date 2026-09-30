package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic observations only. No phone, service, screenshot, location, provider or actual wake. */
class CompanionNativeSentinelTest {
    private fun locationEvent() = CompanionHostEvent(
        CompanionHostEvents.LC_LOCATION, "synthetic-stable-id", "zone_exit_confirmed", null,
        "{\"event_id\":\"synthetic-stable-id\"}", 1_000L,
    )

    @Test fun unownedLocationKeepsLegacyBehavior() {
        var calls = 0
        val actual = deliverLocationEvent(locationEvent(), false,
            { calls++; LocationUploadDisposition.DELIVERED }, { CompanionHostEventResult.NOT_OWNED })
        assertEquals(LocationUploadDisposition.DELIVERED, actual)
        assertEquals(1, calls)
    }

    @Test fun allHostOutcomesPreventLegacyFallback() {
        CompanionHostEventResult.entries.forEach { outcome ->
            val actual = deliverLocationEvent(locationEvent(), true, { error("must not contact VPS") }, { outcome })
            val expected = when (outcome) {
                CompanionHostEventResult.ACCEPTED, CompanionHostEventResult.DUPLICATE -> LocationUploadDisposition.DELIVERED
                CompanionHostEventResult.REJECTED -> LocationUploadDisposition.REJECTED
                else -> LocationUploadDisposition.RETRY
            }
            assertEquals(expected, actual)
        }
    }

    @Test fun newHostBetweenPreflightAndDeliveryStillOwnsEvent() {
        assertEquals(LocationUploadDisposition.DELIVERED,
            deliverLocationEvent(locationEvent(), false, { error("no legacy") }, { CompanionHostEventResult.ACCEPTED }))
    }

    @Test fun uncertainHostRetryPreservesOriginalIdAndPayload() {
        val original = locationEvent()
        val seen = mutableListOf<CompanionHostEvent>()
        assertEquals(LocationUploadDisposition.RETRY, deliverLocationEvent(original, true,
            { error("no legacy") }, { seen += it; CompanionHostEventResult.UNKNOWN }))
        assertEquals(LocationUploadDisposition.DELIVERED, deliverLocationEvent(original, true,
            { error("no legacy") }, { seen += it; CompanionHostEventResult.DUPLICATE }))
        assertSame(seen[0], seen[1])
        assertEquals("synthetic-stable-id", seen[1].eventId)
    }

    @Test fun throwingHostLeavesPendingInsteadOfFallingBack() {
        assertEquals(LocationUploadDisposition.RETRY,
            deliverLocationEvent(locationEvent(), true, { error("no legacy") }, { error("sink lost response") }))
    }

    @Test fun screenContinuityDoesNotImportTimeBeforeMonitoringStarted() {
        val screen = CompanionScreenContinuity()
        assertEquals(0L, screen.observe(true, 50_000L))
        assertEquals(5_000L, screen.observe(true, 55_000L))
    }

    @Test fun screenOffOrLockBreaksContinuity() {
        val screen = CompanionScreenContinuity()
        screen.observe(true, 10_000L)
        assertEquals(5_000L, screen.observe(true, 15_000L))
        assertNull(screen.observe(false, 16_000L))
        assertEquals(0L, screen.observe(true, 17_000L))
    }

    @Test fun missedSamplesNeverCountSleepAsContinuousScreenUsage() {
        val screen = CompanionScreenContinuity()
        screen.observe(true, 10_000L)
        screen.observe(true, 15_000L)
        assertEquals(0L, screen.observe(true, 100_000L))
    }

    @Test fun elapsedClockRollbackAndExplicitResetRestartScreenEpisode() {
        val screen = CompanionScreenContinuity()
        screen.observe(true, 10_000L)
        screen.observe(true, 15_000L)
        assertEquals(0L, screen.observe(true, 1_000L))
        screen.reset()
        assertEquals(0L, screen.observe(true, 2_000L))
    }

    @Test fun usageFactsDoNotRequireLegacySixtyMinuteReminderThreshold() {
        val tracker = AppRestTracker()
        tracker.observeForeground("com.example.reader", 1_000L)
        repeat(60) { tracker.observeForeground("com.example.reader", 1_000L + (it + 1) * 5_000L) }
        val observation = tracker.observation(301_000L, 1_800_000L)!!
        assertEquals(300_000L, observation.continuousDurationMs)
        assertEquals(1_800_000L, observation.observedAtMs)
        assertNull(tracker.snapshot(301_000L, 1_800_000L))
    }

    @Test fun staleOrPausedUsageIsUnknownNotFabricatedDuration() {
        val tracker = AppRestTracker()
        tracker.observeForeground("com.example.reader", 1_000L)
        assertNull(tracker.observation(100_000L, 1_800_000L))
        tracker.observeForeground("com.example.reader", 101_000L)
        tracker.pause(102_000L)
        assertNull(tracker.observation(102_000L, 1_800_000L))
    }

    @Test fun allChatHostsAreExcludedFromNonChatUsageFacts() {
        for (host in listOf("me.rerere.rikkahub", "org.orbis.agent", "org.orbis.agent.dev", "com.lover.connect")) {
            val tracker = AppRestTracker()
            tracker.observeForeground("com.example.reader", 1_000L)
            tracker.observeForeground(host, 2_000L)
            assertNull(tracker.observation(2_000L, 1_800_000L))
        }
    }

    @Test fun observationDoesNotExtrapolatePastLastConfirmedSample() {
        val tracker = AppRestTracker()
        tracker.observeForeground("com.example.reader", 1_000L)
        tracker.observeForeground("com.example.reader", 6_000L)
        assertEquals(5_000L, tracker.observation(10_000L, 1_800_000L)!!.continuousDurationMs)
    }

    @Test fun failedScreenObservationHasNoFakeContentOrImage() {
        val failure = CompanionSentinelObservation(false, 5_000L, errorCode = "device_locked")
        assertFalse(failure.isAvailable)
        assertFalse(failure.shouldNotify)
        assertNull(failure.content)
        assertTrue(failure.images.isEmpty())
        assertEquals(5_000L, failure.observedAtMs)
    }

    @Test fun lockedScreenAndKnownChatResetRearmButMissingPermissionIsUnknown() {
        assertEquals(0L, companionNonChatUsageDuration(false, true, true, false, null))
        assertEquals(0L, companionNonChatUsageDuration(true, false, false, false, null))
        assertEquals(0L, companionNonChatUsageDuration(true, true, true, true, null))
        assertNull(companionNonChatUsageDuration(true, true, false, false, null))
        assertNull(companionNonChatUsageDuration(true, true, true, false, null))
        assertEquals(300_000L, companionNonChatUsageDuration(true, true, true, false, 300_000L))
    }

    @Test fun nonChatUsageSpansDifferentAppsWithoutChangingLegacySameAppDuration() {
        val aggregate = CompanionNonChatUsageTracker()
        val legacy = AppRestTracker()
        for (step in 0..72) {
            val pkg = if (step <= 36) "com.example.reader" else "com.example.player"
            aggregate.observeForeground(pkg, step * 5_000L)
            legacy.observeForeground(pkg, step * 5_000L)
        }
        assertEquals(360_000L, aggregate.observation(360_000L, 1_800_000L)!!.continuousDurationMs)
        assertEquals("com.example.player", aggregate.observation(360_000L, 1_800_000L)!!.packageName)
        assertEquals(175_000L, legacy.observation(360_000L, 1_800_000L)!!.continuousDurationMs)
    }

    @Test fun aggregateChatReturnEndsEpisodeAndUnknownBreakIsNotCounted() {
        val aggregate = CompanionNonChatUsageTracker()
        aggregate.observeForeground("com.example.reader", 1_000L)
        aggregate.observeForeground("com.example.reader", 6_000L)
        aggregate.observeForeground("org.orbis.agent.dev", 7_000L)
        assertNull(aggregate.observation(7_000L, 1_800_000L))
        aggregate.observeForeground("com.example.player", 8_000L)
        assertEquals(0L, aggregate.observation(8_000L, 1_800_000L)!!.continuousDurationMs)
        aggregate.pause(9_000L)
        aggregate.observeForeground("com.example.reader", 100_000L)
        assertEquals(0L, aggregate.observation(100_000L, 1_800_000L)!!.continuousDurationMs)
    }
}
