package me.rerere.rikkahub.data.orbis

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FreshHumanInputRecoveryStoreTest {
    private val conversation = "11111111-1111-4111-8111-111111111111"
    private val otherConversation = "22222222-2222-4222-8222-222222222222"
    private val assistant = "33333333-3333-4333-8333-333333333333"
    private val otherAssistant = "44444444-4444-4444-8444-444444444444"

    private class Disk(var bytes: String? = null) {
        var writes = 0
        var failRead = false
        var dropWrite = false
        fun raw() = OrbisQueuePauseStore(read = {
            if (failRead) throw IOException("synthetic failure")
            bytes
        }, write = { writes++; if (!dropWrite) bytes = it })
        fun open() = FreshHumanInputRecoveryStore(raw())
    }

    @Test fun `reading a fresh store creates no authorization and does not write`() {
        val disk = Disk()
        assertEquals(FreshHumanRecoveryStatus.NONE, disk.open().status(conversation, assistant))
        assertEquals(0, disk.writes)
        assertNull(disk.bytes)
    }

    @Test fun `explicit authorization survives restart only for its exact assistant and conversation`() {
        val disk = Disk()
        disk.open().authorize(conversation, assistant)
        val bytes = disk.bytes
        val writes = disk.writes
        assertEquals(FreshHumanRecoveryStatus.ACTIVE, disk.open().status(conversation, assistant))
        assertEquals(FreshHumanRecoveryStatus.NONE, disk.open().status(otherConversation, assistant))
        assertEquals(FreshHumanRecoveryStatus.OWNER_CHANGED, disk.open().status(conversation, otherAssistant))
        assertEquals(writes, disk.writes)
        assertEquals(bytes, disk.bytes)
    }

    @Test fun `same owner reauthorization is idempotent but a changed owner cannot overwrite it`() {
        val disk = Disk()
        val store = disk.open()
        store.authorize(conversation, assistant)
        val original = disk.bytes
        val writes = disk.writes
        store.authorize(conversation, assistant)
        assertTrue(runCatching { store.authorize(conversation, otherAssistant) }.isFailure)
        assertEquals(original, disk.bytes)
        assertEquals(writes, disk.writes)
        assertEquals(FreshHumanRecoveryStatus.ACTIVE, store.status(conversation, assistant))
    }

    @Test fun `fresh authorization never changes separate gateway and automatic hold facts`() {
        val gateway = Disk()
        val automatic = Disk()
        gateway.raw().pause(conversation, "gateway_terminal_unconfirmed")
        automatic.raw().pause(conversation, "unknown_tool_result")
        val gatewayBytes = gateway.bytes
        val automaticBytes = automatic.bytes
        Disk().open().authorize(conversation, assistant)
        assertEquals(gatewayBytes, gateway.bytes)
        assertEquals(automaticBytes, automatic.bytes)
        assertEquals(QueuePauseStatus.PAUSED, gateway.raw().status(conversation))
        assertEquals(QueuePauseStatus.PAUSED, automatic.raw().status(conversation))
    }

    @Test fun `silently dropped authorization cannot allow fresh input or repair itself`() {
        val disk = Disk().also { it.dropWrite = true }
        val store = disk.open()
        assertTrue(runCatching { store.authorize(conversation, assistant) }.isFailure)
        val writes = disk.writes
        assertEquals(FreshHumanRecoveryStatus.UNAVAILABLE, store.status(conversation, assistant))
        assertTrue(runCatching { store.authorize(conversation, assistant) }.isFailure)
        assertEquals(writes, disk.writes)
        assertNull(disk.bytes)
    }

    @Test fun `malformed unknown-version and unknown-purpose storage fail closed without replacement`() {
        for (bytes in listOf("not json", "{}", "[]",
            """{"version":2,"legacyMigrationDone":false,"pauses":{}}""",
            """{"version":1,"legacyMigrationDone":false,"pauses":{"$conversation":"unrelated_reason"}}""")) {
            val disk = Disk(bytes)
            val store = disk.open()
            assertEquals(bytes, FreshHumanRecoveryStatus.UNAVAILABLE, store.status(conversation, assistant))
            assertTrue(runCatching { store.authorize(conversation, assistant) }.isFailure)
            assertEquals(bytes, disk.bytes)
            assertEquals(0, disk.writes)
        }
    }

    @Test fun `disappearing storage cannot look like a fresh unrestricted store`() {
        val disk = Disk()
        val store = disk.open()
        store.authorize(conversation, assistant)
        val writes = disk.writes
        disk.bytes = null
        assertEquals(FreshHumanRecoveryStatus.UNAVAILABLE, store.status(conversation, assistant))
        assertTrue(runCatching { store.authorize(conversation, assistant) }.isFailure)
        assertEquals(writes, disk.writes)
    }

    @Test fun `a prior failed read cannot later become an empty unrestricted store`() {
        val disk = Disk().also { it.failRead = true }
        val store = disk.open()
        assertEquals(FreshHumanRecoveryStatus.UNAVAILABLE, store.status(conversation, assistant))
        disk.failRead = false
        assertEquals(FreshHumanRecoveryStatus.UNAVAILABLE, store.status(conversation, assistant))
        assertTrue(runCatching { store.authorize(conversation, assistant) }.isFailure)
        assertEquals(0, disk.writes)
    }

    @Test fun `invalid identity never writes an authorization`() {
        val disk = Disk()
        val store = disk.open()
        for ((c, a) in listOf("invalid" to assistant, conversation to "invalid", conversation to "1-1-1-1-1")) {
            assertEquals(FreshHumanRecoveryStatus.UNAVAILABLE, store.status(c, a))
            assertTrue(runCatching { store.authorize(c, a) }.isFailure)
        }
        assertNull(disk.bytes)
        assertEquals(0, disk.writes)
    }
}
