package me.rerere.rikkahub.data.orbis.toy

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** All hardware is fake. These tests never scan, connect or move a physical device. */
class YutuControlTest {
    private class Link(override val profile: ToyProfile = ToyProfile.YUTU_V2) : ToyTransport {
        val packets = mutableListOf<ByteArray>()
        var closes = 0
        var action: suspend () -> Unit = {}
        override suspend fun write(packet: ByteArray) { packets += packet.copyOf(); action() }
        override fun close() { closes++ }
    }

    @Test fun `only verified native target frames are encoded and zero is never a stop frame`() {
        for (value in listOf(1, 3, 50, 100)) assertArrayEquals(
            byteArrayOf(0xA0.toByte(), 0xA0.toByte(), value.toByte()), YutuControlContract.packet(value))
        for (value in listOf(-1, 0, 30, 35, 60, 80, 99, 101, 255, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            YutuControlContract.packet(value)
        }
    }

    @Test fun `tool contract rejects wrong units hidden modes and unbounded durations`() {
        val valid = YutuControlContract.parse(Json.parseToJsonElement("""{"strength":3,"duration_seconds":300}"""))
        assertEquals(YutuControlContract.Command(3, 300), valid)
        for (input in listOf(
            """{"strength":0,"duration_seconds":300}""", """{"strength":255,"duration_seconds":300}""",
            """{"strength":3,"duration_seconds":0}""", """{"strength":3,"duration_seconds":3601}""",
            """{"strength":3,"duration_seconds":1.5}""", """{"strength":"3","duration_seconds":300}""",
            """{"strength":75,"duration_seconds":300}""",
            """{"strength":3}""", """{"level":3}""", """{"strength":3,"duration_seconds":300,"mode":"wave"}"""
        )) assertThrows(input, IllegalArgumentException::class.java) { YutuControlContract.parse(Json.parseToJsonElement(input)) }
    }

    @Test fun `exact seven duration presets match schema parser and status`() {
        val expected = listOf(300, 600, 1200, 1800, 2400, 3000, 3600)
        assertEquals(expected, YutuControlContract.durationPresets)
        val schema = YutuControlContract.properties.getValue("duration_seconds").jsonObject
        assertEquals(300, schema.getValue("minimum").jsonPrimitive.int)
        assertEquals(3600, schema.getValue("maximum").jsonPrimitive.int)
        assertEquals(YutuControlContract.durationsJson, schema.getValue("enum"))
        expected.forEach { seconds ->
            assertEquals(YutuControlContract.Command(3, seconds), YutuControlContract.parse(
                Json.parseToJsonElement("""{"strength":3,"duration_seconds":$seconds}""")))
        }
        for (seconds in listOf(-1, 0, 1, 60, 299, 301, 900, 3599, 3601, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { YutuControlContract.validate(3, seconds) }
        }
        val status = Json.parseToJsonElement(ToyState(profile = ToyProfile.YUTU_V2).asToolJson()).jsonObject
        assertEquals(YutuControlContract.durationsJson, status.getValue("allowed_duration_seconds"))
        assertEquals(3600, status.getValue("max_duration_seconds").jsonPrimitive.int)
        assertFalse(status.getValue("automatic_duration_renewal").jsonPrimitive.boolean)
        assertTrue(status.getValue("strength_notice").jsonPrimitive.content.contains("相邻目标值"))
        assertEquals("1 小时", YutuControlContract.durationLabel(3600))
    }

    @Test fun `one hour runs past old minute limit then stops without automatic renewal`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(3, 3600); runCurrent()
        advanceTimeBy(60000); runCurrent()
        assertTrue(session.state.value.connected)
        advanceTimeBy(3539999); runCurrent()
        assertTrue(session.state.value.connected)
        advanceTimeBy(1); runCurrent()
        assertFalse(session.state.value.connected); assertEquals(1, link.closes)
        val count = link.packets.size
        advanceTimeBy(60000); runCurrent()
        assertEquals(count, link.packets.size)
    }

    @Test fun `immediate stop still interrupts a one hour command`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(100, 3600); runCurrent()
        session.stop(); val count = link.packets.size
        advanceTimeBy(3600001); runCurrent()
        assertFalse(session.state.value.connected); assertEquals(1, link.closes)
        assertEquals(count, link.packets.size)
    }

    @Test fun `connection is inert and reports correct capabilities`() = runTest {
        val session = ToySession(backgroundScope); val link = Link()
        session.attach("fixture", link); advanceTimeBy(5000); runCurrent()
        assertTrue(link.packets.isEmpty())
        val json = Json.parseToJsonElement(session.state.value.asToolJson()).jsonObject
        assertEquals("strength", json.getValue("input_field").jsonPrimitive.content)
        assertEquals(100, json.getValue("max_strength").jsonPrimitive.int)
        assertFalse(json.containsKey("allowed_levels"))
        assertFalse(json.getValue("pattern_control_supported").jsonPrimitive.boolean)
        assertFalse(json.getValue("physical_state_verified").jsonPrimitive.boolean)
    }

    @Test fun `yutu uses its own cadence never legacy frames`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(3, 300); runCurrent()
        advanceTimeBy(99); runCurrent(); assertEquals(1, link.packets.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, link.packets.size)
        assertTrue(link.packets.all { it.contentEquals(YutuControlContract.packet(3)) })
        session.stop()
    }

    @Test fun `explicit stop disconnects with no zero legacy or poweroff packets`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(3, 300); runCurrent(); val count = link.packets.size
        val result = session.stop(); advanceTimeBy(12000); runCurrent()
        assertEquals(count, link.packets.size); assertEquals(1, link.closes)
        assertFalse(result.connected); assertNull(result.requestedYutuStrength)
        assertTrue(result.physicalStopUncertain)
    }

    @Test fun `duration deadline disconnects and cancels repeat`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(3, 300); runCurrent(); advanceTimeBy(299999); runCurrent()
        assertTrue(session.state.value.connected)
        advanceTimeBy(1); runCurrent()
        assertFalse(session.state.value.connected); assertEquals(1, link.closes)
        val count = link.packets.size; advanceTimeBy(5000); runCurrent(); assertEquals(count, link.packets.size)
    }

    @Test fun `new command replaces old deadline without stale timeout closing new command`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        session.setYutu(3, 300); runCurrent(); advanceTimeBy(150000); runCurrent()
        session.setYutu(50, 600); runCurrent(); advanceTimeBy(150001); runCurrent()
        assertTrue(session.state.value.connected); assertEquals(50, session.state.value.requestedYutuStrength)
        advanceTimeBy(449999); runCurrent(); assertFalse(session.state.value.connected)
    }

    @Test fun `link loss clears command and manual reconnection does not replay it`() = runTest {
        val session = ToySession(backgroundScope); val first = Link(); session.attach("fixture", first)
        session.setYutu(3, 300); session.linkLost(first, "lost")
        assertNull(session.state.value.requestedYutuStrength)
        val next = Link(); session.attach("fixture again", next); advanceTimeBy(12000); runCurrent()
        assertTrue(next.packets.isEmpty()); assertTrue(session.state.value.connected)
    }

    @Test fun `stale approval invalid inputs and five level calls never reach yutu`() = runTest {
        val session = ToySession(backgroundScope); val link = Link(); session.attach("fixture", link)
        try { session.setYutu(3, 300) { false }; fail() } catch (_: IllegalStateException) { }
        try { session.setLevel(2); fail() } catch (_: IllegalStateException) { }
        try { session.setYutu(0, 300); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(link.packets.isEmpty())
        // Old zero API may request stop but must not write a zero YUTU packet.
        session.setLevel(0); assertEquals(1, link.closes); assertTrue(link.packets.isEmpty())
    }

    @Test fun `write timeout disconnects and cancels requested command`() = runTest {
        val session = ToySession(backgroundScope, writeTimeoutMs = 50); val link = Link()
        session.attach("fixture", link); link.action = { awaitCancellation() }
        val job = launch { session.setYutu(3, 300) }; runCurrent(); advanceTimeBy(50); runCurrent(); job.join()
        assertEquals(1, link.closes); assertFalse(session.state.value.connected)
        assertNull(session.state.value.requestedYutuStrength); assertTrue(session.state.value.physicalStopUncertain)
    }

    @Test fun `mixed protocols and additional yutu require explicit disconnect first`() = runTest {
        val session = ToySession(backgroundScope); session.attach("fixture", Link())
        for (next in listOf(Link(ToyProfile.SVAKOM), Link())) {
            try { session.attach("other", next); fail() } catch (_: IllegalStateException) { }
            assertTrue(next.packets.isEmpty())
        }
        assertEquals(1, session.state.value.deviceNames.size)
        session.stop(); val legacy = Link(ToyProfile.SVAKOM); session.attach("legacy", legacy)
        session.setLevel(1); assertArrayEquals(ToyProtocol.set(20), legacy.packets.single()); session.stop()
    }

    @Test fun `previous legacy command cannot start yutu on protocol change`() = runTest {
        val session = ToySession(backgroundScope); val legacy = Link(ToyProfile.SVAKOM)
        session.attach("legacy", legacy); session.setLevel(5); session.linkLost(legacy, "lost")
        val yutu = Link(); session.attach("fixture", yutu); advanceTimeBy(5000); runCurrent()
        assertTrue(yutu.packets.isEmpty()); assertNull(session.state.value.requestedIntensity)
        session.stop()
    }

    @Test fun `new protocol and reconnect invalidate old approval scope`() = runTest {
        val session = ToySession(backgroundScope); session.attach("fixture", Link())
        val previous = session.state.value.connectionRevision
        session.stop(); session.attach("fixture again", Link())
        assertTrue(session.state.value.connectionRevision > previous)
    }
}
