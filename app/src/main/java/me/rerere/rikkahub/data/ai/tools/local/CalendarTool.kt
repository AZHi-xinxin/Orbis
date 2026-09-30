package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = """
        Query calendar events on the user's device within a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
        Returns event details, calendar ID, and stored reminders. Stored reminders do not prove that notifications sounded.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires calendar permission enabled in the assistant's local tools settings.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to the preset boundary (tomorrow for today)."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                            add("month")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 20.")
                })
            }
        )
    },
    execute = { args -> withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@withContext listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@withContext listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@withContext listOf(UIMessagePart.Text(payload.toString()))
        }

        val page = readCalendarEvents(context.contentResolver, startTime.toInstant().toEpochMilli(),
            endTime.toInstant().toEpochMilli(), query, limit, zone)
            ?: return@withContext listOf(UIMessagePart.Text(calendarQueryUnavailable().toString()))
        val rawEvents = page.events

        val reminders = readCalendarReminders(context.contentResolver,
            rawEvents.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.longOrNull }.distinct())
        val events = JsonArray(rawEvents.map { event ->
            val id = event.jsonObject["id"]!!.jsonPrimitive.longOrNull!!
            val rows = reminders?.get(id).orEmpty()
            JsonObject(event.jsonObject + buildJsonObject {
                put("reminder_state", if (reminders == null) "unknown" else if (rows.isEmpty()) "none" else "stored")
                if (reminders != null) put("reminders", JsonArray(rows.map { it.asJson() }))
                put("delivery_status", "unverified")
            })
        })
        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("success", true)
            put("query_state", if (page.hasMore) "truncated" else "complete")
            put("has_more", page.hasMore)
            put("message", if (page.hasMore) "结果已截断，请缩小时间范围或按标题核对；未出现在此列表不代表不存在。" else "仅表示本次时间范围和筛选条件内的日程；不证明其他范围不存在日程。")
            put("count", events.size)
            put("events", events)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    } }
)

internal fun buildCalendarCreateTool(context: Context): Tool {
    val snapshot = readCalendarSnapshot(context.contentResolver, hasCalendarWritePermission(context))
    val destinationLabel = snapshot.destination?.let { "${it.name} (#${it.id})" } ?: "暂无可用日历"
    val schema = calendarCreateSchema()
    return Tool(
        name = "calendar_create",
        description = """
            Create an event on the device calendar '$destinationLabel'.
            Requires title/start. End defaults to one hour later, or the next date for all-day events.
            Timezone without an explicit offset: '${snapshot.zoneId}'.
            reminder_minutes is OPTIONAL: omit it for NO reminder; 0 means at start, otherwise minutes before.
            Only set a reminder when the user requests one. Reminder storage does NOT guarantee notification or sound.
            Permission must be enabled in local tools settings. Target/capability changes require rebuilding tools.
            An uncertain write may already have created an event: query first; never automatically retry.
        """.trimIndent().replace("\n", " "),
        parameters = { schema },
        needsApproval = { true },
        hostApproval = HostToolApproval(
            stableId = "local:calendar_create",
            revision = calendarApprovalRevision(snapshot, schema.toString()),
            label = "创建日程 · $destinationLabel · ${snapshot.zoneId}",
            rememberable = snapshot.permissionGranted && snapshot.readable && snapshot.destination != null,
        ),
        isApprovalCurrent = {
            readCalendarSnapshot(context.contentResolver, hasCalendarWritePermission(context)) == snapshot
        },
        execute = { args ->
            withContext(Dispatchers.IO) {
                val payload = try {
                    val request = parseCalendarCreate(args, ZoneId.of(snapshot.zoneId))
                    val executionContext = currentCoroutineContext()
                    writeCalendarEvent(request, snapshot,
                        { readCalendarSnapshot(context.contentResolver, hasCalendarWritePermission(context)) },
                        AndroidCalendarEventWriter(context.contentResolver),
                        { executionContext.ensureActive() })
                } catch (e: CalendarInputException) {
                    calendarFailure(e.code, e.message)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    calendarFailure("INVALID_ARGUMENT", "Invalid calendar arguments; nothing was created.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )
}

internal fun calendarCreateSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        fun stringField(name: String, description: String) = put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
        })
        stringField("title", "Event title.")
        stringField("description", "Optional event notes.")
        stringField("location", "Optional location.")
        stringField("start", "ISO-8601 date, local/offset date-time, or epoch milliseconds as a string.")
        stringField("end", "Same format as start. Defaults to one hour later; all-day end date is exclusive, defaults to next date.")
        put("all_day", buildJsonObject {
            put("type", "boolean")
            put("description", "All-day event, default false. Actual all-day alert timing is calendar-app dependent.")
        })
        put("reminder_minutes", buildJsonObject {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", Int.MAX_VALUE)
            put("description", "Only when the user requests a reminder: non-negative minutes BEFORE start; 0 = at start. OMIT for no reminder. Storage does not guarantee sound; do not invent a default.")
        })
    },
    required = listOf("title", "start"),
)

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCalendarWritePermission(context: Context): Boolean =
    hasCalendarReadPermission(context) &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
