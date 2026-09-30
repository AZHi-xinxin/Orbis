package me.rerere.rikkahub.data.orbis

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisQueuePauseStoreTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val assistant = "33333333-3333-4333-8333-333333333333"

    private class Disk(var contents: String? = null) {
        var readsFail = false
        var writesFail = false
        var dropWrites = false
        var writes = 0
        fun open() = OrbisQueuePauseStore(read = {
            if (readsFail) throw IOException("synthetic read failure")
            contents
        }, write = {
            if (writesFail) throw IOException("synthetic write failure")
            writes++
            if (!dropWrites) contents = it
        })
    }

    private fun event(id: String, state: String, at: Long, conversation: String = first) = OrbisInboxEvent(
        id = id, eventId = id, source = "self_reminder", text = "synthetic event", wake = true,
        assistantId = assistant, conversationId = conversation, receivedAt = at, state = state,
    )

    @Test fun `fresh store reads without creating a file and is conversation scoped`() {
        val disk = Disk()
        val store = disk.open()
        assertFalse(store.isPaused(first))
        assertEquals(QueuePauseStatus.UNPAUSED, store.status(second))
        assertEquals(0, disk.writes)
        store.pause(first)
        assertTrue(store.isPaused(first))
        assertFalse(store.isPaused(second))
        assertTrue(disk.open().isPaused(first))
    }

    @Test fun `resume is persisted and same values do not rewrite storage`() {
        val disk = Disk()
        val store = disk.open()
        store.resume(first)
        assertEquals(0, disk.writes)
        store.pause(first)
        store.pause(first, "another_reason")
        assertEquals(1, disk.writes)
        store.resume(first)
        store.resume(first)
        assertEquals(2, disk.writes)
        assertFalse(disk.open().isPaused(first))
    }

    @Test fun `failed resume keeps original bytes and remains paused`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(first)
        val before = disk.contents
        disk.writesFail = true
        assertNotNull(runCatching { store.resume(first) }.exceptionOrNull())
        assertEquals(before, disk.contents)
        assertTrue(store.isPaused(first))
        assertTrue(disk.open().isPaused(first))
        disk.writesFail = false
        store.resume(first)
        assertFalse(store.isPaused(first))
    }

    @Test fun `failed first pause quarantines this conversation in memory but not another`() {
        val disk = Disk()
        val store = disk.open()
        disk.writesFail = true
        assertNotNull(runCatching { store.pause(first) }.exceptionOrNull())
        assertTrue(store.isPaused(first))
        assertFalse(store.isPaused(second))
        assertEquals(null, disk.contents)
        disk.writesFail = false
        store.pause(first)
        assertTrue(disk.open().isPaused(first))
    }

    @Test fun `silent dropped write cannot report successful resume`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(first)
        disk.dropWrites = true
        assertNotNull(runCatching { store.resume(first) }.exceptionOrNull())
        assertTrue(store.isPaused(first))
        assertEquals("queue_pause_write_failed", store.lastFailure)
    }

    @Test fun `malformed unsupported or missing schema is unavailable and never overwritten`() {
        listOf("not json", "{}", "[]",
            """{"version":2,"legacyMigrationDone":true,"pauses":{}}""",
            """{"version":1,"legacyMigrationDone":true,"pauses":{},"unexpected":true}""",
            """{"version":1,"legacyMigrationDone":true,"pauses":{"bad-id":"pause"}}""",
        ).forEach { bad ->
            val disk = Disk(bad)
            val store = disk.open()
            assertEquals(QueuePauseStatus.UNAVAILABLE, store.status(first))
            assertTrue(store.isPaused(first))
            assertNotNull(runCatching { store.resume(first) }.exceptionOrNull())
            assertNotNull(runCatching { store.pause(first) }.exceptionOrNull())
            assertNotNull(runCatching { store.migrateLegacy(emptyList()) }.exceptionOrNull())
            assertEquals(bad, disk.contents)
            assertEquals(0, disk.writes)
        }
    }

    @Test fun `read failure is unavailable and cannot become a fresh missing store`() {
        val disk = Disk()
        val store = disk.open()
        disk.readsFail = true
        assertEquals(QueuePauseStatus.UNAVAILABLE, store.status(first))
        disk.readsFail = false
        assertEquals(QueuePauseStatus.UNAVAILABLE, store.status(first))
        assertNotNull(runCatching { store.resume(first) }.exceptionOrNull())
        assertEquals(0, disk.writes)
    }

    @Test fun `a previously seen file disappearing does not silently resume`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(first)
        disk.contents = null
        assertEquals(QueuePauseStatus.UNAVAILABLE, store.status(first))
        assertTrue(store.isPaused(first))
        assertEquals("queue_pause_storage_disappeared", store.lastFailure)
    }

    @Test fun `migration pauses latest uncertain receipt followed by pending input only once`() {
        val disk = Disk()
        val store = disk.open()
        store.migrateLegacy(listOf(event("old", "unknown", 1), event("later", "queued", 2)))
        assertTrue(store.isPaused(first))
        store.resume(first)
        val writes = disk.writes
        disk.open().migrateLegacy(listOf(event("new-empty", "unknown", 3), event("new-input", "queued", 4)))
        assertFalse(disk.open().isPaused(first))
        assertEquals(writes, disk.writes)
    }

    @Test fun `migration respects latest dispatched outcome and preserves explicit pauses`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(second, "human_pause")
        store.migrateLegacy(listOf(event("old", "unknown", 1), event("recovered", "replied", 2),
            event("later", "accepted", 3)))
        assertFalse(store.isPaused(first))
        assertTrue(store.isPaused(second))
        assertTrue(disk.contents!!.contains("human_pause"))
    }

    @Test fun `migration does not pause no-pending or only-pending conversations`() {
        val disk = Disk()
        val store = disk.open()
        store.migrateLegacy(listOf(event("failure-only", "failed", 1),
            event("pending-only", "queued", 1, second)))
        assertFalse(store.isPaused(first))
        assertFalse(store.isPaused(second))
    }

    @Test fun `equal receipt timestamps use original inbox order`() {
        val disk = Disk()
        val store = disk.open()
        store.migrateLegacy(listOf(event("old", "generating", 1), event("later", "queued", 1)))
        assertTrue(store.isPaused(first))
    }

    @Test fun `failed migration preserves prior bytes and can be retried`() {
        val disk = Disk()
        val store = disk.open()
        store.pause(second, "human_pause")
        val before = disk.contents
        disk.writesFail = true
        val events = listOf(event("old", "unknown", 1), event("later", "queued", 2))
        assertNotNull(runCatching { store.migrateLegacy(events) }.exceptionOrNull())
        assertEquals(before, disk.contents)
        assertTrue(store.isPaused(first))
        assertTrue(store.isPaused(second))
        disk.writesFail = false
        store.migrateLegacy(events)
        assertTrue(disk.open().isPaused(first))
        assertTrue(disk.open().isPaused(second))
    }
}
