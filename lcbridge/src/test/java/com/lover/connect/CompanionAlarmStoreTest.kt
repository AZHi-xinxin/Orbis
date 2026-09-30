package com.lover.connect

import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionAlarmStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentReadAndMissingUpdateDoNotCreateDirectoryOrFile() {
        val missing = temporary.root.resolve("not-created")
        val store = CompanionAlarmStore(missing)
        assertTrue(store.records().isEmpty())
        assertNull(store.update(UUID.randomUUID().toString(), "cancelled", 20))
        assertFalse(missing.exists())
    }

    @Test fun freshInstancesReadTheSameDurableRecordsAndJsonFields() {
        val first = record(hour = 13, minute = 0)
        val second = record(hour = 14, minute = 45)
        CompanionAlarmStore(temporary.root).put(first)
        CompanionAlarmStore(temporary.root).put(second)
        assertEquals(listOf(first, second), CompanionAlarmStore(temporary.root).records())
        val json = second.toJson()
        assertEquals(second.id, json.getString("alarm_id"))
        assertEquals(second.triggerAt, json.getLong("trigger_at_ms"))
        assertEquals(second.createdAt, json.getLong("created_at_ms"))
        assertEquals(second.updatedAt, json.getLong("updated_at_ms"))
        assertEquals("UTC", json.getString("time_zone"))
        assertEquals("scheduled", json.getString("status"))
        assertTrue(json.isNull("detail"))
        assertEquals(listOf(CompanionAlarmStore.FILE_NAME), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun changingOlderGenerationDoesNotMoveItAfterTheNewerGeneration() {
        val store = CompanionAlarmStore(temporary.root)
        val old = record()
        val replacement = record()
        store.put(old)
        store.put(replacement)
        val superseded = store.update(old.id, "superseded", 30, "same_time_replaced")
        assertEquals(listOf(old.id, replacement.id), store.records().map { it.id })
        assertEquals("superseded", superseded!!.status)
        assertEquals("same_time_replaced", superseded.detail)
        assertEquals(replacement, store.records().last())
        store.put(replacement.copy(message = "updated message"))
        assertEquals(2, store.records().size)
        assertEquals("updated message", store.records().last().message)
    }

    @Test fun unknownIdUpdateLeavesExistingFileByteForByteUntouched() {
        val store = CompanionAlarmStore(temporary.root)
        store.put(record())
        val file = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        val before = file.readBytes()
        assertNull(store.update(UUID.randomUUID().toString(), "stopped", 99))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun atomicReplacementFailureKeepsOriginalAndCleansOnlyItsTemporaryFile() {
        val old = record()
        CompanionAlarmStore(temporary.root).put(old)
        val file = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        val before = file.readBytes()
        var reachedAtomicReplace = false
        val failing = CompanionAlarmStore(temporary.root) { staged, destination ->
            reachedAtomicReplace = true
            assertTrue(staged.toFile().isFile)
            assertEquals(file.toPath(), destination)
            assertArrayEquals(before, destination.toFile().readBytes())
            throw IOException("synthetic atomic replacement failure")
        }
        expectFailure { failing.put(record()) }
        assertTrue(reachedAtomicReplace)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(old), CompanionAlarmStore(temporary.root).records())
        assertEquals(listOf(CompanionAlarmStore.FILE_NAME), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun corruptExistingFileIsNeitherReadAsEmptyNorOverwritten() {
        val file = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        file.writeText("broken alarm ledger")
        val before = file.readBytes()
        val store = CompanionAlarmStore(temporary.root)
        expectFailure { store.records() }
        expectFailure { store.put(record()) }
        expectFailure { store.update(UUID.randomUUID().toString(), "cancelled", 40) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun unsupportedOrMalformedRecordsFailClosed() {
        val valid = record().toJson()
        val cases = listOf(
            JSONObject().put("version", 2).put("records", JSONArray()),
            JSONObject().put("version", 1).put("records", JSONArray().put(JSONObject(valid.toString()).put("status", "unrecognized"))),
            JSONObject().put("version", 1).put("records", JSONArray().put(JSONObject(valid.toString()).put("hour", "13"))),
            JSONObject().put("version", 1).put("records", JSONArray().put(valid).put(valid)),
            JSONObject().put("version", 1).put("records", JSONArray().put(JSONObject(valid.toString()).put("detail", "exception contains private data"))),
        )
        val file = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        cases.map { it.toString() }.plus("${cases.first()} trailing data").forEach { malformed ->
            file.writeText(malformed)
            val before = file.readBytes()
            expectFailure { CompanionAlarmStore(temporary.root).put(record()) }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun malformedUtf8InOtherwiseValidJsonCannotBeSilentlyReplacedAndSaved() {
        val content = JSONObject().put("version", 1).put("records", JSONArray().put(record().toJson())).toString()
        val bytes = content.toByteArray(Charsets.UTF_8)
        bytes[content.indexOf("synthetic alarm")] = 0xff.toByte()
        val file = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        file.writeBytes(bytes)
        expectFailure { CompanionAlarmStore(temporary.root).records() }
        expectFailure { CompanionAlarmStore(temporary.root).put(record()) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun terminalHistoryIsBoundedButEveryActiveOrUncertainRecordSurvives() {
        val store = CompanionAlarmStore(temporary.root)
        val activeStatuses = listOf("scheduled", "scheduling", "schedule_unknown", "ringing", "cancel_unknown")
        val active = (0 until 260).map { record(status = activeStatuses[it % activeStatuses.size]) }
        val history = (0 until 260).map { record(status = if (it % 2 == 0) "fired" else "superseded") }
        // Seed a valid ledger to keep this retention test fast; the write under test is still atomic.
        temporary.root.resolve(CompanionAlarmStore.FILE_NAME).writeText(
            JSONObject().put("version", 1).put("records", JSONArray().apply {
                active.forEach { put(it.toJson()) }
                history.forEach { put(it.toJson()) }
            }).toString(),
        )
        val latest = record(status = "stopped")
        store.put(latest)
        val result = store.records()
        assertEquals(260 + CompanionAlarmStore.HISTORY_LIMIT + 1, result.size)
        assertEquals(active, result.take(active.size))
        assertEquals(history.takeLast(256) + latest, result.drop(active.size))
    }

    @Test fun latestCancelledHeadSurvivesHistoryPruningAndCannotRevealOldScheduledGeneration() {
        val oldScheduled = record(hour = 13, minute = 0)
        val cancelledHead = record(hour = 13, minute = 0, status = "cancelled")
        val otherHistory = (0 until 300).map { record(hour = 14, minute = 45, status = "fired") }
        temporary.root.resolve(CompanionAlarmStore.FILE_NAME).writeText(
            JSONObject().put("version", 1).put("records", JSONArray().apply {
                put(oldScheduled.toJson())
                put(cancelledHead.toJson())
                otherHistory.forEach { put(it.toJson()) }
            }).toString(),
        )
        val store = CompanionAlarmStore(temporary.root)
        val otherHead = record(hour = 14, minute = 45, status = "stopped")
        store.put(otherHead)
        val result = CompanionAlarmStore(temporary.root).records()
        assertEquals(1 + 2 + CompanionAlarmStore.HISTORY_LIMIT, result.size)
        assertEquals(oldScheduled, result.first())
        assertEquals(cancelledHead, result.last { it.hour == 13 && it.minute == 0 })
        assertEquals(otherHead, result.last { it.hour == 14 && it.minute == 45 })
        assertEquals(otherHistory.takeLast(256), result.filter { it.status == "fired" })
    }

    @Test fun invalidPutCannotDamageOldContentOrCreateStagingFiles() {
        val store = CompanionAlarmStore(temporary.root)
        val old = record()
        store.put(old)
        expectFailure { store.put(record(status = "unknown_new_status")) }
        expectFailure { store.put(record(hour = 24)) }
        expectFailure { store.put(record().copy(id = "not-a-uuid")) }
        assertEquals(listOf(old), store.records())
        assertEquals(listOf(CompanionAlarmStore.FILE_NAME), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun sharedLockSupportsAndroidOperationTransactionsWithoutDeadlocking() {
        val store = CompanionAlarmStore(temporary.root)
        synchronized(CompanionAlarmStore.LOCK) {
            val alarm = record(status = "scheduling")
            store.put(alarm)
            assertEquals(alarm, store.records().single())
            assertEquals("scheduled", store.update(alarm.id, "scheduled", 30)!!.status)
        }
    }

    private fun record(hour: Int = 13, minute: Int = 0, status: String = "scheduled") = CompanionAlarmRecord(
        id = UUID.randomUUID().toString(), hour = hour, minute = minute, message = "synthetic alarm",
        triggerAt = 100_000, timeZone = "UTC", createdAt = 10, updatedAt = 20, status = status,
    )

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected a fail-closed error") } catch (_: Exception) { }
    }
}
