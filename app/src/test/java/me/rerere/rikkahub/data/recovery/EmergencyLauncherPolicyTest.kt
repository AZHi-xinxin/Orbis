package me.rerere.rikkahub.data.recovery

import me.rerere.rikkahub.utils.EmergencyProcessGate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EmergencyLauncherPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun gate() = EmergencyProcessGate(File(temporary.root, "control"))

    @Test fun freshStateAllowsNormalEntryAndReleasesInspectionLease() {
        val gate = gate()
        var reads = 0
        assertEquals(EmergencyLauncherState.READY, inspectEmergencyLauncher(gate) { reads++; false })
        assertEquals(1, reads)
        assertFalse(gate.isRecoveryRequested())
        gate.tryAcquireBusinessLease().use { assertNotNull(it) }
    }

    @Test fun priorCrashIsNotAcknowledgedOrAutomaticallyChanged() {
        val gate = gate()
        val previousCrash = true
        assertEquals(EmergencyLauncherState.PREVIOUS_CRASH, inspectEmergencyLauncher(gate) { previousCrash })
        assertTrue(previousCrash)
        assertFalse(gate.isRecoveryRequested())
        gate.tryAcquireBusinessLease().use { assertNotNull(it) }
    }

    @Test fun protectionIsNeitherClearedNorUsedToReadPreferences() {
        val gate = gate()
        gate.requestRecovery()
        repeat(3) {
            assertEquals(EmergencyLauncherState.RECOVERY_REQUESTED,
                inspectEmergencyLauncher(gate) { fail("Must not read crash preferences behind fence"); false })
        }
        assertTrue(gate.isRecoveryRequested())
        assertNull(gate.tryAcquireBusinessLease())
        gate.tryAcquireRecoveryLease().use { assertNotNull(it) }
    }

    @Test fun malformedProtectionRemainsUntouched() {
        val directory = temporary.newFolder("control", "recovery-requested")
        File(directory, "evidence").writeText("synthetic-preserve")
        assertEquals(EmergencyLauncherState.RECOVERY_REQUESTED,
            inspectEmergencyLauncher(gate()) { fail("Must not read preferences"); false })
        assertEquals("synthetic-preserve", File(directory, "evidence").readText())
    }

    @Test fun failedCrashReadNeverOpensNormalEntryAndClosesLease() {
        val gate = gate()
        assertEquals(EmergencyLauncherState.CHECK_FAILED,
            inspectEmergencyLauncher(gate) { throw IOException("synthetic private details") })
        assertFalse(gate.isRecoveryRequested())
        gate.tryAcquireBusinessLease().use { assertNotNull(it) }
    }

    @Test fun inaccessibleControlFailsClosedWithoutReadingPreferences() {
        temporary.newFile("control")
        assertEquals(EmergencyLauncherState.CHECK_FAILED,
            inspectEmergencyLauncher(gate()) { fail("Must not read preferences"); false })
    }

    @Test fun leaseCollisionDoesNotInspectPreferencesOrBypassTheGate() {
        val gate = gate()
        gate.tryAcquireBusinessLease().use {
            assertNotNull(it)
            assertEquals(EmergencyLauncherState.BUSINESS_BUSY,
                inspectEmergencyLauncher(gate) { fail("Must not read preferences"); false })
        }
        assertFalse(gate.isRecoveryRequested())
    }

    @Test fun crashReadOccursWhileTheInspectionOwnsTheLease() {
        val gate = gate()
        assertEquals(EmergencyLauncherState.READY, inspectEmergencyLauncher(gate) {
            assertNull(gate.tryAcquireBusinessLease())
            false
        })
        gate.tryAcquireBusinessLease().use { assertNotNull(it) }
    }

    @Test fun onlyExactOwnedLightweightNamesBypassBusinessInitialization() {
        for (pkg in listOf("org.orbis.agent", "org.orbis.agent.dev")) {
            assertTrue(isEmergencyLightweightProcessName("$pkg:launcher", pkg))
            assertTrue(isEmergencyLightweightProcessName("$pkg:recovery", pkg))
            assertTrue(isEmergencyLauncherProcessName("$pkg:launcher", pkg))
            for (name in listOf(pkg, "$pkg:launcher-worker", "$pkg:other", "foreign:launcher", "foreign:recovery")) {
                assertFalse(isEmergencyLightweightProcessName(name, pkg))
                assertFalse(isEmergencyLauncherProcessName(name, pkg))
            }
            assertFalse(isEmergencyLauncherProcessName("$pkg:recovery", pkg))
        }
    }

    @Test fun existingRecoveryTaskIsResumedWithoutRestartingItsActivity() {
        var resumes = 0
        var opens = 0
        assertTrue(openOrResumeEmergencyTask({ { resumes += 1 } }, { opens++ }))
        assertEquals(1, resumes)
        assertEquals(0, opens)
    }

    @Test fun newRecoveryIsOpenedOnlyAfterAnEmptySuccessfulTaskLookup() {
        val steps = mutableListOf<String>()
        assertTrue(openOrResumeEmergencyTask({ steps += "lookup"; null }, { steps += "open" }))
        assertEquals(listOf("lookup", "open"), steps)
    }

    @Test fun failedTaskLookupCannotFallBackToRestartingRecovery() {
        var opens = 0
        assertFalse(openOrResumeEmergencyTask({ throw SecurityException("synthetic") }, { opens++ }))
        assertEquals(0, opens)
    }

    @Test fun failedTaskResumeCannotFallBackToRestartingRecovery() {
        var opens = 0
        assertFalse(openOrResumeEmergencyTask({ { throw IllegalStateException("synthetic") } }, { opens++ }))
        assertEquals(0, opens)
    }

    @Test fun unavailableRecoveryActivityReturnsFailureWithoutPretendingItOpened() {
        assertFalse(openOrResumeEmergencyTask({ null }, { throw IllegalStateException("synthetic") }))
    }

    @Test fun processApiNameIsPreferredWithoutReadingProc() {
        assertEquals("org.orbis.agent:launcher", resolveEmergencyProcessName(
            { "org.orbis.agent:launcher" }, { fail("No fallback needed"); "" }))
    }

    @Test fun absentOrFailingProcessApiUsesOnlyAnActualFallbackName() {
        assertEquals("org.orbis.agent:recovery", resolveEmergencyProcessName({ null }, { "org.orbis.agent:recovery" }))
        assertEquals("org.orbis.agent", resolveEmergencyProcessName({ "" }, { "org.orbis.agent" }))
        assertEquals("org.orbis.agent", resolveEmergencyProcessName({ throw IOException("synthetic") }, { "org.orbis.agent" }))
    }

    @Test fun unidentifiedProcessNeverDefaultsToLightweight() {
        assertThrows(IllegalStateException::class.java) { resolveEmergencyProcessName({ null }, { "" }) }
        assertThrows(IOException::class.java) { resolveEmergencyProcessName({ null }, { throw IOException("synthetic") }) }
    }
}
