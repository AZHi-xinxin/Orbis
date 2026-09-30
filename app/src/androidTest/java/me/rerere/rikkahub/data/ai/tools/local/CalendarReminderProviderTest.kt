package me.rerere.rikkahub.data.ai.tools.local

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.RemoteException
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** ContentResolver.wrap binds ONLY this unregistered, in-memory provider. Never uses a device calendar resolver. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CalendarReminderProviderTest {
    private lateinit var provider: MemoryCalendarProvider
    private lateinit var resolver: ContentResolver

    @Before fun setup() {
        provider = MemoryCalendarProvider()
        provider.attachInfo(InstrumentationRegistry.getInstrumentation().targetContext, ProviderInfo().apply {
            authority = CalendarContract.AUTHORITY
            exported = false
        })
        resolver = ContentResolver.wrap(provider)
    }

    @After fun teardown() { provider.database.close() }

    private fun request(reminder: Boolean, minutes: Int = 15) = parseCalendarCreate(Json.parseToJsonElement(
        """{"title":"ISOLATED IN-MEMORY TEST","start":"2030-01-02T10:00:00Z"${if (reminder) ",\"reminder_minutes\":$minutes" else ""}}"""), ZoneId.of("UTC"))
    private fun create(reminder: Boolean, minutes: Int = 15): JsonObject {
        val snapshot = readCalendarSnapshot(resolver, true)
        return writeCalendarEvent(request(reminder, minutes), snapshot, { readCalendarSnapshot(resolver, true) }, AndroidCalendarEventWriter(resolver))
    }

    @Test fun batchBackReferenceCreatesAndVerifiesExactlyOneReminder() {
        val result = create(true)
        assertEquals(true, result["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, provider.batches)
        assertEquals(2, provider.lastOperations.size)
        assertTrue(provider.lastOperations.all { !it.isYieldAllowed })
        assertEquals(1L, provider.count("events"))
        assertEquals(1L, provider.count("reminders"))
        val id = result["event_id"]!!.jsonPrimitive.long
        val row = readCalendarReminders(resolver, listOf(id))!![id]!!.single()
        assertEquals(id, row.eventId)
        assertEquals(15, row.minutes)
        assertEquals(CalendarContract.Reminders.METHOD_ALERT, row.method)
        assertEquals("unknown", result["sound_status"]!!.jsonPrimitive.content)
    }

    @Test fun omittedReminderCreatesNoReminderRows() {
        val result = create(false)
        assertEquals(true, result["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, provider.lastOperations.size)
        assertEquals(0L, provider.count("reminders"))
        assertEquals(false, result["reminder_persisted"]!!.jsonPrimitive.boolean)
    }

    @Test fun secondInsertFailureRollsBackTheSingleTransactionWithoutRetry() {
        provider.failReminderInsert = true
        val result = create(true)
        assertEquals(false, result["success"]!!.jsonPrimitive.boolean)
        assertEquals("unknown", result["write_state"]!!.jsonPrimitive.content)
        assertEquals(1, provider.batches)
        assertEquals(0L, provider.count("events"))
        assertEquals(0L, provider.count("reminders"))
    }

    @Test fun commitThenTransportFailureDoesNotCreateSecondEvent() {
        provider.failAfterCommit = true
        val result = create(true)
        assertEquals(false, result["success"]!!.jsonPrimitive.boolean)
        assertEquals(true, result["may_have_created"]!!.jsonPrimitive.boolean)
        assertEquals(false, result["safe_to_retry"]!!.jsonPrimitive.boolean)
        assertEquals(1, provider.batches)
        assertEquals(1L, provider.count("events"))
    }

    @Test fun unavailableReminderReadCannotBecomeNoReminderOrSuccess() {
        provider.failReminderRead = true
        val result = create(false)
        assertEquals(false, result["success"]!!.jsonPrimitive.boolean)
        assertEquals("created_verification_incomplete", result["write_state"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, result["reminder_persisted"])
        assertEquals(1, provider.batches)
    }

    @Test fun unsupportedReminderOrMissingPermissionNeverWrites() {
        provider.maxReminders = 0
        assertEquals("REMINDER_UNSUPPORTED", create(true)["error"]!!.jsonPrimitive.content)
        assertEquals(0, provider.batches)
        val reads = provider.reads
        assertFalse(readCalendarSnapshot(resolver, false).permissionGranted)
        assertEquals(reads, provider.reads)
        assertEquals(0L, provider.count("events"))
    }

    @Test fun nullOrThrowingInstancesQueryIsUnknownWhileEmptyCursorIsReallyEmpty() {
        provider.instanceReadMode = "null"
        assertNull(readCalendarEvents(resolver, 0, 1, null, 20, ZoneId.of("UTC")))
        provider.instanceReadMode = "throw"
        assertNull(readCalendarEvents(resolver, 0, 1, null, 20, ZoneId.of("UTC")))
        provider.instanceReadMode = "normal"
        val result = readCalendarEvents(resolver, 0, 1, null, 20, ZoneId.of("UTC"))!!
        assertEquals(0, result.events.size)
        assertFalse(result.hasMore)
        assertEquals(0, provider.batches)
    }

    @Test fun limitPlusOneReportsTruncationInsteadOfCompleteAbsence() {
        provider.instanceRows = 3
        val result = readCalendarEvents(resolver, 0, 1, null, 2, ZoneId.of("UTC"))!!
        assertEquals(2, result.events.size)
        assertTrue(result.hasMore)
        provider.instanceRows = 2
        assertFalse(readCalendarEvents(resolver, 0, 1, null, 2, ZoneId.of("UTC"))!!.hasMore)
    }

    @Test fun nullReminderFieldsCannotMasqueradeAsZeroMinuteDefault() {
        provider.allowedMethods = "0"
        provider.nullReminderValues = true
        val result = create(true, 0)
        assertEquals(false, result["success"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, result["reminder_persisted"])
        assertEquals(1, provider.batches)
    }

    private class MemoryCalendarProvider : ContentProvider() {
        val database = SQLiteDatabase.create(null)
        var failReminderInsert = false
        var failReminderRead = false
        var failAfterCommit = false
        var maxReminders = 5
        var allowedMethods = "0,1"
        var nullReminderValues = false
        var instanceReadMode = "normal"
        var instanceRows = 0
        var batches = 0
        var reads = 0
        var lastOperations = emptyList<ContentProviderOperation>()

        override fun onCreate(): Boolean {
            database.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY, calendar_id INTEGER, title TEXT, description TEXT, eventLocation TEXT, dtstart INTEGER, dtend INTEGER, eventTimezone TEXT, allDay INTEGER)")
            database.execSQL("CREATE TABLE reminders (_id INTEGER PRIMARY KEY, event_id INTEGER, minutes INTEGER, method INTEGER)")
            return true
        }

        fun count(table: String): Long = database.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val table = uri.pathSegments.first()
            require(table in setOf("events", "reminders"))
            if (table == "reminders" && failReminderInsert) throw OperationApplicationException("Synthetic second-operation failure")
            val actualValues = ContentValues(requireNotNull(values)).apply {
                if (table == "reminders" && nullReminderValues) { putNull("minutes"); putNull("method") }
            }
            val id = database.insertOrThrow(table, null, actualValues)
            return ContentUris.withAppendedId(uri, id)
        }

        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batches++
            lastOperations = operations.toList()
            database.beginTransaction()
            val result = try {
                super.applyBatch(operations).also { database.setTransactionSuccessful() }
            } finally { database.endTransaction() }
            if (failAfterCommit) throw RemoteException("Synthetic uncertain dispatch")
            return result
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            reads++
            val table = uri.pathSegments.first()
            if (table == "instances") {
                if (instanceReadMode == "null") return null
                if (instanceReadMode == "throw") throw IllegalStateException("Synthetic query failure")
                return MatrixCursor(projection!!).apply {
                    repeat(instanceRows) { addRow(arrayOf<Any>(it + 1L, "Synthetic event", "", "", 1L, 2L, 0, "Synthetic calendar", 42L)) }
                }
            }
            if (table == "calendars") {
                val values = mapOf<String, Any>("_id" to 42L, "calendar_displayName" to "Synthetic calendar", "account_name" to "test-account",
                    "account_type" to "LOCAL", "ownerAccount" to "test-owner", "calendar_access_level" to 500,
                    "sync_events" to 1, "maxReminders" to maxReminders, "allowedReminders" to allowedMethods)
                return MatrixCursor(projection!!).apply { addRow(projection.map { values[it] }) }
            }
            require(table in setOf("events", "reminders"))
            if (table == "reminders" && failReminderRead) return null
            val where = if (uri.pathSegments.size > 1) "_id = ?" else selection
            val args = if (uri.pathSegments.size > 1) arrayOf(uri.lastPathSegment!!) else selectionArgs
            return database.query(table, projection, where, args, null, null, sortOrder)
        }

        override fun getType(uri: Uri): String = "vnd.android.cursor.dir/test-calendar"
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Tests must not delete through a resolver")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Tests must not update through a resolver")
    }
}
