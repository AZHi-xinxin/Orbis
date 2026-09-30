package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.approval.approvalFingerprint
import java.time.*
import java.util.concurrent.CancellationException

/** No Android dependencies: parsing, approval scope, and uncertain-write handling are testable offline. */
internal data class CalendarDestination(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val owner: String,
    val accessLevel: Int,
    val syncEvents: Boolean,
    val maxReminders: Int?,
    val allowedReminders: Set<Int>?,
)

internal data class CalendarAccessSnapshot(
    val permissionGranted: Boolean,
    val readable: Boolean,
    val destination: CalendarDestination?,
    val zoneId: String,
)

internal fun calendarApprovalRevision(snapshot: CalendarAccessSnapshot, schema: String): String {
    val scope = buildJsonObject {
        put("permission", snapshot.permissionGranted)
        put("readable", snapshot.readable)
        put("zone", snapshot.zoneId)
        put("destination", snapshot.destination?.let { target -> buildJsonObject {
            put("id", target.id)
            put("name", target.name)
            put("account", target.accountName)
            put("type", target.accountType)
            put("owner", target.owner)
            put("access", target.accessLevel)
            put("sync", target.syncEvents)
            put("maximum", target.maxReminders?.let(::JsonPrimitive) ?: JsonNull)
            put("methods", target.allowedReminders?.sorted()?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        } } ?: JsonNull)
    }
    return approvalFingerprint("calendar-reminders-v1\n$scope\n$schema")
}

internal class CalendarInputException(val code: String, override val message: String) : IllegalArgumentException(message)

internal data class CalendarCreateRequest(
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val eventTimeZone: String,
    val startLabel: String,
    val endLabel: String,
    val allDay: Boolean,
    val reminderMinutes: Int?,
)

internal fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    throw CalendarInputException("INVALID_TIME", "Use an ISO-8601 date/date-time or epoch milliseconds.")
}

internal fun parseCalendarCreate(args: JsonElement, zone: ZoneId): CalendarCreateRequest {
    val params = args as? JsonObject
        ?: throw CalendarInputException("INVALID_ARGUMENT", "Arguments must be an object.")
    fun text(key: String, required: Boolean = false): String? {
        val value = params[key]
        if (value == null || value == JsonNull) {
            if (required) throw CalendarInputException("MISSING_REQUIRED", "$key is required.")
            return null
        }
        val result = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw CalendarInputException("INVALID_ARGUMENT", "$key must be a string.")
        if (required && result.isBlank()) throw CalendarInputException("MISSING_REQUIRED", "$key is required.")
        return result
    }
    val title = text("title", true)!!
    val allDay = when (val value = params["all_day"]) {
        null, JsonNull -> false
        else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw CalendarInputException("INVALID_ARGUMENT", "all_day must be a boolean.")
    }
    val reminder = when (val value = params["reminder_minutes"]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
            ?: throw CalendarInputException("INVALID_REMINDER", "reminder_minutes must be a non-negative integer; omit it for no reminder.")
    }
    val start = parseCalendarTime(text("start", true)!!, zone)
    val end = text("end")?.let { parseCalendarTime(it, zone) }
        ?: if (allDay) start.toLocalDate().plusDays(1).atStartOfDay(zone) else start.plusHours(1)
    if (!start.isBefore(end) || (allDay && !start.toLocalDate().isBefore(end.toLocalDate()))) {
        throw CalendarInputException("INVALID_RANGE", "end must be later than start; an all-day end date is exclusive.")
    }
    return CalendarCreateRequest(
        title, text("description").orEmpty(), text("location").orEmpty(),
        if (allDay) start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.toInstant().toEpochMilli(),
        if (allDay) end.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else end.toInstant().toEpochMilli(),
        if (allDay) "UTC" else zone.id,
        if (allDay) start.toLocalDate().toString() else start.withNano(0).toString(),
        if (allDay) end.toLocalDate().toString() else end.withNano(0).toString(),
        allDay, reminder,
    )
}

/** Android processes ALERT(1) and DEFAULT(0), not the misleadingly named ALARM(4). */
internal fun calendarReminderMethod(destination: CalendarDestination, minutes: Int?): Int? {
    if (minutes == null) return null
    val maximum = destination.maxReminders
        ?: throw CalendarInputException("REMINDER_CAPABILITY_UNKNOWN", "Cannot confirm this calendar's reminder capacity; nothing was created.")
    if (maximum < 1) throw CalendarInputException("REMINDER_UNSUPPORTED", "This calendar does not allow reminders; nothing was created.")
    val methods = destination.allowedReminders
        ?: throw CalendarInputException("REMINDER_CAPABILITY_UNKNOWN", "Cannot confirm supported reminder methods; nothing was created.")
    return when {
        1 in methods -> 1
        0 in methods -> 0
        else -> throw CalendarInputException("REMINDER_UNSUPPORTED", "This calendar does not support device alert/default reminders; nothing was created.")
    }
}

internal data class CalendarWriteIds(val eventId: Long?, val reminderId: Long?, val batchComplete: Boolean = true)
internal data class CalendarWriteVerification(val eventMatches: Boolean?, val reminderMatches: Boolean?)
internal interface CalendarEventWriter {
    /** Implementations dispatch once; exceptions after dispatch must be treated as uncertain, never retried. */
    fun insert(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?): CalendarWriteIds
    fun verify(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?, ids: CalendarWriteIds): CalendarWriteVerification
}

internal fun calendarFailure(code: String, message: String): JsonObject = buildJsonObject {
    put("success", false)
    put("error", code)
    put("write_state", "not_started")
    put("message", message)
}

internal data class CalendarEventQueryPage(val events: JsonArray, val hasMore: Boolean)

internal fun calendarQueryUnavailable(): JsonObject = buildJsonObject {
    put("success", false)
    put("error", "CALENDAR_QUERY_UNAVAILABLE")
    put("query_state", "unknown")
    put("safe_to_assume_no_events", false)
    put("message", "日历查询不可用，无法判断日程是否存在。这不是空日历；请稍后核对，不能据此重复创建。")
}

internal fun calendarCreatePreview(arguments: JsonElement, result: JsonElement?): String {
    val output = result as? JsonObject
    (output?.get("message") as? JsonPrimitive)?.contentOrNull?.let { return it }
    val args = arguments as? JsonObject ?: return "参数待核实；尚未创建日程。"
    fun label(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val reminder = args["reminder_minutes"]
    val reminderText = when {
        reminder == null || reminder == JsonNull -> "仅创建日程，不设置提醒"
        reminder is JsonPrimitive && !reminder.isString && reminder.intOrNull?.let { it >= 0 } == true ->
            if (reminder.intOrNull == 0) "开始时由日历应用提醒" else "提前 ${reminder.intOrNull} 分钟由日历应用提醒"
        else -> "提醒分钟参数无效，不能创建"
    }
    val end = label("end").ifBlank {
        if (label("all_day") == "true") "次日（全天结束日期不含当天）" else "开始后 1 小时"
    }
    return "开始：${label("start")}\n结束：$end\n$reminderText；尚未执行。实际弹窗及声音由日历应用设置决定。"
}

internal fun writeCalendarEvent(
    request: CalendarCreateRequest,
    expected: CalendarAccessSnapshot,
    readCurrent: () -> CalendarAccessSnapshot,
    writer: CalendarEventWriter,
    checkBeforeDispatch: () -> Unit = {},
): JsonObject {
    if (!expected.permissionGranted) return calendarFailure("NO_PERMISSION", "Enable calendar permission in local tools settings; nothing was created.")
    if (!expected.readable) return calendarFailure("CALENDAR_UNAVAILABLE", "Calendar selection could not be read; nothing was created.")
    val destination = expected.destination
        ?: return calendarFailure("NO_CALENDAR", "No writable calendar is available; nothing was created.")
    if (destination.accessLevel < 500 || !destination.syncEvents) {
        return calendarFailure("NO_WRITABLE_CALENDAR", "The selected calendar is not writable or enabled; nothing was created.")
    }
    val method = try { calendarReminderMethod(destination, request.reminderMinutes) }
    catch (e: CalendarInputException) { return calendarFailure(e.code, e.message) }
    if (runCatching(readCurrent).getOrNull() != expected) {
        return calendarFailure("CALENDAR_CHANGED", "Calendar, permissions, or timezone changed. Rebuild tools and approve again; nothing was created.")
    }
    var ids = CalendarWriteIds(null, null)
    var verification = CalendarWriteVerification(null, null)
    var submitted = false
    try {
        // Cancellation during a slow capability query must still stop before the first mutation.
        checkBeforeDispatch()
        submitted = true
        ids = writer.insert(request, destination, method)
        verification = writer.verify(request, destination, method, ids)
    } catch (e: CancellationException) {
        // The provider may already have committed. Do not retry or compensate by deleting by title.
        throw e
    } catch (_: Exception) {
        // No raw provider error is exposed: account data, titles, or URIs may occur in exceptions.
    }
    val verified = ids.batchComplete && verification.eventMatches == true && verification.reminderMatches == true
    return buildJsonObject {
        put("success", verified)
        put("write_state", if (verified) "verified" else if (ids.eventId != null) "created_verification_incomplete" else "unknown")
        if (!verified) put("error", "WRITE_RESULT_UNVERIFIED")
        put("event_created", if (ids.eventId != null) JsonPrimitive(true) else JsonNull)
        put("event_verified", verification.eventMatches?.let(::JsonPrimitive) ?: JsonNull)
        ids.eventId?.let { put("event_id", it) }
        ids.reminderId?.let { put("reminder_id", it) }
        put("may_have_created", submitted)
        put("safe_to_retry", false)
        put("calendar_id", destination.id)
        put("calendar", destination.name)
        put("title", request.title)
        put("start", request.startLabel)
        put("end", request.endLabel)
        put("all_day", request.allDay)
        put("location", request.location)
        put("reminder_requested", request.reminderMinutes != null)
        put("reminder_verified", verification.reminderMatches?.let(::JsonPrimitive) ?: JsonNull)
        put("reminder_persisted", if (request.reminderMinutes == null) {
            if (verification.reminderMatches == true) JsonPrimitive(false) else JsonNull
        } else verification.reminderMatches?.let(::JsonPrimitive) ?: JsonNull)
        put("unexpected_reminders", request.reminderMinutes == null && verification.reminderMatches == false)
        request.reminderMinutes?.let { put("reminder_minutes", it) }
        method?.let { put("reminder_method", it) }
        put("delivery_status", if (request.reminderMinutes != null) "calendar_app_managed_unverified" else "not_requested")
        put("sound_status", if (request.reminderMinutes != null) "unknown" else "not_requested")
        put("message", when {
            !verified -> "日历写入结果未能完整核实，可能已创建。请使用相同时间范围和标题查询核对；查询失败或结果截断不能证明不存在，不要自动重试或重复创建。"
            request.reminderMinutes == null -> "日程已创建并核实；本次未设置提醒。"
            else -> "日程及提前 ${request.reminderMinutes} 分钟提醒已写入并核实；是否弹窗或响铃仍取决于日历应用通知、声音与勿扰等设置，不能保证。全天或已过提醒时刻的处理也由日历应用决定。"
        })
    }
}
