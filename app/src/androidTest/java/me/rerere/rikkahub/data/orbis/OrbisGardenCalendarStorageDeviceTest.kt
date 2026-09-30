package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite in a random cache child; no Koin, network, service, model, or user database. */
@RunWith(AndroidJUnit4::class)
class OrbisGardenCalendarStorageDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val directory = Files.createTempDirectory(cache.toPath(), "garden-calendar-test-").toFile()
        val name = "garden-test-${UUID.randomUUID()}.db"

        private fun owned() {
            check(directory.canonicalFile == directory && directory.parentFile == cache)
            check(directory.name.startsWith("garden-calendar-test-") && directory.isDirectory)
            check(!Files.isSymbolicLink(directory.toPath()))
        }

        private fun path(request: String = name): File {
            owned()
            check(request == name)
            return File(directory, name).also {
                check(it.canonicalFile == it && it.parentFile == directory)
                check(!Files.isSymbolicLink(it.toPath()))
            }
        }

        private fun filesOwned() {
            owned()
            val allowed = setOf(name, "$name-wal", "$name-shm", "$name-journal")
            checkNotNull(directory.listFiles()).forEach {
                check(it.name in allowed && it.canonicalFile == it && it.parentFile == directory)
                check(it.isFile && !Files.isSymbolicLink(it.toPath()))
            }
        }

        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = path(name)
            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
            ): SQLiteDatabase {
                check(mode == MODE_PRIVATE)
                return SQLiteDatabase.openOrCreateDatabase(path(name), factory)
            }
            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                handler: DatabaseErrorHandler?,
            ): SQLiteDatabase {
                check(mode == MODE_PRIVATE)
                return SQLiteDatabase.openOrCreateDatabase(path(name).path, factory, handler)
            }
            override fun deleteDatabase(name: String): Boolean {
                filesOwned()
                return SQLiteDatabase.deleteDatabase(path(name))
            }
            override fun getFilesDir(): File = error("private files forbidden")
            override fun getNoBackupFilesDir(): File = error("private config forbidden")
            override fun getCacheDir(): File = error("further cache access forbidden")
            override fun getDir(name: String, mode: Int): File = error("directories forbidden")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("preferences forbidden")
            override fun startService(intent: Intent): ComponentName? = error("services forbidden")
            override fun startForegroundService(intent: Intent): ComponentName? = error("services forbidden")
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean =
                error("services forbidden")
            override fun startActivity(intent: Intent) { error("activities forbidden") }
            override fun sendBroadcast(intent: Intent) { error("broadcasts forbidden") }
            override fun sendBroadcast(intent: Intent, permission: String?) { error("broadcasts forbidden") }
        }

        val store = OrbisGardenStore(context, name)

        override fun close() {
            runBlocking { store.close() }
            filesOwned()
            check(context.deleteDatabase(name) || !path().exists())
            check(checkNotNull(directory.listFiles()).isEmpty())
            check(directory.delete())
        }
    }

    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun entry(
        seed: String,
        kind: OrbisGardenKind,
        createdAt: Long,
        updatedAt: Long = createdAt,
    ) = OrbisGardenEntry(
        id = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString(),
        kind = kind,
        title = "合成标题-$seed",
        body = "隔离测试正文-$seed",
        author = "测试者",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Test fun requestedZoneCanMoveTheSameCreationInstantAcrossYearBoundary() = runBlocking {
        Fixture().use { fixture ->
            val instant = millis("2026-01-01T00:30:00Z")
            val record = entry("cross-zone", OrbisGardenKind.DIARY, instant)
            assertEquals(1, fixture.store.importBackup(encodeGardenBackup(listOf(record))))

            val utcDay = LocalDate.of(2026, 1, 1)
            val laDay = LocalDate.of(2025, 12, 31)
            assertEquals(
                mapOf(utcDay to 1),
                fixture.store.monthDays(OrbisGardenKind.DIARY, YearMonth.of(2026, 1), ZoneId.of("UTC")),
            )
            assertEquals(
                mapOf(laDay to 1),
                fixture.store.monthDays(
                    OrbisGardenKind.DIARY,
                    YearMonth.of(2025, 12),
                    ZoneId.of("America/Los_Angeles"),
                ),
            )
            assertEquals(listOf(record), fixture.store.listOnDate(OrbisGardenKind.DIARY, utcDay, ZoneId.of("UTC")))
            assertEquals(
                listOf(record),
                fixture.store.listOnDate(
                    OrbisGardenKind.DIARY,
                    laDay,
                    ZoneId.of("America/Los_Angeles"),
                ),
            )
        }
    }

    @Test fun editingChangesUpdatedAtButNeverMovesCreationDay() = runBlocking {
        Fixture().use { fixture ->
            val created = millis("2000-01-10T12:00:00Z")
            val original = entry("edited", OrbisGardenKind.LETTER, created, created + 1_000)
            fixture.store.importBackup(encodeGardenBackup(listOf(original)))
            val edited = fixture.store.update(original, "编辑后标题", "编辑后正文", "测试者")

            assertEquals(created, edited.createdAt)
            assertNull(edited.entryDate)
            assertTrue(edited.updatedAt > original.updatedAt)
            val zone = ZoneId.of("UTC")
            val creationDay = LocalDate.of(2000, 1, 10)
            assertEquals(
                mapOf(creationDay to 1),
                fixture.store.monthDays(OrbisGardenKind.LETTER, YearMonth.of(2000, 1), zone),
            )
            assertTrue(
                fixture.store.monthDays(
                    OrbisGardenKind.LETTER,
                    YearMonth.of(2000, 2),
                    zone,
                ).isEmpty(),
            )
            assertEquals(listOf(edited), fixture.store.listOnDate(OrbisGardenKind.LETTER, creationDay, zone))
        }
    }

    @Test fun monthCountsAllRecordsAndDatePaginationKeepsExistingOrder() = runBlocking {
        Fixture().use { fixture ->
            val base = millis("2024-02-20T08:00:00Z")
            val sameDay = List(35) { index ->
                entry("same-$index", OrbisGardenKind.WISH, base + index * 1_000L, base + index * 10_000L)
            }
            val otherDay = entry(
                "other-day",
                OrbisGardenKind.WISH,
                millis("2024-02-21T08:00:00Z"),
            )
            val otherKind = entry("other-kind", OrbisGardenKind.DIARY, base)
            fixture.store.importBackup(encodeGardenBackup(sameDay + otherDay + otherKind))

            val zone = ZoneId.of("UTC")
            val day = LocalDate.of(2024, 2, 20)
            val counts = fixture.store.monthDays(OrbisGardenKind.WISH, YearMonth.of(2024, 2), zone)
            assertEquals(35, counts[day])
            assertEquals(1, counts[LocalDate.of(2024, 2, 21)])
            assertEquals(36, counts.values.sum())

            val first = fixture.store.listOnDate(OrbisGardenKind.WISH, day, zone)
            val second = fixture.store.listOnDate(OrbisGardenKind.WISH, day, zone, limit = 30, offset = 30)
            assertEquals(30, first.size)
            assertEquals(5, second.size)
            val expected = fixture.store.list(OrbisGardenKind.WISH, limit = 100)
                .filter { gardenCreatedDate(it.createdAt, zone) == day }
            assertEquals(expected, first + second)
            assertEquals(35, (first + second).map { it.id }.distinct().size)

            try {
                fixture.store.listOnDate(OrbisGardenKind.WISH, day, zone, limit = 0)
                fail("invalid limit")
            } catch (_: IllegalArgumentException) { }
            try {
                fixture.store.listOnDate(
                    OrbisGardenKind.WISH,
                    day,
                    zone,
                    offset = 1_000_001,
                )
                fail("invalid offset")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun chosenLeapDayCountsEveryKindAndKeepsCreationTimestampWhenEdited() = runBlocking {
        Fixture().use { fixture ->
            val day = LocalDate.of(2024, 2, 29)
            val zone = ZoneId.of("UTC")
            val beforeCreate = System.currentTimeMillis()
            val records = OrbisGardenKind.entries.map { kind ->
                fixture.store.create(kind, "合成${kind.label}", "指定日期的纯文本", "测试者", entryDate = day)
            }
            records.forEach { record ->
                assertEquals(day.toString(), record.entryDate)
                assertTrue(record.createdAt >= beforeCreate)
                assertEquals(listOf(record), fixture.store.listOnDate(record.kind, day, zone))
            }
            assertEquals(mapOf(day to 5), fixture.store.monthDays(null, YearMonth.of(2024, 2), zone))
            assertEquals(
                mapOf(day to 5),
                fixture.store.monthDays(null, YearMonth.of(2024, 2), ZoneId.of("America/Los_Angeles")),
            )
            assertTrue(fixture.store.listOnDate(null, day.plusDays(1), zone).isEmpty())
            val expected = fixture.store.list(null, limit = 100)
            val firstPage = fixture.store.listOnDate(null, day, zone, limit = 3)
            val secondPage = fixture.store.listOnDate(null, day, zone, limit = 3, offset = 3)
            assertEquals(expected, firstPage + secondPage)

            val song = records.single { it.kind == OrbisGardenKind.SONG }
            val edited = fixture.store.update(song, "编辑歌名", "歌曲文字链接 https://example.org/song", "测试者")
            assertEquals(song.createdAt, edited.createdAt)
            assertEquals("2024-02-29", edited.entryDate)
            assertEquals(song.revision + 1, edited.revision)
            assertEquals(listOf(edited), fixture.store.listOnDate(OrbisGardenKind.SONG, day, zone))
            assertEquals(mapOf(day to 5), fixture.store.monthDays(null, YearMonth.of(2024, 2), zone))
        }
    }

    @Test fun explicitDatesAndLegacyCreationDatesShareTheSameMonthSummary() = runBlocking {
        Fixture().use { fixture ->
            val zone = ZoneId.of("UTC")
            val day = LocalDate.of(2024, 2, 29)
            val legacy = entry("legacy-leapday", OrbisGardenKind.ANCHOR, millis("2024-02-29T12:00:00Z"))
            fixture.store.importBackup(encodeGardenBackup(listOf(legacy)))
            val explicit = fixture.store.create(OrbisGardenKind.DIARY, "补记", "没有改动创建时间", "测试者", day)
            fixture.store.create(OrbisGardenKind.LETTER, "三月信件", "只属于三月", "测试者", day.plusDays(1))
            assertEquals(mapOf(day to 2), fixture.store.monthDays(null, YearMonth.of(2024, 2), zone))
            assertEquals(mapOf(day to 1), fixture.store.monthDays(OrbisGardenKind.ANCHOR, YearMonth.of(2024, 2), zone))
            assertEquals(setOf(legacy, explicit), fixture.store.listOnDate(null, day, zone).toSet())
            assertEquals(mapOf(day.plusDays(1) to 1), fixture.store.monthDays(null, YearMonth.of(2024, 3), zone))
        }
    }
}
