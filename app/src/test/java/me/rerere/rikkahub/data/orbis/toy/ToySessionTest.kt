package me.rerere.rikkahub.data.orbis.toy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake links and virtual clocks only. No Android permissions, scanning or real hardware. */
class ToySessionTest {
    private class FakeLink : ToyTransport {
        val packets = mutableListOf<ByteArray>()
        var closes = 0
        var action: suspend (ByteArray) -> Unit = {}
        override suspend fun write(packet: ByteArray) { packets += packet.copyOf(); action(packet) }
        override fun close() { closes++ }
    }

    @Test fun `original five ranges and exact packet bytes`() {
        val boundaries = mapOf(0 to 0, 1 to 50, 20 to 50, 21 to 100, 40 to 100,
            41 to 160, 60 to 160, 61 to 210, 80 to 210, 81 to 255, 100 to 255)
        boundaries.forEach { (intensity, value) ->
            assertArrayEquals(byteArrayOf(0x55, 4, 0, 0, 1, value.toByte(), 0xAA.toByte()), ToyProtocol.set(intensity))
        }
        assertArrayEquals(byteArrayOf(0x55, 4, 0, 0, 0, 0, 0xAA.toByte()), ToyProtocol.stop)
    }
    @Test fun `out of range intensity is rejected`() {
        listOf(-1, 101, Int.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { ToyProtocol.set(it) }
        }
    }
    @Test fun `new session has no selected device or running command`() = runTest {
        val session = ToySession(backgroundScope)
        assertFalse(session.state.value.connected); assertNull(session.state.value.requestedIntensity)
    }
    @Test fun `manual attach is inert with no existing command`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink()
        session.attach("fixture", link)
        advanceTimeBy(5000); runCurrent()
        assertTrue(session.state.value.connected); assertTrue(link.packets.isEmpty())
    }
    @Test fun `multiple manually selected devices coexist`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val second = FakeLink()
        session.attach("one", first); session.attach("two", second)
        assertEquals(listOf("one", "two"), session.state.value.deviceNames)
        assertEquals(0, first.closes); assertEquals(0, second.closes)
    }
    @Test fun `nonzero set refuses disconnected session`() = runTest {
        val session = ToySession(backgroundScope)
        try { session.set(1); fail("must refuse") } catch (_: IllegalStateException) { }
    }
    @Test fun `invalid command never writes to connected links`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        try { session.set(101); fail("must refuse") } catch (_: IllegalArgumentException) { }
        assertTrue(link.packets.isEmpty())
    }
    @Test fun `set is broadcast to all manually selected links`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val second = FakeLink()
        session.attach("one", first); session.attach("two", second); session.set(41)
        assertArrayEquals(ToyProtocol.set(41), first.packets.single())
        assertArrayEquals(ToyProtocol.set(41), second.packets.single())
        assertEquals(3, session.state.value.level); session.stop()
    }
    @Test fun `continuous command repeats every 1400 milliseconds`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        session.set(21); runCurrent()
        advanceTimeBy(1399); runCurrent(); assertEquals(1, link.packets.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, link.packets.size)
        advanceTimeBy(1400); runCurrent(); assertEquals(3, link.packets.size); session.stop()
    }
    @Test fun `continuous mode is not stopped at the removed sixty second limit`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        session.set(80); runCurrent(); advanceTimeBy(61_000); runCurrent()
        assertEquals(80, session.state.value.requestedIntensity)
        assertTrue(link.packets.size > 40)
        assertTrue(link.packets.all { it[4] == 1.toByte() }); session.stop()
    }
    @Test fun `explicit stop cancels heartbeat and stops all devices`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val second = FakeLink()
        session.attach("one", first); session.attach("two", second); session.set(1); runCurrent(); session.stop()
        val count = first.packets.size; advanceTimeBy(5000); runCurrent()
        assertEquals(count, first.packets.size); assertNull(session.state.value.requestedIntensity)
        assertArrayEquals(ToyProtocol.stop, first.packets.last()); assertArrayEquals(ToyProtocol.stop, second.packets.last())
    }
    @Test fun `zero intensity is the explicit stop alias even if activation grant is stale`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        session.set(100); session.set(0) { false }
        assertNull(session.state.value.requestedIntensity); assertArrayEquals(ToyProtocol.stop, link.packets.last())
    }
    @Test fun `manual addition while running follows current intensity on next heartbeat`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val second = FakeLink()
        session.attach("one", first); session.set(61); runCurrent(); session.attach("two", second)
        assertTrue(second.packets.isEmpty()); advanceTimeBy(1400); runCurrent()
        assertArrayEquals(ToyProtocol.set(61), second.packets.single()); session.stop()
    }
    @Test fun `disconnection retains original last command but never reconnects itself`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val next = FakeLink()
        session.attach("one", first); session.set(40); runCurrent(); session.linkLost(first, "lost")
        advanceTimeBy(5000); runCurrent()
        assertFalse(session.state.value.connected); assertEquals(40, session.state.value.requestedIntensity)
        assertEquals(1, first.packets.size)
        session.attach("next", next); advanceTimeBy(1400); runCurrent()
        assertArrayEquals(ToyProtocol.set(40), next.packets.single()); session.stop()
    }
    @Test fun `stale disconnect cannot remove another connected device`() = runTest {
        val session = ToySession(backgroundScope); val first = FakeLink(); val next = FakeLink()
        session.attach("one", first); session.linkLost(first, "lost"); session.attach("next", next)
        val revision = session.state.value.connectionRevision; session.linkLost(first, "stale")
        assertEquals(listOf("next"), session.state.value.deviceNames)
        assertEquals(revision, session.state.value.connectionRevision); assertEquals(0, next.closes)
    }
    @Test fun `one failed link does not stop the other links and warning is not overwritten`() = runTest {
        val session = ToySession(backgroundScope); val failed = FakeLink(); val good = FakeLink()
        failed.action = { error("synthetic failure") }
        session.attach("failed", failed); session.attach("good", good)
        val result = session.set(1)
        assertEquals(listOf("good"), result.deviceNames); assertTrue(result.physicalStopUncertain)
        assertTrue(result.message.contains("失败")); assertEquals(1, failed.closes)
        assertArrayEquals(ToyProtocol.set(1), good.packets.single()); session.stop()
    }
    @Test fun `write timeout closes failed link and keeps physical state uncertain`() = runTest {
        val session = ToySession(backgroundScope, writeTimeoutMs = 100); val link = FakeLink()
        link.action = { awaitCancellation() }; session.attach("fixture", link)
        val job = launch { session.set(1) }; runCurrent(); advanceTimeBy(100); runCurrent(); job.join()
        assertFalse(session.state.value.connected); assertTrue(session.state.value.physicalStopUncertain)
        assertEquals(1, link.closes); session.stop()
    }
    @Test fun `failed stop reports uncertainty and clears the failed link`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        link.action = { error("synthetic failure") }; val result = session.stop()
        assertFalse(result.connected); assertTrue(result.physicalStopUncertain)
        assertTrue(result.message.contains("失败")); assertEquals(1, link.closes)
    }
    @Test fun `manual stop and disconnect writes stop before closing all links`() = runTest {
        val session = ToySession(backgroundScope); val one = FakeLink(); val two = FakeLink()
        session.attach("one", one); session.attach("two", two); session.set(1); session.stop(disconnect = true)
        assertFalse(session.state.value.connected); assertNull(session.state.value.requestedIntensity)
        listOf(one, two).forEach { assertEquals(1, it.closes); assertArrayEquals(ToyProtocol.stop, it.packets.last()) }
    }
    @Test fun `scope revision changes with connection membership but not ordinary commands`() = runTest {
        val session = ToySession(backgroundScope); val one = FakeLink(); val two = FakeLink()
        val initial = session.state.value.connectionRevision
        session.attach("one", one); val attached = session.state.value.connectionRevision
        assertTrue(attached > initial); session.set(1); session.stop()
        assertEquals(attached, session.state.value.connectionRevision)
        session.attach("two", two); assertTrue(session.state.value.connectionRevision > attached)
        val both = session.state.value.connectionRevision
        session.linkLost(one, "lost"); assertTrue(session.state.value.connectionRevision > both)
    }
    @Test fun `stop is serialized after bounded pending write and invalidates queued activation`() = runTest {
        val session = ToySession(backgroundScope); val link = FakeLink(); session.attach("fixture", link)
        val release = CompletableDeferred<Unit>()
        link.action = { if (it[4] == 1.toByte()) release.await() }
        val first = async { session.set(1) }; runCurrent()
        var permitted = true
        val queued = async { try { session.set(21) { permitted }; false } catch (_: IllegalStateException) { true } }
        runCurrent(); permitted = false
        val stop = async { session.stop() }; release.complete(Unit)
        first.await(); assertTrue(queued.await()); stop.await()
        assertEquals(2, link.packets.size); assertArrayEquals(ToyProtocol.stop, link.packets.last())
    }
    @Test fun `stop on disconnected session remains available`() = runTest {
        val session = ToySession(backgroundScope); val result = session.set(0)
        assertFalse(result.connected); assertNull(result.requestedIntensity)
    }
}
