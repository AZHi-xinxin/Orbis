package me.rerere.rikkahub.data.orbis.toy.lab

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToyLabTest {
    private val service = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val characteristic = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val report = LabDeviceReport("Synthetic",listOf(LabChannel(service,characteristic,28)),"TEST","1")
    private fun profile() = labCandidates(report).single()
    private fun rev() = LabProfileRevision(1,profile(),0,"synthetic")
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    private class Fake(override val report: LabDeviceReport): LabTransport {
        val packets = mutableListOf<String>()
        var closed = false
        var failStop = false
        var blockWrite = false
        var failSecond = false
        override suspend fun subscribe(channel: LabNotify) { check(!closed) }
        override suspend fun write(packet: LabPacket) {
            check(!closed)
            if (blockWrite) awaitCancellation()
            if (failStop && packet.hex == "55 04 00 00 00 00 AA") error("stop_write_failed")
            if (failSecond && packets.isNotEmpty()) error("write_failed")
            packets += packet.hex
        }
        override fun close() { closed = true }
    }
    @Test fun unknownGattNeverGetsGuessedCommand() {
        assertTrue(labCandidates(report.copy(channels = listOf(LabChannel("0000180f-0000-1000-8000-00805f9b34fb",characteristic,8)))).isEmpty())
        assertTrue(labCandidates(report.copy(channels = report.channels + LabChannel(service,"0000ffe2-0000-1000-8000-00805f9b34fb",8))).isEmpty())
        assertTrue(labCandidates(report.copy(channels = listOf(report.channels.single().copy(properties = 2)))).isEmpty())
    }
    @Test fun candidateUsesKnownExplicitDiscreteEncodingAndStop() {
        val p = profile(); p.validate(report)
        assertEquals(listOf(50,100,160,210,255),p.actions.map { it.packet.bytes()[5].toInt() and 255 })
        assertEquals("55 04 00 00 00 00 AA",p.stop!!.hex)
        assertEquals("packet_then_disconnect",p.stopMethod)
    }
    @Test fun packetRejectsExpressionsUnknownChannelAndWrongWriteMode() {
        val packet = profile().actions.first().packet
        listOf("FF;alert(1)","0xFF","{strength}","A","AA".repeat(65)).forEach { hex -> reject { packet.copy(hex = hex).validate(report) } }
        reject { packet.copy(characteristic = "0000ffe2-0000-1000-8000-00805f9b34fb").validate(report) }
        reject { packet.copy(mode = "guess").validate(report) }
        reject { packet.copy(mode = "response").validate(report.copy(channels = listOf(report.channels.single().copy(properties = 4)))) }
    }
    @Test fun stopIsExplicitAndDisconnectDoesNotInventZeroPacket() {
        profile().copy(stop = null,stopMethod = "disconnect").validate(report)
        reject { profile().copy(stop = null).validate(report) }
        reject { profile().copy(stopMethod = "disconnect").validate(report) }
        reject { profile().copy(stopMethod = "send_zero").validate(report) }
    }
    @Test fun boundCountsRatesAndPayloadsRejectMalformedProfiles() {
        reject { profile().copy(repeatMs = 1).validate(report) }
        reject { profile().copy(maxRunSeconds = 301).validate(report) }
        reject { profile().copy(actions = emptyList()).validate(report) }
        reject { profile().copy(actions = listOf(profile().actions.first(),profile().actions.first())).validate(report) }
        reject { profile().copy(source = "").validate(report) }
        reject { profile().copy(handshake = List(9) { profile().actions.first().packet }).validate(report) }
        reject { profile().copy(notifications = listOf(LabNotify(service,"0000ffe3-0000-1000-8000-00805f9b34fb"))).validate(report) }
    }
    @Test fun identityFingerprintIgnoresNameAndTimestampButDetectsFirmwareAndProperties() {
        assertEquals(report.fingerprint(),report.copy(name = "rename",capturedAt = 100).fingerprint())
        assertNotEquals(report.fingerprint(),report.copy(firmware = "2").fingerprint())
        assertNotEquals(report.fingerprint(),report.copy(channels = listOf(report.channels.single().copy(properties = 4))).fingerprint())
    }
    @Test fun archiveRoundTripPreservesProtocolAndObservationsWithoutGrants() {
        val trial = LabTrial("trial",1,"level_1",2,0,true,"sent",true)
        val archive = LabArchive(report = report).addProfile(profile(),"人类",1).copy(observations = listOf(LabObservation(trial,"有反应","轻微","确认已停",2)))
        archive.validate()
        val text = ToyLabJson.encodeToString(archive)
        assertEquals(archive,decodeLabArchive(text))
        assertFalse(text.contains("sharedWith")); assertFalse(text.contains("address"))
        reject { decodeLabArchive(text.dropLast(1) + ",\"sharedWith\":\"all\"}") }
    }
    @Test fun importedObservationsMustReferenceExactActionsAndVersions() {
        val archive = LabArchive(report = report).addProfile(profile(),"test",1)
        val observation = LabObservation(LabTrial("trial",7,"missing",2,0),"有反应","","确认已停",1)
        reject { archive.copy(observations = listOf(observation)).validate() }
    }
    @Test fun editingCreatesVersionAndKeepsPreviousEvidence() {
        val first = LabArchive(report = report).addProfile(profile(),"human",1)
        val second = first.addProfile(profile().copy(name = "modified"),"AI",2)
        assertEquals(2,second.latest()!!.revision)
        assertEquals(first.revisions.single(),second.revisions.first())
    }
    @Test fun deadlineStopsThenDisconnectsAndNeverRepeatsAfterward() = runTest {
        val link = Fake(report); val session = ToyLabSession(this) { testScheduler.currentTime }
        session.start(link,rev(),"level_1",2,{true},{})
        runCurrent(); assertEquals(1,link.packets.size)
        advanceTimeBy(2001); runCurrent()
        assertTrue(link.closed); assertFalse(session.state.value.busy)
        assertEquals(profile().stop!!.hex,link.packets.last())
        assertTrue(session.state.value.trial!!.writeSucceeded)
        val count = link.packets.size; advanceTimeBy(10000); assertEquals(count,link.packets.size)
    }
    @Test fun stopCancelsRepeatImmediatelyAndKeepsFailedStopVisible() = runTest {
        val link = Fake(report).also { it.failStop = true }; val session = ToyLabSession(this)
        session.start(link,rev(),"level_1",10,{true},{})
        runCurrent(); session.stop(); val count = link.packets.size
        advanceTimeBy(10000); runCurrent()
        assertTrue(link.closed); assertEquals(count,link.packets.size)
        assertFalse(session.state.value.trial!!.stopRequestSucceeded)
        assertTrue(session.state.value.message.contains("停止指令未确认"))
    }
    @Test fun revokedLeasePreventsNextWriteAndCloses() = runTest {
        var valid = true; val link = Fake(report); val session = ToyLabSession(this)
        session.start(link,rev(),"level_1",10,{valid},{})
        runCurrent(); valid = false; advanceTimeBy(1500); runCurrent()
        assertTrue(link.closed); assertFalse(session.state.value.busy)
        assertEquals(1,link.packets.count { it == profile().actions.first().packet.hex })
    }
    @Test fun blockedWriteCannotEscapeDeadlineOrBeReportedAsSuccess() = runTest {
        val link = Fake(report).also { it.blockWrite = true }; val session = ToyLabSession(this)
        session.start(link,rev(),"level_1",2,{true},{})
        advanceTimeBy(2001); runCurrent()
        assertTrue(link.closed); assertFalse(session.state.value.trial!!.writeSucceeded)
        assertTrue(session.state.value.message.contains("未确认"))
    }
    @Test fun disconnectStopNeverSendsInventedCommand() = runTest {
        val link = Fake(report); val session = ToyLabSession(this)
        val revision = rev().copy(profile = profile().copy(stop = null,stopMethod = "disconnect",repeatMs = 0))
        session.start(link,revision,"level_1",2,{true},{})
        advanceTimeBy(2001); runCurrent(); assertEquals(1,link.packets.size); assertTrue(link.closed)
    }
    @Test fun invalidScopeAndActionsAreRejectedBeforeAnyWrite() = runTest {
        val link = Fake(report); val session = ToyLabSession(this)
        try { session.start(link,rev(),"level_1",2,{false},{}); fail() } catch (_: IllegalStateException) {}
        try { session.start(link,rev(),"unknown",2,{true},{}); fail() } catch (_: IllegalStateException) {}
        assertTrue(link.packets.isEmpty())
    }
    @Test fun concurrentStartCannotReplaceRunningCommand() = runTest {
        val link = Fake(report); val session = ToyLabSession(this)
        session.start(link,rev(),"level_1",2,{true},{})
        try { session.start(link,rev(),"level_2",2,{true},{}); fail() } catch (_: IllegalStateException) {}
        session.stop()
    }
}
