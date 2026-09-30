package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrbisStartupPolicyTest {
    private fun contentReady(
        settingsReady: Boolean = true,
        loadSettled: Boolean = true,
        loadFailed: Boolean = false,
        snapshotsCurrent: Boolean = true,
        assistantMatches: Boolean = true,
        backgroundSettled: Boolean = true,
        laidOut: Boolean = true,
    ) = orbisStartupContentReady(settingsReady, loadSettled, loadFailed, snapshotsCurrent,
        assistantMatches, backgroundSettled, laidOut)

    @Test fun gateCanOnlyBeClaimedOnceAcrossRepeatedActivityEntries() {
        val gate = OrbisStartupOnceGate()
        assertTrue(gate.claim())
        repeat(100) { assertFalse(gate.claim()) }
    }

    @Test fun concurrentClaimsHaveExactlyOneWinner() {
        val gate = OrbisStartupOnceGate()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                pool.execute {
                    try {
                        ready.countDown()
                        start.await()
                        repeat(64) { if (gate.claim()) winners.incrementAndGet() }
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue("Synthetic workers did not start", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue("Synthetic claims did not complete", done.await(5, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
            assertFalse(gate.claim())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun independentGateModelsANewProcessWithoutResettingThePreviousGate() {
        val previousProcess = OrbisStartupOnceGate()
        assertTrue(previousProcess.claim())
        val newProcess = OrbisStartupOnceGate()
        assertTrue(newProcess.claim())
        assertFalse(previousProcess.claim())
        assertFalse(newProcess.claim())
    }

    @Test fun onlyDebugNormalFirstActivityMayShowTheCover() {
        for (debug in listOf(false, true)) for (normal in listOf(false, true)) {
            for (first in listOf(false, true)) {
                assertEquals(debug && normal && first, shouldShowOrbisStartup(debug, normal, first))
            }
        }
    }

    @Test fun specialEntryConsumesTheGateWithoutShowingOrDeferringTheCover() {
        val gate = OrbisStartupOnceGate()
        val specialFirstActivity = gate.claim()
        assertTrue(specialFirstActivity)
        assertFalse(shouldShowOrbisStartup(isDebug = true, normalLaunch = false, firstActivity = specialFirstActivity))
        val subsequentNormalActivity = gate.claim()
        assertFalse(shouldShowOrbisStartup(isDebug = true, normalLaunch = true, firstActivity = subsequentNormalActivity))
    }

    @Test fun bothReadyExitsImmediatelyWithoutAFixedMinimumAnimationDuration() {
        for (elapsed in listOf(Long.MIN_VALUE, -1L, 0L, 1L, ORBIS_STARTUP_SLOW_MS, ORBIS_STARTUP_TIMEOUT_MS - 1)) {
            assertEquals(OrbisStartupExit.READY, orbisStartupExit(true, true, elapsed, leave = false))
        }
    }

    @Test fun readinessRequiresBothInputsBeforeDeadline() {
        for (settings in listOf(false, true)) for (chat in listOf(false, true)) {
            val expected = if (settings && chat) OrbisStartupExit.READY else null
            for (elapsed in listOf(0L, ORBIS_STARTUP_TIMEOUT_MS - 1)) {
                assertEquals(expected, orbisStartupExit(settings, chat, elapsed, leave = false))
            }
        }
    }

    @Test fun negativeElapsedTimeIsTreatedAsZero() {
        for (elapsed in listOf(Long.MIN_VALUE, -7000L, -1L)) {
            assertNull(orbisStartupExit(false, false, elapsed, leave = false))
            assertEquals(orbisStartupExit(true, true, 0L, leave = false),
                orbisStartupExit(true, true, elapsed, leave = false))
            assertFalse(orbisStartupSlow(elapsed))
        }
    }

    @Test fun deadlineIsIndependentOfAnimationAndWinsOverReadiness() {
        assertEquals(7000L, ORBIS_STARTUP_TIMEOUT_MS)
        assertNull(orbisStartupExit(false, false, ORBIS_STARTUP_TIMEOUT_MS - 1, leave = false))
        for (settings in listOf(false, true)) for (chat in listOf(false, true)) {
            for (elapsed in listOf(ORBIS_STARTUP_TIMEOUT_MS, ORBIS_STARTUP_TIMEOUT_MS + 1, Long.MAX_VALUE)) {
                assertEquals(OrbisStartupExit.TIMEOUT, orbisStartupExit(settings, chat, elapsed, leave = false))
            }
        }
    }

    @Test fun leaveHasPriorityOverReadinessAndTimeout() {
        for (settings in listOf(false, true)) for (chat in listOf(false, true)) {
            for (elapsed in listOf(Long.MIN_VALUE, 0L, ORBIS_STARTUP_TIMEOUT_MS - 1,
                ORBIS_STARTUP_TIMEOUT_MS, Long.MAX_VALUE)) {
                assertEquals(OrbisStartupExit.LEAVE, orbisStartupExit(settings, chat, elapsed, leave = true))
            }
        }
    }

    @Test fun slowThresholdHasAnExactBoundaryAndDoesNotForceAnExit() {
        assertEquals(2500L, ORBIS_STARTUP_SLOW_MS)
        assertFalse(orbisStartupSlow(0L))
        assertFalse(orbisStartupSlow(ORBIS_STARTUP_SLOW_MS - 1))
        assertTrue(orbisStartupSlow(ORBIS_STARTUP_SLOW_MS))
        assertTrue(orbisStartupSlow(ORBIS_STARTUP_SLOW_MS + 1))
        assertTrue(orbisStartupSlow(Long.MAX_VALUE))
        assertNull(orbisStartupExit(false, false, ORBIS_STARTUP_SLOW_MS, leave = false))
    }

    @Test fun currentSettledLaidOutContentExitsWithoutAnyMinimumDelay() {
        assertTrue(contentReady())
        assertEquals(OrbisStartupExit.READY, orbisStartupExit(true, contentReady(), 0L, leave = false))
    }

    @Test fun dummySettingsPlaceholderLoadStaleCollectorsAndMissingLayoutAreNotReady() {
        assertFalse(contentReady(settingsReady = false))
        assertFalse(contentReady(loadSettled = false))
        assertFalse(contentReady(snapshotsCurrent = false))
        assertFalse(contentReady(laidOut = false))
    }

    @Test fun successfulLoadRequiresMatchingAssistantAndSettledBackground() {
        assertFalse(contentReady(assistantMatches = false))
        assertFalse(contentReady(backgroundSettled = false))
        assertFalse(contentReady(assistantMatches = false, backgroundSettled = false))
        assertTrue(contentReady(assistantMatches = true, backgroundSettled = true))
    }

    @Test fun backgroundImageFailureCanSettleOnTheExistingFallback() {
        // Success and a failed image with its fallback drawn both supply backgroundSettled=true.
        assertFalse(contentReady(backgroundSettled = false))
        assertTrue(contentReady(backgroundSettled = true))
        assertEquals(OrbisStartupExit.READY,
            orbisStartupExit(true, contentReady(backgroundSettled = true), 0L, leave = false))
    }

    @Test fun loadErrorBypassesOwnerAndBackgroundButNotActualSettingsLoadCollectorsOrLayout() {
        assertTrue(contentReady(loadFailed = true, assistantMatches = false, backgroundSettled = false))
        assertFalse(contentReady(settingsReady = false, loadFailed = true, assistantMatches = false, backgroundSettled = false))
        assertFalse(contentReady(loadSettled = false, loadFailed = true, assistantMatches = false, backgroundSettled = false))
        assertFalse(contentReady(snapshotsCurrent = false, loadFailed = true, assistantMatches = false, backgroundSettled = false))
        assertFalse(contentReady(laidOut = false, loadFailed = true, assistantMatches = false, backgroundSettled = false))
    }

    @Test fun everyContentReadinessCombinationObeysTheIndependentGuards() {
        for (mask in 0 until 128) {
            val settings = (mask and 1) != 0
            val settled = (mask and 2) != 0
            val failed = (mask and 4) != 0
            val current = (mask and 8) != 0
            val owner = (mask and 16) != 0
            val background = (mask and 32) != 0
            val layout = (mask and 64) != 0
            val expected = settings && settled && current && layout && (failed || (owner && background))
            assertEquals("readiness combination $mask", expected,
                orbisStartupContentReady(settings, settled, failed, current, owner, background, layout))
        }
    }
}
