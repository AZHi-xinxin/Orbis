package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRestPolicyTest {
    private val appA = "com.example.reader"
    private val appB = "com.example.video"

    @Test
    fun `zero duration does not produce a snapshot`() {
        val tracker = AppRestTracker()
        tracker.observeForeground(appA, 0L)
        assertNull(tracker.snapshot(0L, 1_000L))
    }

    @Test
    fun `less than sixty confirmed minutes does not produce a snapshot`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 59)
        assertNull(tracker.snapshot(59 * MINUTE, 1_000L))
    }

    @Test
    fun `sixty confirmed minutes produces a snapshot`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        val snapshot = tracker.snapshot(60 * MINUTE, 123_456L)
        requireNotNull(snapshot)
        assertEquals(appA, snapshot.packageName)
        assertEquals(60, snapshot.durationMinutes)
        assertEquals(123_456L, snapshot.capturedAtMs)
        assertEquals(60, snapshot.thresholdMinutes)
    }

    @Test
    fun `requested threshold below sixty is clamped to sixty`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 30)
        assertNull(tracker.snapshot(30 * MINUTE, 1_000L, thresholdMinutes = 30))
        confirmFrom(tracker, appA, 30 * MINUTE, 60 * MINUTE)
        assertEquals(60, tracker.snapshot(60 * MINUTE, 2_000L, thresholdMinutes = 30)?.thresholdMinutes)
    }

    @Test
    fun `switching valid apps resets continuous duration`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 59)
        tracker.observeForeground(appB, 59 * MINUTE + SAMPLE)
        confirmFrom(tracker, appB, 59 * MINUTE + SAMPLE, 60 * MINUTE + SAMPLE)
        assertNull(tracker.snapshot(60 * MINUTE + SAMPLE, 2_000L))
    }

    @Test
    fun `short pause resumes same app without counting paused time`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 59)
        val pauseAt = 59 * MINUTE
        tracker.pause(pauseAt)
        tracker.observeForeground(appA, pauseAt + 10_000L)
        confirmFrom(tracker, appA, pauseAt + 10_000L, pauseAt + 10_000L + MINUTE)
        val snapshot = tracker.snapshot(pauseAt + 10_000L + MINUTE, 3_000L)
        requireNotNull(snapshot)
        assertEquals(60, snapshot.durationMinutes)
    }

    @Test
    fun `long pause resets even when returning to same app`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 59)
        val pauseAt = 59 * MINUTE
        tracker.pause(pauseAt)
        tracker.observeForeground(appA, pauseAt + 15_001L)
        confirmFrom(tracker, appA, pauseAt + 15_001L, pauseAt + 15_001L + MINUTE)
        assertNull(tracker.snapshot(pauseAt + 15_001L + MINUTE, 4_000L))
    }

    @Test
    fun `null foreground pauses rather than extrapolating`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 59)
        val pauseAt = 59 * MINUTE
        tracker.observeForeground(null, pauseAt)
        assertNull(tracker.snapshot(pauseAt, 5_000L))
        tracker.observeForeground(appA, pauseAt + 5_000L)
        assertNull(tracker.snapshot(pauseAt + 5_000L, 5_001L))
    }

    @Test
    fun `transient system package pauses and does not become tracked app`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        tracker.observeForeground("com.android.systemui", 60 * MINUTE)
        assertNull(tracker.snapshot(60 * MINUTE, 6_000L))
        tracker.observeForeground(appA, 60 * MINUTE + 5_000L)
        assertEquals(appA, tracker.snapshot(60 * MINUTE + 5_000L, 6_001L)?.packageName)
    }

    @Test
    fun `launcher and exempt chat packages reset the session`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        tracker.observeForeground("com.miui.home", 60 * MINUTE)
        assertNull(tracker.snapshot(60 * MINUTE, 7_000L))
        confirmFor(tracker, appA, 60)
        tracker.observeForeground("me.rerere.rikkahub", 60 * MINUTE)
        assertNull(tracker.snapshot(60 * MINUTE, 7_001L))
    }

    @Test
    fun `rikka and loverconnect are exempt without inspecting conversation content`() {
        assertTrue(AppRestPolicy.isChatPackage("me.rerere.rikkahub"))
        assertTrue(AppRestPolicy.isChatPackage("com.lover.connect"))
        assertFalse(AppRestPolicy.isChatPackage("com.tencent.mm"))
    }

    @Test
    fun `exclusion classification is explicit and not a broad system flag`() {
        assertTrue(AppRestPolicy.isExcludedPackage("com.android.systemui"))
        assertTrue(AppRestPolicy.isExcludedPackage("com.google.android.permissioncontroller"))
        assertTrue(AppRestPolicy.isExcludedPackage("com.google.android.inputmethod.latin"))
        assertTrue(AppRestPolicy.isExcludedPackage("com.oppo.launcher"))
        assertFalse(AppRestPolicy.isExcludedPackage("com.android.chrome"))
    }

    @Test
    fun `confirmation gap above fifteen seconds cannot accumulate`() {
        val tracker = AppRestTracker()
        tracker.observeForeground(appA, 0L)
        tracker.observeForeground(appA, 15_001L)
        assertNull(tracker.snapshot(60 * MINUTE, 8_000L))
        assertFalse(tracker.freshCheck(30_002L))
    }

    @Test
    fun `fresh check only extends a currently active confirmed session`() {
        val tracker = AppRestTracker()
        tracker.observeForeground(appA, 0L)
        assertTrue(tracker.freshCheck(5_000L))
        tracker.pause(6_000L)
        assertFalse(tracker.freshCheck(7_000L))
    }

    @Test
    fun `snapshot becomes stale after one hundred twenty seconds`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        val snapshot = requireNotNull(tracker.snapshot(60 * MINUTE, 9_000L))
        assertTrue(tracker.isCurrent(snapshot, 60 * MINUTE + 120_000L))
        assertFalse(tracker.isCurrent(snapshot, 60 * MINUTE + 120_001L))
    }

    @Test
    fun `future or rolled back elapsed clocks are rejected`() {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        val snapshot = requireNotNull(tracker.snapshot(60 * MINUTE, 10_000L))
        assertFalse(tracker.isCurrent(snapshot, 60 * MINUTE - 1L))
        assertNull(tracker.snapshot(60 * MINUTE - 1L, 10_001L))
        tracker.observeForeground(appA, 1L)
        assertNull(tracker.snapshot(1L, 10_002L))
    }

    @Test
    fun `switch pause and reset each invalidate an existing snapshot`() {
        val switched = trackerAndSnapshot()
        switched.first.observeForeground(appB, 60 * MINUTE + SAMPLE)
        assertFalse(switched.first.isCurrent(switched.second, 60 * MINUTE + SAMPLE))

        val paused = trackerAndSnapshot()
        paused.first.pause(60 * MINUTE)
        assertFalse(paused.first.isCurrent(paused.second, 60 * MINUTE))

        val reset = trackerAndSnapshot()
        reset.first.reset()
        assertFalse(reset.first.isCurrent(reset.second, 60 * MINUTE))
    }

    @Test
    fun `successful http response is delivered`() {
        assertEquals(SentinelDelivery.DELIVERED, DeliveryPolicy.fromHttpStatus(200))
        assertEquals(SentinelDelivery.DELIVERED, DeliveryPolicy.fromHttpStatus(299))
    }

    @Test
    fun `cooldown and client errors remain distinct`() {
        assertEquals(SentinelDelivery.SUPPRESSED, DeliveryPolicy.fromHttpStatus(429))
        assertEquals(SentinelDelivery.REJECTED, DeliveryPolicy.fromHttpStatus(400))
        assertEquals(SentinelDelivery.REJECTED, DeliveryPolicy.fromHttpStatus(499))
    }

    @Test
    fun `server and transport uncertainty never use local fallback`() {
        assertEquals(SentinelDelivery.UNCERTAIN, DeliveryPolicy.fromHttpStatus(503))
        assertEquals(SentinelDelivery.UNCERTAIN, DeliveryPolicy.fromHttpStatus(null))
        assertEquals(SentinelDelivery.UNCERTAIN, DeliveryPolicy.fromException())
        assertFalse(DeliveryPolicy.shouldUseLocalFallback(SentinelDelivery.UNCERTAIN))
        assertFalse(DeliveryPolicy.shouldUseLocalFallback(SentinelDelivery.SUPPRESSED))
        assertFalse(DeliveryPolicy.shouldUseLocalFallback(SentinelDelivery.REJECTED))
    }

    @Test
    fun `only disabled or unconfigured preflight is unavailable and may fall back`() {
        assertEquals(SentinelDelivery.UNAVAILABLE, DeliveryPolicy.preflight(false, true))
        assertEquals(SentinelDelivery.UNAVAILABLE, DeliveryPolicy.preflight(true, false))
        assertNull(DeliveryPolicy.preflight(true, true))
        assertTrue(DeliveryPolicy.shouldUseLocalFallback(SentinelDelivery.UNAVAILABLE))
    }

    private fun trackerAndSnapshot(): Pair<AppRestTracker, AppRestSnapshot> {
        val tracker = AppRestTracker()
        confirmFor(tracker, appA, 60)
        return tracker to requireNotNull(tracker.snapshot(60 * MINUTE, 11_000L))
    }

    private fun confirmFor(tracker: AppRestTracker, packageName: String, minutes: Int) {
        tracker.observeForeground(packageName, 0L)
        confirmFrom(tracker, packageName, 0L, minutes * MINUTE)
    }

    private fun confirmFrom(
        tracker: AppRestTracker,
        packageName: String,
        fromElapsedMs: Long,
        toElapsedMs: Long,
    ) {
        var elapsed = fromElapsedMs + SAMPLE
        while (elapsed <= toElapsedMs) {
            tracker.observeForeground(packageName, elapsed)
            elapsed += SAMPLE
        }
    }

    companion object {
        private const val SAMPLE = 5_000L
        private const val MINUTE = 60_000L
    }
}
