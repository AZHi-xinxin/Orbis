package me.rerere.rikkahub.data.orbis.toy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** No Android runtime or Bluetooth operations: synthetic grants and a fake GATT transport only. */
class ToyPermissionPolicyTest {
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val location = "android.permission.ACCESS_FINE_LOCATION"

    @Test fun `modern discovery still requests scan and connect but not location`() {
        assertEquals(setOf(scan, connect), ToyPermissionPolicy.scanPermissions(31).toSet())
        assertTrue(ToyPermissionPolicy.canScan(35) { it in setOf(scan, connect) })
    }

    @Test fun `connect alone permits communication without allowing discovery`() {
        val grants = setOf(connect)
        assertTrue(ToyPermissionPolicy.canConnect(31, grants::contains))
        assertFalse(ToyPermissionPolicy.canScan(31, grants::contains))
        assertEquals(listOf(connect), ToyPermissionPolicy.connectionPermissions(35))
    }

    @Test fun `scan alone never substitutes for connection permission`() {
        val grants = setOf(scan)
        assertFalse(ToyPermissionPolicy.canConnect(35, grants::contains))
        assertFalse(ToyPermissionPolicy.canScan(35, grants::contains))
    }

    @Test fun `modern missing permissions deny discovery and communication`() {
        assertFalse(ToyPermissionPolicy.canScan(31) { false })
        assertFalse(ToyPermissionPolicy.canConnect(31) { false })
    }

    @Test fun `legacy discovery needs location but existing GATT does not`() {
        for (api in listOf(26, 29, 30)) {
            assertEquals(listOf(location), ToyPermissionPolicy.scanPermissions(api))
            assertTrue(ToyPermissionPolicy.canScan(api) { it == location })
            assertFalse(ToyPermissionPolicy.canScan(api) { false })
            assertTrue(ToyPermissionPolicy.canConnect(api) { error("No runtime grant needed for legacy GATT") })
        }
    }

    @Test fun `connection grant is rechecked rather than cached`() {
        val grants = mutableSetOf(scan, connect)
        assertTrue(ToyPermissionPolicy.canConnect(35, grants::contains))
        grants.remove(scan)
        assertTrue(ToyPermissionPolicy.canConnect(35, grants::contains))
        grants.remove(connect)
        assertFalse(ToyPermissionPolicy.canConnect(35, grants::contains))
    }

    @Test fun `revoking scan does not prevent stop reaching fake connected device`() = runTest {
        val grants = mutableSetOf(scan, connect)
        val packets = mutableListOf<ByteArray>()
        val link = object : ToyTransport {
            override suspend fun write(packet: ByteArray) {
                check(ToyPermissionPolicy.canConnect(35, grants::contains))
                packets += packet.copyOf()
            }
            override fun close() = Unit
        }
        val session = ToySession(backgroundScope)
        session.attach("fixture", link); session.set(20)
        grants.remove(scan)
        val stopped = session.stop()
        assertTrue(stopped.connected); assertFalse(stopped.physicalStopUncertain)
        assertNull(stopped.requestedIntensity)
        assertArrayEquals(ToyProtocol.stop, packets.last())
    }

    @Test fun `revoking connect reports stop failure rather than bypassing Android permission`() = runTest {
        val grants = mutableSetOf(scan, connect)
        var closes = 0
        val link = object : ToyTransport {
            override suspend fun write(packet: ByteArray) {
                check(ToyPermissionPolicy.canConnect(35, grants::contains))
            }
            override fun close() { closes++ }
        }
        val session = ToySession(backgroundScope)
        session.attach("fixture", link); session.set(20)
        grants.remove(connect)
        val stopped = session.stop()
        assertFalse(stopped.connected); assertTrue(stopped.physicalStopUncertain)
        assertNull(stopped.requestedIntensity); assertEquals(1, closes)
    }
}
