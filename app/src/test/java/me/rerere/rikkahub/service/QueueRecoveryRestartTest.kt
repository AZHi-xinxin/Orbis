package me.rerere.rikkahub.service

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test

class QueueRecoveryRestartTest {
    private val conversation = "11111111-1111-4111-8111-111111111111"
    private val otherConversation = "22222222-2222-4222-8222-222222222222"

    private class Disk(var contents: String? = null) {
        var readsFail = false
        var dropWrites = false
        var writes = 0

        fun open() = OrbisQueuePauseStore(read = {
            if (readsFail) throw IOException("synthetic read failure")
            contents
        }, write = {
            writes++
            if (!dropWrites) contents = it
        })
    }

    private fun allowed(gateway: OrbisQueuePauseStore, automatic: OrbisQueuePauseStore,
        sessionBlocked: Boolean = false, observedGateway: Boolean = false): Boolean =
        mayRecoverWithoutGatewayOwner(sessionBlocked, gateway.status(conversation), observedGateway,
            automatic.pauseReason(conversation))

    @Test fun `ordinary legacy pause reopens as local only with no writes or control callbacks`() = runTest {
        val queueDisk = Disk()
        val gatewayDisk = Disk()
        val automaticDisk = Disk()
        queueDisk.open().pause(conversation, "generation_or_human_pause")
        val queueBytes = queueDisk.contents
        val initialWrites = queueDisk.writes
        val reopenedQueue = queueDisk.open()
        assertEquals(QueuePauseStatus.PAUSED, reopenedQueue.status(conversation))
        assertEquals("generation_or_human_pause", reopenedQueue.pauseReason(conversation))

        val localOnly = allowed(gatewayDisk.open(), automaticDisk.open())
        var controlCalls = 0
        // This is the caller's branch decision, not an end-to-end ChatService test. A permitted
        // local-only restart must not enter even a synthetic status/finish path.
        if (!localOnly) recoverEndedGatewayRequests(listOf("synthetic-request"), { true }, { _, _ -> true },
            { controlCalls++; GatewayStopProbe.CAN_STOP }, { _, _ -> controlCalls++; true })
        assertTrue(localOnly)
        assertEquals(0, controlCalls)
        assertEquals(initialWrites, queueDisk.writes)
        assertEquals(0, gatewayDisk.writes)
        assertEquals(0, automaticDisk.writes)
        assertEquals(queueBytes, queueDisk.contents)
        assertNull(gatewayDisk.contents)
        assertNull(automaticDisk.contents)
        // Admission is read-only: the caller still owes durable queue resume and local gates.
        assertEquals(QueuePauseStatus.PAUSED, reopenedQueue.status(conversation))
    }

    @Test fun `new durable gateway pause or unavailable status never permits local only`() {
        for (status in listOf(QueuePauseStatus.PAUSED, QueuePauseStatus.UNAVAILABLE)) {
            assertFalse(mayRecoverWithoutGatewayOwner(false, status, false, null))
        }
        val gatewayDisk = Disk()
        gatewayDisk.open().pause(conversation, "gateway_terminal_unconfirmed")
        val before = gatewayDisk.contents
        val writes = gatewayDisk.writes
        assertFalse(allowed(gatewayDisk.open(), Disk().open()))
        assertEquals(before, gatewayDisk.contents)
        assertEquals(writes, gatewayDisk.writes)
    }

    @Test fun `an in memory gateway block wins even when its durable store is clear`() {
        assertFalse(allowed(Disk().open(), Disk().open(), sessionBlocked = true))
    }

    @Test fun `legacy exact transport hold blocks and remains byte for byte intact`() {
        val automaticDisk = Disk()
        automaticDisk.open().pause(conversation, "gateway_terminal_unconfirmed")
        val before = automaticDisk.contents
        val writes = automaticDisk.writes
        assertFalse(allowed(Disk().open(), automaticDisk.open()))
        assertEquals(before, automaticDisk.contents)
        assertEquals(writes, automaticDisk.writes)
        assertEquals("gateway_terminal_unconfirmed", automaticDisk.open().pauseReason(conversation))
    }

    @Test fun `unknown and other automatic holds survive while local gate may admit`() {
        for (reason in listOf("unknown_tool_result", "legacy_unknown_tool_result", "event_receipt_not_saved")) {
            val automaticDisk = Disk()
            automaticDisk.open().pause(conversation, reason)
            val before = automaticDisk.contents
            val writes = automaticDisk.writes
            assertTrue(allowed(Disk().open(), automaticDisk.open()))
            assertEquals(QueuePauseStatus.PAUSED, automaticDisk.open().status(conversation))
            assertEquals(reason, automaticDisk.open().pauseReason(conversation))
            assertEquals(before, automaticDisk.contents)
            assertEquals(writes, automaticDisk.writes)
        }
    }

    @Test fun `current or historical observed gateway evidence blocks missing owner recovery`() {
        for (evidenceSource in listOf("current", "historical")) {
            assertFalse(evidenceSource, allowed(Disk().open(), Disk().open(), observedGateway = true))
        }
    }

    @Test fun `fresh or unrelated pause reason is definitely absent without creating storage`() {
        val fresh = Disk()
        assertNull(fresh.open().pauseReason(conversation))
        assertEquals(0, fresh.writes)
        val disk = Disk()
        disk.open().pause(otherConversation, "gateway_terminal_unconfirmed")
        val writes = disk.writes
        val before = disk.contents
        assertNull(disk.open().pauseReason(conversation))
        assertTrue(allowed(Disk().open(), disk.open()))
        assertEquals(before, disk.contents)
        assertEquals(writes, disk.writes)
    }

    @Test fun `corrupt reason storage throws instead of pretending no legacy hold exists`() {
        for (contents in listOf("not json", "{}", "[]",
            """{"version":2,"legacyMigrationDone":false,"pauses":{}}""")) {
            val disk = Disk(contents)
            val store = disk.open()
            assertNotNull(runCatching { store.pauseReason(conversation) }.exceptionOrNull())
            assertNotNull(runCatching { allowed(Disk().open(), store) }.exceptionOrNull())
            assertEquals(contents, disk.contents)
            assertEquals(0, disk.writes)
        }
    }

    @Test fun `reason read failure throws and cannot later become a fresh absent store`() {
        val disk = Disk()
        val store = disk.open()
        disk.readsFail = true
        assertNotNull(runCatching { store.pauseReason(conversation) }.exceptionOrNull())
        disk.readsFail = false
        assertNotNull(runCatching { store.pauseReason(conversation) }.exceptionOrNull())
        assertNotNull(runCatching { allowed(Disk().open(), store) }.exceptionOrNull())
        assertNull(disk.contents)
        assertEquals(0, disk.writes)
    }

    @Test fun `uncertain reason throws even when previous valid bytes are still present`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(conversation, "unknown_tool_result")
        val before = disk.contents
        disk.dropWrites = true
        assertNotNull(runCatching { store.resume(conversation) }.exceptionOrNull())
        val writes = disk.writes
        assertEquals(QueuePauseStatus.PAUSED, store.status(conversation))
        assertNotNull(runCatching { store.pauseReason(conversation) }.exceptionOrNull())
        assertNotNull(runCatching { allowed(Disk().open(), store) }.exceptionOrNull())
        assertEquals(before, disk.contents)
        assertEquals(writes, disk.writes)
    }

    @Test fun `previously observed automatic hold file disappearing cannot return null reason`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(conversation, "gateway_terminal_unconfirmed")
        val writes = disk.writes
        disk.contents = null
        assertNotNull(runCatching { store.pauseReason(conversation) }.exceptionOrNull())
        assertNotNull(runCatching { allowed(Disk().open(), store) }.exceptionOrNull())
        assertEquals(0, disk.writes - writes)
        assertNull(disk.contents)
    }
}
