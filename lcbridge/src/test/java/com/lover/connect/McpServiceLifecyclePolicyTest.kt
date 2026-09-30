package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class McpServiceLifecyclePolicyTest {
    @Test
    fun packageReplacementAndBootAreRestoreTriggers() {
        assertTrue(
            McpServiceLifecyclePolicy.handlesRestoreBroadcast(
                McpServiceLifecyclePolicy.ACTION_BOOT_COMPLETED,
            ),
        )
        assertTrue(
            McpServiceLifecyclePolicy.handlesRestoreBroadcast(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
            ),
        )
    }

    @Test
    fun unrelatedOrMissingBroadcastIsIgnored() {
        assertFalse(McpServiceLifecyclePolicy.handlesRestoreBroadcast(null))
        assertFalse(McpServiceLifecyclePolicy.handlesRestoreBroadcast("android.intent.action.TIME_SET"))
    }

    @Test
    fun enabledStoppedServiceRequestsStart() {
        assertTrue(McpServiceLifecyclePolicy.shouldRequestStart(enabled = true))
    }

    @Test
    fun disabledServiceDoesNotRequestStart() {
        assertFalse(McpServiceLifecyclePolicy.shouldRequestStart(enabled = false))
    }

    @Test
    fun enabledServiceAlwaysReceivesAnIdempotentStartRequest() {
        assertTrue(McpServiceLifecyclePolicy.shouldRequestStart(enabled = true))
    }

    @Test
    fun onlyFirstLegacyPackageReplacementMigratesToEnabled() {
        assertTrue(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = true,
            ),
        )
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = true,
                hasLegacyUseEvidence = true,
            ),
        )
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_BOOT_COMPLETED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = true,
            ),
        )
    }

    @Test
    fun neverOpenedCleanInstallIsNotMigratedOnItsFirstReplacement() {
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = false,
            ),
        )
    }

    @Test
    fun cleanInstallDefaultsToStoppedUntilTheUserStartsIt() {
        assertFalse(McpServiceLifecyclePolicy.DEFAULT_ENABLED)
    }

    @Test fun createdEnabledShellCanInitializeBeforeStartCommand() {
        assertTrue(McpServiceLifecyclePolicy.shouldInitializeRuntime(true, false, false))
    }

    @Test fun startCommandAfterCreateDoesNotInitializeTwice() {
        assertFalse(McpServiceLifecyclePolicy.shouldInitializeRuntime(true, true, false))
        assertFalse(McpServiceLifecyclePolicy.shouldInitializeRuntime(true, false, true))
    }

    @Test fun disabledRuntimeIsNeverInitialized() {
        for (initialized in listOf(false, true)) for (initializing in listOf(false, true)) {
            assertFalse(McpServiceLifecyclePolicy.shouldInitializeRuntime(false, initialized, initializing))
        }
    }

    @Test fun recoveryDoesNotStartDisabledOrAlreadyReadyService() {
        val gate = McpRecoveryGate()
        assertFalse(gate.tryAcquire(0, enabled = false, ready = false))
        assertFalse(gate.tryAcquire(0, enabled = true, ready = true))
        assertTrue(gate.tryAcquire(0, enabled = true, ready = false))
    }

    @Test fun concurrentRecoveryCallsOnlyAcquireOneLease() {
        val gate = McpRecoveryGate()
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = List(16) {
            Thread {
                start.await()
                if (gate.tryAcquire(100, true, false)) accepted.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(2_000) }
        assertEquals(1, accepted.get())
    }

    @Test fun pendingRecoveryIsBoundedAndCanBeRetriedAfterItsLease() {
        val gate = McpRecoveryGate(cooldownMs = 5_000, pendingLeaseMs = 10_000)
        assertTrue(gate.tryAcquire(0, true, false))
        assertFalse(gate.tryAcquire(5_000, true, false))
        assertFalse(gate.tryAcquire(9_999, true, false))
        assertTrue(gate.tryAcquire(10_000, true, false))
    }

    @Test fun failedOrCompletedRecoveryRetainsShortCooldown() {
        val gate = McpRecoveryGate()
        assertTrue(gate.tryAcquire(0, true, false))
        gate.complete()
        assertFalse(gate.tryAcquire(4_999, true, false))
        assertTrue(gate.tryAcquire(5_000, true, false))
    }

    @Test fun explicitDisableResetsRecoveryLeaseWithoutEnablingAnything() {
        val gate = McpRecoveryGate()
        assertTrue(gate.tryAcquire(0, true, false))
        gate.reset()
        assertFalse(gate.tryAcquire(1, false, false))
        assertTrue(gate.tryAcquire(1, true, false))
    }
}
