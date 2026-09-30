package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

class CalendarReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val destination = CalendarDestination(42, "Test calendar", "account", "local", "owner", 500, true, 5, setOf(0, 1, 4))
    private val snapshot = CalendarAccessSnapshot(true, true, destination, zone.id)
    private fun args(extra: String = "") = Json.parseToJsonElement("""{"title":"Test only","start":"2030-01-02T10:00:00"$extra}""")
    private fun request(extra: String = "") = parseCalendarCreate(args(extra), zone)
    private class Writer : CalendarEventWriter {
        var writes = 0
        var error: Exception? = null
        var verifyError: Exception? = null
        var result = CalendarWriteIds(8, 9)
        var verification = CalendarWriteVerification(true, true)
        var method: Int? = null
        override fun insert(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?): CalendarWriteIds {
            writes++
            this.method = method
            error?.let { throw it }
            return result
        }
        override fun verify(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?, ids: CalendarWriteIds): CalendarWriteVerification {
            verifyError?.let { throw it }
            return verification
        }
    }
    private fun JsonObject.bool(key: String) = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull

    @Test fun omissionAndNullDoNotInventReminder() {
        assertNull(request().reminderMinutes)
        assertNull(request(",\"reminder_minutes\":null").reminderMinutes)
        val writer = Writer()
        val result = writeCalendarEvent(request(), snapshot, { snapshot }, writer)
        assertNull(writer.method)
        assertEquals(false, result.bool("reminder_requested"))
        assertEquals(false, result.bool("reminder_persisted"))
        assertEquals("not_requested", result.text("delivery_status"))
        assertTrue(result.text("message")!!.contains("未设置提醒"))
    }

    @Test fun zeroAndPositiveAreExplicitMinutes() {
        assertEquals(0, request(",\"reminder_minutes\":0").reminderMinutes)
        assertEquals(15, request(",\"reminder_minutes\":15").reminderMinutes)
    }

    @Test fun malformedReminderValuesAreRejected() {
        listOf("-1", "1.5", "\"15\"", "2147483648", "true", "[]", "{}").forEach { value ->
            try { request(",\"reminder_minutes\":$value"); fail("Accepted $value") }
            catch (e: CalendarInputException) { assertEquals("INVALID_REMINDER", e.code) }
        }
    }

    @Test fun malformedTitleAndBooleanDoNotBecomeSilentDefaults() {
        listOf("""{"title":8,"start":"2030-01-01"}""", """{"title":"x","start":"2030-01-01","all_day":"true"}""").forEach {
            try { parseCalendarCreate(Json.parseToJsonElement(it), zone); fail("Accepted invalid fields") }
            catch (_: CalendarInputException) { }
        }
    }

    @Test fun defaultTimedDurationAndOffsetArePreserved() {
        val parsed = parseCalendarCreate(Json.parseToJsonElement("""{"title":"x","start":"2030-01-02T02:00:00Z"}"""), zone)
        assertEquals(Instant.parse("2030-01-02T02:00:00Z").toEpochMilli(), parsed.startMillis)
        assertEquals(3_600_000L, parsed.endMillis - parsed.startMillis)
        assertTrue(parsed.startLabel.endsWith("+08:00[Asia/Shanghai]"))
    }

    @Test fun allDayUsesUtcDatesAndExclusiveEnd() {
        val parsed = request(",\"all_day\":true")
        assertEquals("UTC", parsed.eventTimeZone)
        assertEquals("2030-01-02", parsed.startLabel)
        assertEquals("2030-01-03", parsed.endLabel)
        assertEquals(86_400_000L, parsed.endMillis - parsed.startMillis)
        assertEquals(Instant.parse("2030-01-02T00:00:00Z").toEpochMilli(), parsed.startMillis)
    }

    @Test fun nonForwardRangeIsRejected() {
        try { request(",\"end\":\"2030-01-02T09:00:00\""); fail("Accepted reverse range") }
        catch (e: CalendarInputException) { assertEquals("INVALID_RANGE", e.code) }
    }

    @Test fun dstExplicitOffsetsRepresentDifferentInstants() {
        val ny = ZoneId.of("America/New_York")
        val first = parseCalendarTime("2030-11-03T01:30:00-04:00", ny)
        val second = parseCalendarTime("2030-11-03T01:30:00-05:00", ny)
        assertEquals(3_600_000L, second.toInstant().toEpochMilli() - first.toInstant().toEpochMilli())
    }

    @Test fun alertPreferredDefaultAllowedAlarmNotAccepted() {
        assertEquals(1, calendarReminderMethod(destination, 15))
        assertEquals(0, calendarReminderMethod(destination.copy(allowedReminders = setOf(0, 4)), 15))
        try { calendarReminderMethod(destination.copy(allowedReminders = setOf(4)), 15); fail("Accepted ALARM method") }
        catch (e: CalendarInputException) { assertEquals("REMINDER_UNSUPPORTED", e.code) }
    }

    @Test fun unsupportedOrUnknownCapabilitiesProduceNoWrite() {
        listOf(destination.copy(maxReminders = 0), destination.copy(maxReminders = null),
            destination.copy(allowedReminders = null), destination.copy(allowedReminders = emptySet())).forEach { calendar ->
            val expected = snapshot.copy(destination = calendar)
            val writer = Writer()
            val result = writeCalendarEvent(request(",\"reminder_minutes\":15"), expected, { expected }, writer)
            assertEquals(0, writer.writes)
            assertEquals("not_started", result.text("write_state"))
        }
    }

    @Test fun noReminderDoesNotRequireReminderCapability() {
        val expected = snapshot.copy(destination = destination.copy(maxReminders = null, allowedReminders = null))
        val writer = Writer()
        assertEquals(true, writeCalendarEvent(request(), expected, { expected }, writer).bool("success"))
        assertEquals(1, writer.writes)
    }

    @Test fun missingPermissionOrCalendarProducesNoWrite() {
        listOf(snapshot.copy(permissionGranted = false), snapshot.copy(readable = false), snapshot.copy(destination = null)).forEach { expected ->
            val writer = Writer()
            assertEquals("not_started", writeCalendarEvent(request(), expected, { expected }, writer).text("write_state"))
            assertEquals(0, writer.writes)
        }
    }

    @Test fun changedCalendarAccountCapabilitiesTimezoneOrPermissionInvalidateBeforeWrite() {
        listOf(snapshot.copy(destination = destination.copy(id = 43)),
            snapshot.copy(destination = destination.copy(accountName = "another")),
            snapshot.copy(destination = destination.copy(allowedReminders = setOf(0))),
            snapshot.copy(zoneId = "UTC"), snapshot.copy(permissionGranted = false)).forEach { current ->
            val writer = Writer()
            assertEquals("CALENDAR_CHANGED", writeCalendarEvent(request(), snapshot, { current }, writer).text("error"))
            assertEquals(0, writer.writes)
        }
    }

    @Test fun verifiedReminderNeverClaimsSoundGuaranteed() {
        val writer = Writer()
        val result = writeCalendarEvent(request(",\"reminder_minutes\":15"), snapshot, { snapshot }, writer)
        assertEquals(true, result.bool("success"))
        assertEquals(true, result.bool("reminder_persisted"))
        assertEquals("unknown", result.text("sound_status"))
        assertEquals("calendar_app_managed_unverified", result.text("delivery_status"))
        assertEquals(false, result.bool("safe_to_retry"))
        assertEquals(1, writer.writes)
    }

    @Test fun dispatchFailureIsUncertainAndNeverRetriedOrLeaksException() {
        val writer = Writer().apply { error = IllegalStateException("account-private-value") }
        val result = writeCalendarEvent(request(), snapshot, { snapshot }, writer)
        assertEquals("unknown", result.text("write_state"))
        assertEquals(JsonNull, result["event_created"])
        assertEquals(true, result.bool("may_have_created"))
        assertEquals(false, result.bool("safe_to_retry"))
        assertFalse(result.toString().contains("account-private-value"))
        assertEquals(1, writer.writes)
    }

    @Test fun readbackFailureKeepsReturnedEventIdAndDoesNotRetry() {
        val writer = Writer().apply { verifyError = IllegalStateException("unavailable") }
        val result = writeCalendarEvent(request(), snapshot, { snapshot }, writer)
        assertEquals(8L, result["event_id"]!!.jsonPrimitive.long)
        assertEquals("created_verification_incomplete", result.text("write_state"))
        assertEquals(JsonNull, result["reminder_persisted"])
        assertEquals(1, writer.writes)
    }

    @Test fun unexpectedRemindersAndIncompleteBatchCannotReportSuccess() {
        val unexpected = Writer().apply { verification = CalendarWriteVerification(true, false) }
        val result = writeCalendarEvent(request(), snapshot, { snapshot }, unexpected)
        assertEquals(false, result.bool("success"))
        assertEquals(true, result.bool("unexpected_reminders"))
        assertEquals(JsonNull, result["reminder_persisted"])
        val incomplete = Writer().apply { this.result = CalendarWriteIds(8, 9, false) }
        assertEquals(false, writeCalendarEvent(request(), snapshot, { snapshot }, incomplete).bool("success"))
    }

    @Test fun cancellationPropagatesWithoutRetry() {
        val writer = Writer().apply { error = CancellationException("stopped") }
        try { writeCalendarEvent(request(), snapshot, { snapshot }, writer); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, writer.writes) }
    }

    @Test fun cancellationAfterCapabilityReadStopsBeforeFirstWrite() {
        val writer = Writer()
        var checked = false
        try {
            writeCalendarEvent(request(), snapshot, { checked = true; snapshot }, writer,
                { assertTrue(checked); throw CancellationException("cancelled during preflight") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { assertEquals(0, writer.writes) }
    }

    @Test fun unavailableQueryDoesNotClaimZeroEvents() {
        val result = calendarQueryUnavailable()
        assertEquals(false, result.bool("success"))
        assertEquals("CALENDAR_QUERY_UNAVAILABLE", result.text("error"))
        assertEquals("unknown", result.text("query_state"))
        assertEquals(false, result.bool("safe_to_assume_no_events"))
        assertFalse(result.containsKey("events"))
        assertFalse(result.containsKey("count"))
    }

    @Test fun previewExplicitlyDistinguishesNoReminderAndZero() {
        assertTrue(calendarCreatePreview(args(), null).contains("不设置提醒"))
        assertTrue(calendarCreatePreview(args(",\"reminder_minutes\":0"), null).contains("开始时"))
        assertTrue(calendarCreatePreview(args(",\"reminder_minutes\":15"), null).contains("提前 15 分钟"))
        assertTrue(calendarCreatePreview(args(",\"reminder_minutes\":\"15\""), null).contains("参数无效"))
        assertEquals("尚未核实", calendarCreatePreview(args(), buildJsonObject { put("message", "尚未核实") }))
    }

    @Test fun approvalRevisionBindsSchemaAccountTargetCapabilitiesAndTimezone() {
        val original = calendarApprovalRevision(snapshot, "schema-v1")
        assertNotEquals(original, calendarApprovalRevision(snapshot, "schema-v2"))
        listOf(snapshot.copy(destination = destination.copy(id = 43)),
            snapshot.copy(destination = destination.copy(accountName = "other")),
            snapshot.copy(destination = destination.copy(allowedReminders = setOf(0))),
            snapshot.copy(zoneId = "UTC"), snapshot.copy(permissionGranted = false)).forEach {
            assertNotEquals(original, calendarApprovalRevision(it, "schema-v1"))
        }
        assertEquals(original, calendarApprovalRevision(snapshot.copy(destination = destination.copy(allowedReminders = setOf(4, 1, 0))), "schema-v1"))
        assertFalse(original.contains("account"))
    }
}
