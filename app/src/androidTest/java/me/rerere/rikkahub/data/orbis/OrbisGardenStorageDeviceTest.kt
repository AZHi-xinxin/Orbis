package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, random cache child only. Plain isolated Application; no Koin/network/service/model. */
@RunWith(AndroidJUnit4::class)
class OrbisGardenStorageDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val directory = Files.createTempDirectory(cache.toPath(), "garden-storage-test-").toFile()
        val name = "garden-test-${UUID.randomUUID()}.db"
        private fun owned() {
            check(directory.canonicalFile == directory && directory.parentFile == cache)
            check(directory.name.startsWith("garden-storage-test-") && directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        }
        fun path(request: String = name): File {
            owned(); check(request == name)
            return File(directory, name).also { check(it.canonicalFile == it && it.parentFile == directory && !Files.isSymbolicLink(it.toPath())) }
        }
        private fun filesOwned() {
            owned()
            val allowed = setOf(name, "$name-wal", "$name-shm", "$name-journal")
            checkNotNull(directory.listFiles()).forEach {
                check(it.name in allowed && it.canonicalFile == it && it.parentFile == directory && it.isFile && !Files.isSymbolicLink(it.toPath()))
            }
        }
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = path(name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
                check(mode == MODE_PRIVATE); return SQLiteDatabase.openOrCreateDatabase(path(name), factory)
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase {
                check(mode == MODE_PRIVATE); return SQLiteDatabase.openOrCreateDatabase(path(name).path, factory, handler)
            }
            override fun deleteDatabase(name: String): Boolean { filesOwned(); return SQLiteDatabase.deleteDatabase(path(name)) }
            override fun getFilesDir(): File = error("private files forbidden")
            override fun getNoBackupFilesDir(): File = error("private config forbidden")
            override fun getCacheDir(): File = error("further cache access forbidden")
            override fun getDir(name: String, mode: Int): File = error("directories forbidden")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("preferences forbidden")
            override fun startService(intent: Intent): ComponentName? = error("services forbidden")
            override fun startForegroundService(intent: Intent): ComponentName? = error("services forbidden")
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean = error("services forbidden")
            override fun startActivity(intent: Intent) { error("activities forbidden") }
            override fun sendBroadcast(intent: Intent) { error("broadcasts forbidden") }
            override fun sendBroadcast(intent: Intent, permission: String?) { error("broadcasts forbidden") }
        }
        var store = OrbisGardenStore(context, name)
        suspend fun reopen() { store.close(); store = OrbisGardenStore(context, name) }
        override fun close() {
            runBlocking { store.close() }
            filesOwned()
            check(context.deleteDatabase(name) || !path().exists())
            check(checkNotNull(directory.listFiles()).isEmpty()); check(directory.delete())
        }
    }
    @Test fun freshDatabaseIsEmptyAndEveryKindPersistsAfterReopen() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.store.list(null).isEmpty())
            val records = OrbisGardenKind.entries.map { f.store.create(it, "合成${it.label}", "仅沙盒正文", "伙伴") }
            f.reopen()
            records.forEach { assertEquals(it, f.store.read(it.id)); assertEquals(listOf(it), f.store.list(it.kind)) }
        }
    }
    @Test fun updateAndDeleteRequireExactPreviousRecord() = runBlocking {
        Fixture().use { f ->
            val original = f.store.create(OrbisGardenKind.DIARY, "标题", "第一版", "我")
            val next = f.store.update(original, "标题", "第二版", "我")
            try { f.store.update(original, "覆盖", "旧草稿", "我"); fail("stale update") } catch (_: IllegalStateException) { }
            try { f.store.delete(original); fail("stale delete") } catch (_: IllegalStateException) { }
            f.reopen(); assertEquals(next, f.store.read(original.id))
            f.store.delete(next); f.reopen(); assertNull(f.store.read(original.id))
        }
    }
    @Test fun legacyPayloadWithoutNewDefaultsCanStillBeUpdatedAndDeleted() = runBlocking {
        Fixture().use { f ->
            f.store.list(null)
            f.store.close()
            val firstId = UUID.randomUUID().toString()
            val secondId = UUID.randomUUID().toString()
            SQLiteDatabase.openDatabase(f.path().path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (id in listOf(firstId, secondId)) {
                    // Older records may omit default revision and differ in JSON whitespace.
                    val legacy = """{ "id":"$id", "kind":"DIARY", "title":"旧标题", "body":"旧正文", "author":"我", "createdAt":1, "updatedAt":2 }"""
                    db.execSQL(
                        "INSERT INTO entries (id,kind,updated_at,revision,payload) VALUES (?,?,?,?,?)",
                        arrayOf<Any>(id, "DIARY", 2L, 1, legacy),
                    )
                }
                assertEquals(1, db.version)
            }
            f.reopen()
            val first = requireNotNull(f.store.read(firstId))
            val second = requireNotNull(f.store.read(secondId))
            assertNull(first.entryDate)
            assertEquals(1, first.revision)
            val edited = f.store.update(first, "旧标题", "编辑旧正文", "我")
            assertNull(edited.entryDate)
            assertEquals(first.createdAt, edited.createdAt)
            assertEquals(2, edited.revision)
            try { f.store.update(first, "覆盖", "过期版本", "我"); fail("stale legacy update") } catch (_: IllegalStateException) { }
            f.store.delete(second)
            f.reopen()
            assertEquals(edited, f.store.read(firstId))
            assertNull(f.store.read(secondId))
        }
    }
    @Test fun backupRoundTripIsPortableAndIdenticalImportIsIdempotent() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            val entry = first.store.create(OrbisGardenKind.LETTER, "信", "引用 content://synthetic/reference\n完整原文", "自己")
            val bytes = first.store.exportBackup()
            assertEquals(1, second.store.importBackup(bytes)); assertEquals(0, second.store.importBackup(bytes))
            second.reopen(); assertEquals(entry, second.store.read(entry.id))
            assertEquals(entry, first.store.read(entry.id))
        } }
    }
    @Test fun conflictingImportRollsBackAllAdditionsAndKeepsExisting() = runBlocking {
        Fixture().use { f ->
            val old = f.store.create(OrbisGardenKind.ANCHOR, "锚", "原文", "我")
            val incoming = old.copy(id = UUID.randomUUID().toString())
            val bytes = encodeGardenBackup(listOf(incoming, old.copy(body = "冲突")))
            try { f.store.importBackup(bytes); fail("must reject all") } catch (_: IllegalArgumentException) { }
            f.reopen(); assertEquals(listOf(old), f.store.list(null)); assertNull(f.store.read(incoming.id))
        }
    }
    @Test fun dateAndSongRoundTripRemainIdempotentAndDateConflictRejectsWholeImport() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            val day = LocalDate.of(2024, 2, 29)
            val song = first.store.create(OrbisGardenKind.SONG, "合成歌名", "只存文字链接 https://example.org/song", "我", day)
            val bytes = first.store.exportBackup()
            assertTrue(bytes.toString(Charsets.UTF_8).contains("orbis-local-garden/2"))
            assertEquals(1, second.store.importBackup(bytes))
            assertEquals(0, second.store.importBackup(bytes))
            val addition = song.copy(id = UUID.randomUUID().toString(), kind = OrbisGardenKind.LETTER)
            val collision = song.copy(entryDate = "2024-03-01")
            try {
                second.store.importBackup(encodeGardenBackup(listOf(addition, collision)))
                fail("date conflict must reject all additions")
            } catch (_: IllegalArgumentException) { }
            second.reopen()
            assertEquals(listOf(song), second.store.list(null))
            assertNull(second.store.read(addition.id))
        } }
    }
    @Test fun paginationDoesNotDropRecordsAndInvalidRequestsDoNotWrite() = runBlocking {
        Fixture().use { f ->
            repeat(65) { f.store.create(OrbisGardenKind.WISH, "愿望$it", "正文", "我") }
            val pages = (0..2).flatMap { f.store.list(OrbisGardenKind.WISH, 30, it * 30) }
            assertEquals(65, pages.size); assertEquals(65, pages.map { it.id }.distinct().size)
            try { f.store.create(OrbisGardenKind.WISH, "非法", "中".repeat(11000), "我"); fail("too large") } catch (_: IllegalArgumentException) { }
            assertEquals(65, f.store.list(null, 100).size)
        }
    }
    @Test fun malformedImportDoesNotModifyDatabase() = runBlocking {
        Fixture().use { f ->
            val original = f.store.create(OrbisGardenKind.DIARY, "标题", "正文", "我")
            try { f.store.importBackup("{broken".toByteArray()); fail("invalid") } catch (_: Exception) { }
            f.reopen(); assertEquals(listOf(original), f.store.list(null))
        }
    }
    @Test fun newerSchemaFailsWithoutErasingOrDowngradingIt() = runBlocking {
        Fixture().use { f ->
            f.store.create(OrbisGardenKind.DIARY, "标题", "正文", "我"); f.store.close()
            SQLiteDatabase.openDatabase(f.path().path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 9 }
            val before = f.path().readBytes()
            try { f.store.list(null); fail("unknown schema") } catch (_: IllegalStateException) { }
            assertArrayEquals(before, f.path().readBytes())
        }
    }
    @Test fun corruptDatabaseIsPreservedRatherThanAutomaticallyRecreated() = runBlocking {
        Fixture().use { f ->
            f.store.close()
            val corrupt = "synthetic-non-sqlite-data-preserve-me".toByteArray()
            f.path().writeBytes(corrupt)
            try { f.store.list(null); fail("corrupt") } catch (_: Exception) { }
            assertArrayEquals(corrupt, f.path().readBytes())
        }
    }
}
