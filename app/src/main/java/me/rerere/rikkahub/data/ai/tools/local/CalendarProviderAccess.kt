package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import kotlinx.serialization.json.*
import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/** null means unreadable, never a successful empty calendar. Fetch one extra row to expose truncation. */
internal fun readCalendarEvents(
    resolver: ContentResolver,
    startMillis: Long,
    endMillis: Long,
    titleQuery: String?,
    limit: Int,
    zone: ZoneId,
): CalendarEventQueryPage? {
    require(limit in 1..100)
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startMillis.toString()).appendPath(endMillis.toString()).build()
    val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
        CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.CALENDAR_ID)
    return try {
        resolver.query(uri, projection,
            if (titleQuery != null) "${CalendarContract.Instances.TITLE} LIKE ?" else null,
            titleQuery?.let { arrayOf("%$it%") }, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            val events = buildList {
                while (size <= limit && cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1).orEmpty())
                        put("description", cursor.getString(2).orEmpty())
                        put("location", cursor.getString(3).orEmpty())
                        val allDay = cursor.getInt(6) == 1
                        fun time(value: Long): String = if (allDay) {
                            Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        } else Instant.ofEpochMilli(value).atZone(zone).withNano(0).toString()
                        put("start", time(cursor.getLong(4)))
                        put("end", cursor.getLong(5).let { if (it > 0) time(it) else "" })
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7).orEmpty())
                        put("calendar_id", cursor.getLong(8))
                    })
                }
            }
            CalendarEventQueryPage(JsonArray(events.take(limit)), events.size > limit)
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}

internal fun readCalendarSnapshot(resolver: ContentResolver, permissionGranted: Boolean): CalendarAccessSnapshot {
    val zone = ZoneId.systemDefault().id
    if (!permissionGranted) return CalendarAccessSnapshot(false, false, null, zone)
    return try {
        val projection = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.SYNC_EVENTS, CalendarContract.Calendars.MAX_REMINDERS,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        fun read(primary: Boolean): CalendarDestination? {
            val cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI, projection,
                selection + if (primary) " AND ${CalendarContract.Calendars.IS_PRIMARY} = 1" else "",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.VISIBLE} DESC, ${CalendarContract.Calendars._ID} ASC")
                ?: error("Calendar capability query unavailable")
            return cursor.use {
                if (!it.moveToFirst()) null else CalendarDestination(
                    it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3).orEmpty(),
                    it.getString(4).orEmpty(), it.getInt(5), it.getInt(6) == 1,
                    if (it.isNull(7)) null else it.getInt(7),
                    if (it.isNull(8)) null else it.getString(8)?.split(',')?.mapNotNull { token -> token.trim().toIntOrNull() }?.toSortedSet(),
                )
            }
        }
        CalendarAccessSnapshot(true, true, read(true) ?: read(false), zone)
    } catch (_: Exception) {
        CalendarAccessSnapshot(true, false, null, zone)
    }
}

/** Non-yielding batch: AOSP CalendarProvider wraps it in a SQLite transaction. OEM behavior is verified, not assumed. */
internal fun calendarInsertOperations(
    request: CalendarCreateRequest,
    destination: CalendarDestination,
    method: Int?,
): ArrayList<ContentProviderOperation> = arrayListOf<ContentProviderOperation>().apply {
    add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
        .withValue(CalendarContract.Events.CALENDAR_ID, destination.id)
        .withValue(CalendarContract.Events.TITLE, request.title)
        .withValue(CalendarContract.Events.DESCRIPTION, request.description)
        .withValue(CalendarContract.Events.EVENT_LOCATION, request.location)
        .withValue(CalendarContract.Events.DTSTART, request.startMillis)
        .withValue(CalendarContract.Events.DTEND, request.endMillis)
        .withValue(CalendarContract.Events.EVENT_TIMEZONE, request.eventTimeZone)
        .withValue(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
        .withYieldAllowed(false)
        .build())
    if (request.reminderMinutes != null) {
        require(method == CalendarContract.Reminders.METHOD_ALERT || method == CalendarContract.Reminders.METHOD_DEFAULT)
        add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
            .withValue(CalendarContract.Reminders.MINUTES, request.reminderMinutes)
            .withValue(CalendarContract.Reminders.METHOD, method)
            .withYieldAllowed(false)
            .build())
    }
}

internal data class CalendarReminderRow(val id: Long, val eventId: Long, val minutes: Int, val method: Int) {
    fun asJson() = buildJsonObject {
        put("id", id)
        put("minutes", minutes)
        put("method", method)
        put("device_method", method == CalendarContract.Reminders.METHOD_ALERT || method == CalendarContract.Reminders.METHOD_DEFAULT)
    }
}

internal fun readCalendarReminders(resolver: ContentResolver, eventIds: List<Long>): Map<Long, List<CalendarReminderRow>>? {
    if (eventIds.isEmpty()) return emptyMap()
    // All IDs are provider-returned Longs; placeholders still avoid selection interpolation.
    return try {
        resolver.query(CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID, CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} IN (${eventIds.joinToString(",") { "?" }})",
            eventIds.map { it.toString() }.toTypedArray(), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check((0..3).none(cursor::isNull)) { "Incomplete reminder row" }
                    add(CalendarReminderRow(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)))
                }
            }.groupBy { it.eventId }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}

internal class AndroidCalendarEventWriter(private val resolver: ContentResolver) : CalendarEventWriter {
    override fun insert(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?): CalendarWriteIds {
        val operations = calendarInsertOperations(request, destination, method)
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        fun id(index: Int, path: String): Long? = results.getOrNull(index)?.uri?.let { uri ->
            if (uri.authority != CalendarContract.AUTHORITY || uri.pathSegments.firstOrNull() != path) null
            else runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 }
        }
        return CalendarWriteIds(id(0, "events"), id(1, "reminders"), results.size == operations.size)
    }

    override fun verify(request: CalendarCreateRequest, destination: CalendarDestination, method: Int?, ids: CalendarWriteIds): CalendarWriteVerification {
        val eventId = ids.eventId ?: return CalendarWriteVerification(null, null)
        val eventMatches = resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) == destination.id && cursor.getString(1).orEmpty() == request.title &&
                cursor.getString(2).orEmpty() == request.description && cursor.getString(3).orEmpty() == request.location &&
                cursor.getLong(4) == request.startMillis && cursor.getLong(5) == request.endMillis &&
                (cursor.getInt(6) == 1) == request.allDay && cursor.getString(7) == request.eventTimeZone
        }
        // A null query differs from an empty list; never call an unavailable read "no reminders".
        val reminderMap = readCalendarReminders(resolver, listOf(eventId))
        val reminderMatches = reminderMap?.let {
            val reminders = it[eventId].orEmpty()
            if (request.reminderMinutes == null) reminders.isEmpty()
            else reminders.size == 1 && reminders.single().let { row ->
                row.id == ids.reminderId && row.eventId == eventId && row.minutes == request.reminderMinutes && row.method == method
            }
        }
        return CalendarWriteVerification(eventMatches, reminderMatches)
    }
}
