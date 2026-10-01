package me.rerere.rikkahub.data.orbis.schedule

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrbisScheduleStoreTest {
    private class Memory : OrbisSchedulePersistence {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        var dropWrite = false
        override fun read(): ByteArray? {
            if (failRead) throw IOException("synthetic secret detail")
            return bytes?.copyOf()
        }
        override fun write(bytes: ByteArray) {
            writes++
            if (failWrite) throw IOException("synthetic secret detail")
            if (!dropWrite) this.bytes = bytes.copyOf()
        }
    }
    private fun draft(title: String = "合成日程") = OrbisScheduleDraft(OrbisScheduleKind.DATE, title, allDay = true, date = "2026-09-30")
    private fun store(memory: Memory, id: String = "synthetic-1") = OrbisScheduleStore(memory, { 100 }, { id })

    @Test fun `empty read needs no write and no gateway configuration`() = runBlocking {
        val disk = Memory()
        assertEquals(OrbisScheduleSnapshot(), store(disk).load())
        assertNull(disk.bytes)
        assertEquals(0, disk.writes)
    }

    @Test fun `human and AI can reopen one storage and edit every field`() = runBlocking {
        val disk = Memory()
        val human = store(disk)
        val first = human.create(0, draft())
        val ai = store(disk)
        assertEquals(first, ai.load())
        val weekly = OrbisScheduleDraft(OrbisScheduleKind.WEEKLY, "合成课表", notes = "新备注", location = "合成教室", important = true,
            startTime = "12:00", endTime = "13:00", weekdays = listOf(2, 4), validFrom = "2026-10-01", validUntil = "2027-01-01")
        val second = ai.update(first.revision, first.entries.single().id, weekly)
        assertEquals(second, human.load())
        assertEquals(weekly, second.entries.single().details)
        assertEquals(100L, second.entries.single().createdAt)
        assertTrue(ai.delete(second.revision, first.entries.single().id).entries.isEmpty())
        assertEquals(3, human.load().revision)
    }

    @Test fun `stale update and delete cannot overwrite newer work`() = runBlocking {
        val disk = Memory()
        val value = store(disk)
        value.create(0, draft())
        value.update(1, "synthetic-1", draft("newer"))
        val exact = disk.bytes!!.copyOf()
        assertEquals("schedule_revision_changed", runCatching { value.update(1, "synthetic-1", draft("stale")) }.exceptionOrNull()?.message)
        assertEquals("schedule_revision_changed", runCatching { value.delete(1, "synthetic-1") }.exceptionOrNull()?.message)
        assertArrayEquals(exact, disk.bytes)
        assertEquals(2, disk.writes)
    }

    @Test fun `concurrent CAS creates have exactly one winner`() = runBlocking {
        val disk = Memory()
        val a = store(disk, "a")
        val b = store(disk, "b")
        val attempts = listOf(async { runCatching { a.create(0, draft("A")) } }, async { runCatching { b.create(0, draft("B")) } }).awaitAll()
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, a.load().entries.size)
        assertEquals(1, disk.writes)
    }

    @Test fun `corrupt missing fields and invalid UTF8 never become empty writable data`() = runBlocking {
        listOf("{}".toByteArray(), "garbage".toByteArray(), "{\"version\":2,\"revision\":0,\"entries\":[]}".toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28)).forEach { raw ->
            val disk = Memory().apply { bytes = raw }
            val value = store(disk)
            assertEquals("schedule_invalid_storage", runCatching { value.load() }.exceptionOrNull()?.message)
            assertNotNull(runCatching { value.create(0, draft()) }.exceptionOrNull())
            assertArrayEquals(raw, disk.bytes)
            assertEquals(0, disk.writes)
        }
    }

    @Test fun `observed file disappearing and initial failed read block a fresh overwrite`() = runBlocking {
        val disk = Memory()
        val value = store(disk)
        value.create(0, draft())
        disk.bytes = null
        assertEquals("schedule_storage_disappeared", runCatching { value.create(0, draft()) }.exceptionOrNull()?.message)
        assertEquals(1, disk.writes)
        val failed = Memory().apply { failRead = true }
        val failedStore = store(failed)
        assertEquals("schedule_storage_unavailable", runCatching { failedStore.load() }.exceptionOrNull()?.message)
        failed.failRead = false
        assertNotNull(runCatching { failedStore.create(0, draft()) }.exceptionOrNull())
        assertEquals(0, failed.writes)
    }

    @Test fun `failed or unverifiable writes cannot return a committed result or expose raw errors`() = runBlocking {
        listOf(Memory().apply { failWrite = true }, Memory().apply { dropWrite = true }).forEach { disk ->
            val result = runCatching { store(disk).create(0, draft()) }
            assertEquals("schedule_write_unverified", result.exceptionOrNull()?.message)
            assertNull(result.exceptionOrNull()?.cause)
            assertEquals(1, disk.writes)
        }
    }

    @Test fun `unknown fields duplicate IDs and exhausted revisions cannot be rewritten`() = runBlocking {
        val entry = OrbisScheduleEntry("synthetic-1", draft(), 1, 1)
        val raw = Json { encodeDefaults = true }.encodeToString(OrbisScheduleSnapshot(revision = 1, entries = listOf(entry, entry)))
        val duplicate = Memory().apply { bytes = raw.toByteArray() }
        assertNotNull(runCatching { store(duplicate).load() }.exceptionOrNull())
        assertEquals(0, duplicate.writes)
        val unknown = Memory().apply { bytes = "{\"version\":1,\"revision\":0,\"entries\":[],\"unknown\":true}".toByteArray() }
        assertEquals("schedule_invalid_storage", runCatching { store(unknown).load() }.exceptionOrNull()?.message)
        assertEquals(0, unknown.writes)
        val exhausted = Memory().apply { bytes = Json { encodeDefaults = true }.encodeToString(OrbisScheduleSnapshot(revision = Int.MAX_VALUE)).toByteArray() }
        assertEquals("schedule_revision_exhausted", runCatching { store(exhausted).create(Int.MAX_VALUE, draft()) }.exceptionOrNull()?.message)
        assertEquals(0, exhausted.writes)
    }

    @Test fun `oversized disk is refused and invalid drafts do not perform writes`() = runBlocking {
        val disk = Memory().apply { bytes = ByteArray(ORBIS_SCHEDULE_MAX_BYTES + 1) }
        assertNotNull(runCatching { store(disk).load() }.exceptionOrNull())
        val empty = Memory()
        assertNotNull(runCatching { store(empty).create(0, draft("")) }.exceptionOrNull())
        assertEquals(0, empty.writes)
    }
}
