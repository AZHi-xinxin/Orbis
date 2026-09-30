package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTrackingRecoveryPolicyTest {
    @Test
    fun `created service restores an enabled unpaused loop`() {
        assertTrue(shouldStart(enabled = true, paused = false, loopStarted = false))
    }

    @Test
    fun `start delivered after create and repeated foreground visits keep the live loop`() {
        assertTrue(shouldStart(enabled = true, paused = false, loopStarted = false))
        repeat(3) {
            assertFalse(shouldStart(enabled = true, paused = false, loopStarted = true))
        }
    }

    @Test
    fun `recovery never enables disabled or paused tracking even for a reload`() {
        for (running in listOf(false, true)) {
            for (reload in listOf(false, true)) {
                assertFalse(shouldStart(false, false, running, reload))
                assertFalse(shouldStart(false, true, running, reload))
                assertFalse(shouldStart(true, true, running, reload))
            }
        }
    }

    @Test
    fun `explicit configuration edit still reloads a running loop`() {
        assertTrue(shouldStart(true, false, true, reloadConfiguration = true))
    }

    @Test
    fun `explicit resume can restart the stopped loop after persisted pause is cleared`() {
        assertFalse(shouldStart(true, true, false))
        assertTrue(shouldStart(true, false, false))
    }

    private fun shouldStart(
        enabled: Boolean,
        paused: Boolean,
        loopStarted: Boolean,
        reloadConfiguration: Boolean = false,
    ) = LocationTrackingRecoveryPolicy.shouldStart(
        enabled, paused, loopStarted, reloadConfiguration,
    )
}
