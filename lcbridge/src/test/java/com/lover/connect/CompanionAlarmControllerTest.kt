package com.lover.connect

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionAlarmControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test fun emptyQueryIsReadOnlyAndDoesNotCreateStorageOrInvokeAlarmWrites() {
        val missing = temporary.root.resolve("not-created")
        val gateway = FakeGateway()
        val result = controller(CompanionAlarmStore(missing), gateway).query()

        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("read_only"))
        assertEquals(0, result.getInt("active_count"))
        assertEquals(0, result.getInt("returned_count"))
        assertFalse(missing.exists())
        assertTrue(gateway.scheduled.isEmpty())
        assertTrue(gateway.cancelled.isEmpty())
        assertTrue(gateway.tokenChecks.isEmpty())
        assertEquals(1, gateway.healthCalls)
    }

    @Test fun pendingIntentExistenceIsReportedButNeverClaimedAsSystemQueueProof() {
        val store = CompanionAlarmStore(temporary.root)
        val record = record(triggerAt = now + 60_000)
        store.put(record)
        val ledger = temporary.root.resolve(CompanionAlarmStore.FILE_NAME)
        val before = ledger.readBytes()
        val gateway = FakeGateway(tokenIds = mutableSetOf(record.id))

        val result = controller(store, gateway).query(includeHistory = false)
        val alarm = result.getJSONArray("alarms").getJSONObject(0)

        assertEquals("scheduled_unverified", alarm.getString("state"))
        assertTrue(alarm.getBoolean("pending_token_exists"))
        assertTrue(alarm.getBoolean("enabled"))
        assertFalse(alarm.getBoolean("system_queue_verified"))
        assertTrue(result.getString("status_note").contains("不证明系统队列"))
        assertArrayEquals(before, ledger.readBytes())
        assertTrue(gateway.scheduled.isEmpty())
        assertTrue(gateway.cancelled.isEmpty())
        assertEquals(listOf(record.id), gateway.tokenChecks)
    }

    @Test fun missingPendingIntentIsNotPresentedAsAnEnabledAlarm() {
        val store = CompanionAlarmStore(temporary.root)
        store.put(record(triggerAt = now + 60_000))

        val alarm = controller(store, FakeGateway()).query(includeHistory = false)
            .getJSONArray("alarms").getJSONObject(0)

        assertEquals("registration_missing", alarm.getString("state"))
        assertFalse(alarm.getBoolean("pending_token_exists"))
        assertFalse(alarm.getBoolean("enabled"))
        assertFalse(alarm.getBoolean("system_queue_verified"))
    }

    @Test fun permissionBlockWinsEvenWhenPendingIntentTokenExists() {
        val store = CompanionAlarmStore(temporary.root)
        val record = record(triggerAt = now + 60_000)
        store.put(record)
        val gateway = FakeGateway(
            tokenIds = mutableSetOf(record.id),
            healthValue = JSONObject().put("can_schedule_exact", false),
        )

        val alarm = controller(store, gateway).query(includeHistory = false)
            .getJSONArray("alarms").getJSONObject(0)

        assertEquals("permission_blocked", alarm.getString("state"))
        assertTrue(alarm.getBoolean("pending_token_exists"))
        assertFalse(alarm.getBoolean("enabled"))
        assertFalse(alarm.getBoolean("system_queue_verified"))
    }

    @Test fun sameDayAndNextDayCalculationsDoNotReplaceDifferentWallClockSlots() {
        val testNow = Instant.parse("2026-09-24T13:05:00Z").toEpochMilli()
        val store = CompanionAlarmStore(temporary.root)
        val gateway = FakeGateway()
        val controller = CompanionAlarmController(store, gateway, { testNow }, { utc })

        val nextDay = controller.set(13, 0, "next day")
        val sameDay = controller.set(14, 45, "same day")

        assertEquals("明天", nextDay.getString("day"))
        assertEquals("今天", sameDay.getString("day"))
        assertEquals("2026-09-25", nextDay.getString("next_trigger_at").substring(0, 10))
        assertEquals("2026-09-24", sameDay.getString("next_trigger_at").substring(0, 10))
        assertFalse(nextDay.getBoolean("replaces_same_time"))
        assertFalse(sameDay.getBoolean("replaces_same_time"))
        assertEquals(setOf(13 to 0, 14 to 45), store.records().map { it.hour to it.minute }.toSet())
    }

    @Test fun paginationNextOffsetsCoverEveryRowExactlyOnce() {
        val store = CompanionAlarmStore(temporary.root)
        val expected = (0 until 5).map { index ->
            record(hour = 5 + index, triggerAt = now - 60_000 - index, status = "fired")
                .copy(updatedAt = now + index)
                .also(store::put)
        }
        val controller = controller(store, FakeGateway())

        val first = controller.query(limit = 2, offset = 0)
        val second = controller.query(limit = 2, offset = first.getInt("next_offset"))
        val third = controller.query(limit = 2, offset = second.getInt("next_offset"))
        val ids = listOf(first, second, third).flatMap { page ->
            val rows = page.getJSONArray("alarms")
            (0 until rows.length()).map { rows.getJSONObject(it).getString("alarm_id") }
        }

        assertEquals(2, first.getInt("next_offset"))
        assertEquals(4, second.getInt("next_offset"))
        assertTrue(third.isNull("next_offset"))
        assertFalse(third.getBoolean("has_more"))
        assertEquals(expected.map { it.id }.toSet(), ids.toSet())
        assertEquals(expected.size, ids.size)
    }

    @Test fun queryNeverRestoresAPlanButExplicitRestoreOnlyReschedulesFutureRecords() {
        val store = CompanionAlarmStore(temporary.root)
        val past = record(hour = 11, triggerAt = now - 1)
        val future = record(hour = 13, triggerAt = now + 60_000)
        store.put(past)
        store.put(future)
        val gateway = FakeGateway()
        val controller = controller(store, gateway)

        controller.query()
        assertTrue(gateway.scheduled.isEmpty())

        controller.restore()

        assertEquals(listOf(future.id), gateway.scheduled.map { it.id })
        val records = store.records().associateBy { it.id }
        assertEquals("missed_unconfirmed", records.getValue(past.id).status)
        assertEquals("past_due_on_restore", records.getValue(past.id).detail)
        assertEquals("scheduled", records.getValue(future.id).status)
        assertEquals("restored_future_plan", records.getValue(future.id).detail)
    }

    @Test fun failedFutureRestoreDoesNotClaimSuccessOrMoveTheAlarmToAnotherTime() {
        val store = CompanionAlarmStore(temporary.root)
        val future = record(triggerAt = now + 60_000)
        store.put(future)
        val gateway = FakeGateway(scheduleFailure = IOException("synthetic"))

        controller(store, gateway).restore()

        val after = store.records().single()
        assertEquals(future.triggerAt, after.triggerAt)
        assertEquals("scheduled", after.status)
        assertEquals("restore_not_confirmed", after.detail)
        assertEquals(listOf(future.id), gateway.scheduled.map { it.id })
    }

    @Test fun restoreDoesNotRetrySchedulingOrCancellationWhoseOutcomeIsUnknown() {
        val store = CompanionAlarmStore(temporary.root)
        val scheduling = record(hour = 13, triggerAt = now + 60_000, status = "scheduling")
        val cancelUnknown = record(hour = 14, triggerAt = now + 120_000, status = "cancel_unknown")
        store.put(scheduling)
        store.put(cancelUnknown)
        val gateway = FakeGateway()

        controller(store, gateway).restore()

        assertTrue(gateway.scheduled.isEmpty())
        assertTrue(gateway.cancelled.isEmpty())
        val records = store.records().associateBy { it.id }
        assertEquals("scheduling", records.getValue(scheduling.id).status)
        assertEquals("cancel_unknown", records.getValue(cancelUnknown.id).status)
    }

    @Test fun overduePlanIsNotRescheduledButItsActuallyDeliveredLateBroadcastCanBeRecorded() {
        val store = CompanionAlarmStore(temporary.root)
        val overdue = record(triggerAt = now - 1)
        store.put(overdue)
        val gateway = FakeGateway()
        val controller = controller(store, gateway)

        controller.restore()
        assertTrue(gateway.scheduled.isEmpty())
        assertEquals("missed_unconfirmed", store.records().single().status)

        val fired = controller.receive(overdue.id)
        assertNotNull(fired)
        assertEquals("fired", fired!!.status)
        assertEquals("receiver_received", fired.detail)
    }

    @Test fun persistedRingingWithoutLivePlaybackIsUnconfirmedOnReadAndStoppedOnRestore() {
        val store = CompanionAlarmStore(temporary.root)
        val ringing = record(triggerAt = now - 60_000, status = "ringing")
        store.put(ringing)
        val gateway = FakeGateway(healthValue = JSONObject().put("can_schedule_exact", true))
        val controller = controller(store, gateway)

        val queried = controller.query().getJSONArray("alarms").getJSONObject(0)
        assertEquals("playback_started_unconfirmed", queried.getString("state"))
        assertFalse(queried.getBoolean("enabled"))

        controller.restore()
        val stopped = store.records().single()
        assertEquals("stopped", stopped.status)
        assertEquals("playback_not_live_on_restore", stopped.detail)
        assertTrue(gateway.scheduled.isEmpty())
    }

    @Test fun cancelledGenerationRejectsALateBroadcast() {
        val store = CompanionAlarmStore(temporary.root)
        val alarm = record(triggerAt = now + 60_000)
        store.put(alarm)
        val gateway = FakeGateway(cancelResult = true)
        val controller = controller(store, gateway)

        val receipt = controller.cancel(alarm.hour, alarm.minute)
        val late = controller.receive(alarm.id)

        assertTrue(receipt.getBoolean("ok"))
        assertTrue(receipt.getBoolean("pending_token_found"))
        assertNull(late)
        assertEquals("cancelled", store.records().single().status)
        assertEquals(listOf(alarm.hour to alarm.minute), gateway.cancelled)
    }

    @Test fun cancellingAnUntrackedTimeDoesNotCreateAnAlarmLedger() {
        val missing = temporary.root.resolve("not-created-by-cancel")
        val gateway = FakeGateway(cancelResult = false)

        val receipt = controller(CompanionAlarmStore(missing), gateway).cancel(6, 45)

        assertTrue(receipt.getBoolean("ok"))
        assertFalse(receipt.getBoolean("tracked_record_found"))
        assertFalse(receipt.getBoolean("pending_token_found"))
        assertFalse(missing.exists())
        assertEquals(listOf(6 to 45), gateway.cancelled)
    }

    @Test fun replacementRejectsOldGenerationAndOnlyCurrentGenerationCanFire() {
        val store = CompanionAlarmStore(temporary.root)
        val gateway = FakeGateway()
        var mutableNow = now
        val controller = CompanionAlarmController(store, gateway, { mutableNow }, { utc })

        val first = controller.set(13, 30, "first").getJSONObject("alarm").getString("alarm_id")
        val second = controller.set(13, 30, "second").getJSONObject("alarm").getString("alarm_id")
        mutableNow = store.records().last().triggerAt

        assertNotEquals(first, second)
        assertNull(controller.receive(first))
        val fired = controller.receive(second)
        assertNotNull(fired)
        assertEquals(second, fired!!.id)
        assertEquals("fired", fired.status)
        val generations = store.records().associateBy { it.id }
        assertEquals("superseded", generations.getValue(first).status)
        assertEquals("fired", generations.getValue(second).status)
        assertEquals(2, gateway.scheduled.size)
    }

    @Test fun futureScheduleUnknownRejectsAnEarlyBroadcastFromAReusedPendingIntent() {
        val store = CompanionAlarmStore(temporary.root)
        val uncertain = record(triggerAt = now + 60_000, status = "schedule_unknown")
        store.put(uncertain)

        val received = controller(store, FakeGateway()).receive(uncertain.id)

        assertNull(received)
        assertEquals("schedule_unknown", store.records().single().status)
    }

    @Test fun scheduleExceptionReturnsUnknownAndPersistsAnUncertainReceipt() {
        val store = CompanionAlarmStore(temporary.root)
        val gateway = FakeGateway(scheduleFailure = IOException("synthetic"))

        val result = controller(store, gateway).set(13, 30, "test")

        assertFalse(result.getBoolean("ok"))
        assertEquals("unknown", result.getString("outcome"))
        assertEquals("alarm_schedule_unconfirmed", result.getJSONObject("error").getString("code"))
        assertEquals("schedule_unknown", store.records().single().status)
        assertEquals(1, gateway.scheduled.size)
    }

    @Test fun receiptPersistenceFailureIsNeverReturnedAsSuccess() {
        var writes = 0
        val store = CompanionAlarmStore(temporary.root) { staged, destination ->
            writes += 1
            if (writes == 2) throw IOException("synthetic receipt failure")
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
            Unit
        }
        val gateway = FakeGateway()

        val result = controller(store, gateway).set(13, 30, "test")

        assertFalse(result.getBoolean("ok"))
        assertEquals("unknown", result.getString("outcome"))
        assertEquals("alarm_receipt_not_saved", result.getJSONObject("error").getString("code"))
        assertEquals(1, gateway.scheduled.size)
        assertEquals("scheduling", CompanionAlarmStore(temporary.root).records().single().status)
        assertEquals(listOf(CompanionAlarmStore.FILE_NAME), temporary.root.listFiles()!!.map { it.name })
    }

    private fun controller(store: CompanionAlarmStore, gateway: FakeGateway) = CompanionAlarmController(
        store = store,
        gateway = gateway,
        clock = { now },
        zone = { utc },
    )

    private fun record(
        hour: Int = 13,
        minute: Int = 30,
        triggerAt: Long,
        status: String = "scheduled",
    ) = CompanionAlarmRecord(
        id = UUID.randomUUID().toString(),
        hour = hour,
        minute = minute,
        message = "synthetic alarm",
        triggerAt = triggerAt,
        timeZone = utc.id,
        createdAt = now - 10_000,
        updatedAt = now - 10_000,
        status = status,
    )

    private class FakeGateway(
        private val tokenIds: MutableSet<String> = mutableSetOf(),
        private val cancelResult: Boolean = false,
        private val scheduleFailure: Exception? = null,
        private val healthValue: JSONObject = JSONObject().put("can_schedule_exact", true),
    ) : CompanionAlarmGateway {
        val scheduled = mutableListOf<CompanionAlarmRecord>()
        val cancelled = mutableListOf<Pair<Int, Int>>()
        val tokenChecks = mutableListOf<String>()
        var healthCalls = 0

        override fun schedule(record: CompanionAlarmRecord) {
            scheduled += record
            scheduleFailure?.let { throw it }
            tokenIds += record.id
        }

        override fun cancel(hour: Int, minute: Int): Boolean {
            cancelled += hour to minute
            return cancelResult
        }

        override fun tokenExists(record: CompanionAlarmRecord): Boolean {
            tokenChecks += record.id
            return record.id in tokenIds
        }

        override fun health(): JSONObject {
            healthCalls += 1
            return JSONObject(healthValue.toString())
        }
    }
}
