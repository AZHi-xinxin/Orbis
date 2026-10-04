package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EmergencyProcessGateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun gate(name: String = "control") = EmergencyProcessGate(File(temporary.root, name))

    @Test fun `normal business starts without a recovery request`() {
        val gate = gate()
        assertFalse(gate.isRecoveryRequested())
        gate.tryAcquireBusinessLease().use { lease ->
            assertNotNull(lease)
            assertTrue(lease!!.isValid)
            assertNull(gate.tryAcquireRecoveryLease())
        }
    }

    @Test fun `recovery is refused until existing business lease closes`() {
        val gate = gate()
        val business = gate.tryAcquireBusinessLease()!!
        try {
            assertTrue(gate.requestRecovery())
            assertNull(gate.tryAcquireRecoveryLease())
            assertNull(gate.tryAcquireBusinessLease())
        } finally {
            business.close()
        }
        gate.tryAcquireRecoveryLease().use { recovery -> assertNotNull(recovery) }
    }

    @Test fun `request survives gate reconstruction and recovery lease release`() {
        val first = gate()
        assertTrue(first.requestRecovery())
        first.tryAcquireRecoveryLease().use { assertNotNull(it) }
        val afterRestart = gate()
        assertTrue(afterRestart.isRecoveryRequested())
        assertNull(afterRestart.tryAcquireBusinessLease())
        afterRestart.tryAcquireRecoveryLease().use { assertNotNull(it) }
    }

    @Test fun `human exit still excludes business until exclusive lease is closed`() {
        val gate = gate()
        gate.requestRecovery()
        val recovery = gate.tryAcquireRecoveryLease()!!
        assertTrue(gate.clearRecoveryRequest(recovery))
        assertFalse(gate.isRecoveryRequested())
        assertNull(gate.tryAcquireBusinessLease())
        recovery.close()
        gate.tryAcquireBusinessLease().use { assertNotNull(it) }
    }

    @Test fun `business lease cannot clear a recovery request`() {
        val gate = gate()
        gate.tryAcquireBusinessLease().use { business ->
            gate.requestRecovery()
            assertThrows(IllegalArgumentException::class.java) {
                gate.clearRecoveryRequest(business!!)
            }
            assertTrue(gate.isRecoveryRequested())
        }
    }

    @Test fun `lease from another directory cannot clear request`() {
        val first = gate("first")
        val second = gate("second")
        first.requestRecovery()
        second.requestRecovery()
        first.tryAcquireRecoveryLease().use { lease ->
            assertThrows(IllegalArgumentException::class.java) {
                second.clearRecoveryRequest(lease!!)
            }
            assertTrue(second.isRecoveryRequested())
        }
    }

    @Test fun `closed recovery lease cannot clear request`() {
        val gate = gate()
        gate.requestRecovery()
        val recovery = gate.tryAcquireRecoveryLease()!!
        recovery.close()
        assertThrows(IllegalArgumentException::class.java) { gate.clearRecoveryRequest(recovery) }
        assertTrue(gate.isRecoveryRequested())
    }

    @Test fun `empty fence still blocks startup`() {
        val control = temporary.newFolder("control")
        assertTrue(File(control, "recovery-requested").createNewFile())
        assertTrue(gate().isRecoveryRequested())
        assertNull(gate().tryAcquireBusinessLease())
    }

    @Test fun `malformed fence is never recursively deleted`() {
        val control = temporary.newFolder("control")
        val malformed = File(control, "recovery-requested")
        assertTrue(malformed.mkdir())
        File(malformed, "evidence").writeText("preserve")
        val gate = gate()
        assertNull(gate.tryAcquireBusinessLease())
        gate.tryAcquireRecoveryLease().use { recovery ->
            assertFalse(gate.clearRecoveryRequest(recovery!!))
        }
        assertEquals("preserve", File(malformed, "evidence").readText())
    }

    @Test fun `unavailable control directory is an error not permission to start`() {
        temporary.newFile("control")
        assertThrows(IOException::class.java) { gate().tryAcquireBusinessLease() }
        assertThrows(IOException::class.java) { gate().requestRecovery() }
    }
}
