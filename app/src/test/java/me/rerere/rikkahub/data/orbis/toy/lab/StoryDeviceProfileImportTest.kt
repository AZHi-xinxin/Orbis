package me.rerere.rikkahub.data.orbis.toy.lab

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoryDeviceProfileImportTest {
    private val svc = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val chr = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val report = LabDeviceReport("Synthetic", listOf(LabChannel(svc, chr, 12)))
    private fun source() = buildJsonObject {
        put("profileSchema", "story-device-ctrl/1"); put("version", 2); put("tool", "ble-channel-lab")
        put("deviceName", "Synthetic")
        putJsonObject("functions") {
            for (index in 1..3) putJsonObject("function$index") {
                put("label", "功能$index"); put("serviceUuid", svc); put("characteristicUuid", chr)
                put("writeMode", "without-response")
                put("onPacketHex", "55 0$index 00 00 01 0A AA")
                put("offPacketHex", "55 0$index 00 00 00 00 AA")
                put("levelByteIndex", 5); put("levelScale", if (index == 2) 10 else 1)
                put("levelMin", 1); put("levelMax", 10)
            }
        }
    }
    private fun modified(change: (JsonObject) -> JsonObject): JsonObject {
        val src = source(); val functions = src["functions"]!!.jsonObject
        return JsonObject(src + ("functions" to JsonObject(functions + ("function1" to change(functions["function1"]!!.jsonObject)))))
    }
    private fun reject(value: JsonObject) { try { decodeLabProfile(value, report); fail("must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun importsAllFunctionsLevelsModesAndUnknownMetadataWithoutUserRepair() {
        val p = decodeLabProfile(source(), report)
        assertEquals(30, p.actions.size); assertEquals("待核实", p.model)
        val a = p.actions.single { it.id == "fn2_level7" }
        assertEquals("55 02 00 00 01 46 AA", a.packet.hex)
        assertEquals(listOf("55 02 00 00 01 1E AA", "55 02 00 00 01 32 AA"), a.startup.map { it.packet.hex })
        assertTrue(a.startup.all { it.delayAfterMs == 60L }); assertEquals(500L, a.repeatMs)
        assertEquals("55 02 00 00 00 00 AA", a.stopPackets.single().hex)
        assertTrue(p.actions.all { it.packet.mode == "no_response" })
        val archive = LabArchive(report = report).addProfile(p, "imported", 1)
        assertEquals(archive, decodeLabArchive(ToyLabJson.encodeToString(archive)))
    }
    @Test fun missingOffDoesNotInventZeroOrBroadcastStops() {
        val p = decodeLabProfile(modified { JsonObject(it - "offPacketHex") }, report)
        assertTrue(p.actions.first().stopPackets.isEmpty()); assertNull(p.stop)
        assertTrue(p.notes.contains("没有关包"))
    }
    @Test fun withoutResponseAliasesUseNoResponseAndPropertyMismatchIsNotAFormatError() {
        for (alias in listOf("without-response", "withoutresponse", "writewithoutresponse", "nr", "no_response")) {
            val profile = decodeLabProfile(modified { JsonObject(it + ("writeMode" to JsonPrimitive(alias))) },
                report.copy(channels = listOf(LabChannel(svc, chr, 4))))
            assertTrue(profile.actions.all { it.packet.mode == "no_response" })
        }
        try {
            decodeLabProfile(source(), report.copy(channels = listOf(LabChannel(svc, chr, 8))))
            fail("must not silently switch modes or ignore the device report")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("方案为 no_response"))
            assertTrue(e.message!!.contains("response（有应答）"))
            assertTrue(e.message!!.contains("属性0x8"))
            assertTrue(e.message!!.contains("不是字段格式错误"))
        }
    }
    @Test fun malformedIndexModeChannelVersionAndExpressionGiveFailure() {
        reject(modified { JsonObject(it + ("levelByteIndex" to JsonPrimitive(99))) })
        reject(modified { JsonObject(it - "levelByteIndex") })
        reject(modified { JsonObject(it + ("writeMode" to JsonPrimitive("guess"))) })
        reject(modified { JsonObject(it + ("serviceUuid" to JsonPrimitive("180f"))) })
        reject(modified { JsonObject(it + ("onPacketHex" to JsonPrimitive("55 {value} AA"))) })
        reject(JsonObject(source() + ("version" to JsonPrimitive(99))))
    }
    @Test fun humanSampleLabelsAndShortUuidsHaveExactMeaning() {
        val src = modified { JsonObject(it - "onPacketHex" - "offPacketHex" + mapOf(
            "serviceUuid" to JsonPrimitive("ffe0"), "characteristicUuid" to JsonPrimitive("ffe1"),
            "levelByteIndex" to JsonPrimitive(-2),
            "samplePacketsHex" to JsonArray(listOf(JsonPrimitive("开: 55 01 00 00 01 05 AA"), JsonPrimitive("关: 55 01 00 00 00 00 AA"))))) }
        val p = decodeLabProfile(src, report)
        assertEquals("55 01 00 00 01 07 AA", p.actions.single { it.id == "fn1_level7" }.packet.hex)
    }
    private class Link(override val report: LabDeviceReport, val time: () -> Long) : LabTransport {
        val writes = mutableListOf<Pair<Long,String>>()
        var closed = false
        var failOnWrite = -1
        override suspend fun subscribe(channel: LabNotify) = Unit
        override suspend fun write(packet: LabPacket) {
            if (writes.size == failOnWrite) error("synthetic write failure")
            writes += time() to packet.hex
        }
        override fun close() { closed = true }
    }
    @Test fun trialSendsSoftRampThenKeepaliveAndOnlyChosenFunctionStop() = runTest {
        val p = decodeLabProfile(source(), report)
        val link = Link(report) { testScheduler.currentTime }; val session = ToyLabSession(this)
        session.start(link, LabProfileRevision(1,p,0,"test"), "fn2_level7", 2, {true}, {})
        advanceTimeBy(2001); runCurrent()
        assertEquals(listOf(0L,60L,120L,620L,1120L,1620L,2000L), link.writes.map { it.first })
        assertEquals("55 02 00 00 00 00 AA", link.writes.last().second)
        advanceTimeBy(50); runCurrent(); assertTrue(link.closed)
    }
    @Test fun cancellingDuringRampDoesNotSendRemainingStepsOrRepeat() = runTest {
        val p = decodeLabProfile(source(), report)
        val link = Link(report) { testScheduler.currentTime }; val session = ToyLabSession(this)
        session.start(link, LabProfileRevision(1,p,0,"test"), "fn1_level7", 10, {true}, {})
        advanceTimeBy(30); val stop = launch { session.stop() }; advanceUntilIdle(); stop.join()
        assertEquals(listOf("55 01 00 00 01 03 AA", "55 01 00 00 00 00 AA"), link.writes.map { it.second })
        assertTrue(link.closed)
    }
    @Test fun failedLaterWriteDoesNotReplayOrAppendStopPacket() = runTest {
        val p = decodeLabProfile(source(), report)
        val link = Link(report) { testScheduler.currentTime }; link.failOnWrite = 1
        val session = ToyLabSession(this)
        session.start(link, LabProfileRevision(1,p,0,"test"), "fn1_level7", 10, {true}, {})
        advanceUntilIdle(); assertEquals(1, link.writes.size); assertTrue(link.closed)
        assertFalse(session.state.value.trial!!.stopRequestSucceeded)
    }
    @Test fun explicitPulseSequencePreservesPacketOrderAndWaits() = runTest {
        val p = decodeLabProfile(source(), report); val action = p.actions.first()
        val pulse = action.copy(startup = emptyList(), cycle = listOf(LabStep(action.packet, 150), LabStep(action.stopPackets.single(), 500)), repeatMs = 0)
        val link = Link(report) { testScheduler.currentTime }; val session = ToyLabSession(this)
        session.start(link, LabProfileRevision(1,p.copy(actions = listOf(pulse)),0,"test"),pulse.id,2,{true},{})
        advanceTimeBy(700); assertEquals(listOf(0L,150L,650L),link.writes.map { it.first })
        val stop = launch { session.stop() }; advanceUntilIdle(); stop.join(); assertTrue(link.closed)
    }
}
