package me.rerere.rikkahub.data.recovery

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.utils.EmergencyProcessGate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Synthetic private directories only; policy must never repair, acknowledge or remove data. */
class NoRescueStartupPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "orbis-emergency")
    private fun journal(status: String, schema: Int = 1): File {
        val transaction = File(root, "transactions/$status").apply { mkdirs() }
        return File(transaction, EmergencyRestore.JOURNAL).apply {
            writeText(Json.encodeToString(EmergencyRestore.Journal(schema, status,
                emptyMap(), emptyMap(), emptyList(), 0)))
        }
    }

    @Test fun absentStateIsAllowedWithoutCreatingFiles() {
        requireSafeNoRescueStartup(root)
        assertFalse(root.exists())
    }

    @Test fun oldRecoveryRequestIsIgnoredAndPreservedWhileStandardBuildStillBlocks() {
        val gate = EmergencyProcessGate(File(root, "control"))
        assertTrue(gate.requestRecovery())
        val request = File(root, "control/recovery-requested")
        val original = request.readBytes()
        gate.tryAcquireNoRescueBusinessLease { requireSafeNoRescueStartup(root) }.use {
            assertNotNull(it)
            assertNull(gate.tryAcquireRecoveryLease())
        }
        assertArrayEquals(original, request.readBytes())
        assertNull(gate.tryAcquireBusinessLease())
        assertEquals(EmergencyLauncherState.READY, inspectNoRescueLauncher(gate) { requireSafeNoRescueStartup(root) })
        assertEquals(EmergencyLauncherState.RECOVERY_REQUESTED, inspectEmergencyLauncher(gate) { false })
    }

    @Test fun liveRecoveryLeaseCannotBeBypassedAndValidationRunsUnderLease() {
        val gate = EmergencyProcessGate(File(root, "control"))
        gate.requestRecovery()
        gate.tryAcquireRecoveryLease().use {
            assertNotNull(it)
            assertNull(gate.tryAcquireNoRescueBusinessLease { fail("Must not inspect during restore") })
            assertEquals(EmergencyLauncherState.BUSINESS_BUSY,
                inspectNoRescueLauncher(gate) { fail("Must not inspect during restore") })
        }
        gate.tryAcquireNoRescueBusinessLease { assertNull(gate.tryAcquireRecoveryLease()) }.use { assertNotNull(it) }
    }

    @Test fun rejectedStateClosesLeaseAndNeverClearsEvidence() {
        val record = journal("INSTALLING")
        val original = record.readBytes()
        val gate = EmergencyProcessGate(File(root, "control"))
        gate.requestRecovery()
        assertThrows(IllegalStateException::class.java) {
            gate.tryAcquireNoRescueBusinessLease { requireSafeNoRescueStartup(root) }
        }
        assertEquals(EmergencyLauncherState.LEGACY_RESTORE_BLOCKED,
            inspectNoRescueLauncher(gate) { requireSafeNoRescueStartup(root) })
        gate.tryAcquireRecoveryLease().use { assertNotNull(it) }
        assertArrayEquals(original, record.readBytes())
    }

    @Test fun committedAndRolledBackRestoresAreAllowedWithoutRemovingOriginals() {
        for (status in listOf("COMMITTED", "ROLLED_BACK")) {
            val record = journal(status)
            val original = File(record.parentFile, "originals/retained.txt").apply {
                parentFile.mkdirs(); writeText("preserve")
            }
            requireSafeNoRescueStartup(root)
            assertEquals("preserve", original.readText())
        }
    }

    @Test fun everyUnfinishedOrUnknownStateBlocksWithoutWriting() {
        for (status in listOf("PREPARED", "INSTALLING", "ROLLING_BACK", "UNKNOWN")) {
            val record = journal(status)
            val before = record.readBytes()
            assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
            assertArrayEquals(before, record.readBytes())
            assertTrue(record.delete()) // only our synthetic fixture, not policy cleanup
        }
    }

    @Test fun unknownSchemaAndCorruptJournalAreNotTreatedAsCompleted() {
        val record = journal("COMMITTED", schema = 2)
        assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
        record.writeText("broken fixture")
        assertThrows(Exception::class.java) { requireSafeNoRescueStartup(root) }
        assertEquals("broken fixture", record.readText())
    }

    @Test fun unfinishedPermissionRestoreAndTemporaryJournalBlock() {
        val pending = File(root, "mount-permissions/pending.json").apply { parentFile.mkdirs(); writeText("fixture") }
        assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
        assertTrue(pending.delete())
        val record = journal("COMMITTED")
        val temp = File(record.parentFile, EmergencyRestore.JOURNAL + ".tmp").apply { writeText("fixture") }
        assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
        assertEquals("fixture", temp.readText())
    }

    @Test fun preparationWithoutJournalIsSafeOnlyIfNoOriginalsWereMoved() {
        val transaction = File(root, "transactions/preparation").apply { mkdirs() }
        File(transaction, "raw").mkdir()
        requireSafeNoRescueStartup(root)
        File(transaction, "originals").mkdir()
        assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
    }

    @Test fun malformedDirectoryIsNotTreatedAsAbsent() {
        root.writeText("fixture")
        assertThrows(IllegalStateException::class.java) { requireSafeNoRescueStartup(root) }
        assertEquals("fixture", root.readText())
    }
}
