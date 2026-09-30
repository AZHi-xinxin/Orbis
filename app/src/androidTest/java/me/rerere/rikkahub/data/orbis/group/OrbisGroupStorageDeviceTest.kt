package me.rerere.rikkahub.data.orbis.group

import android.app.Application
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real SQLite in one random cache child. No production DB, model, phone service, or private chat. */
@RunWith(AndroidJUnit4::class)
class OrbisGroupStorageDeviceTest {
    private class Database : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        // Instrumentation's APK context has no Application and can belong to another UID.
        // Borrow only the running target's cache parent, never its database/preferences/files.
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val directory = Files.createTempDirectory(cache.toPath(), "orbis-group-storage-test-").toFile()
        val name = "orbis-group-test-${Uuid.random()}.db"
        private fun requireOwnedDirectory() {
            check(directory.canonicalFile == directory && directory.parentFile == cache)
            check(directory.name.startsWith("orbis-group-storage-test-"))
            check(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
        }
        private fun databasePath(requested: String): File {
            requireOwnedDirectory()
            check(requested == name && name.matches(Regex("orbis-group-test-[0-9a-f-]{36}\\.db")))
            return File(directory, name).also {
                check(it.canonicalFile == it && it.parentFile == directory && !Files.isSymbolicLink(it.toPath()))
            }
        }
        private fun requireOnlyOwnedDatabaseFiles() {
            requireOwnedDirectory()
            val allowed = setOf(name, "$name-wal", "$name-shm", "$name-journal")
            checkNotNull(directory.listFiles()).forEach {
                check(it.name in allowed && it.canonicalFile == it && it.parentFile == directory &&
                    !Files.isSymbolicLink(it.toPath()) && it.isFile)
            }
        }
        val context: Context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = databasePath(name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
                check(mode == MODE_PRIVATE)
                return SQLiteDatabase.openOrCreateDatabase(databasePath(name), factory)
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                check(mode == MODE_PRIVATE)
                return SQLiteDatabase.openOrCreateDatabase(databasePath(name).absolutePath, factory, errorHandler)
            }
            override fun deleteDatabase(name: String): Boolean {
                val path = databasePath(name)
                requireOnlyOwnedDatabaseFiles()
                return SQLiteDatabase.deleteDatabase(path)
            }
            override fun getFilesDir(): File = throw AssertionError("Files forbidden in SQLite fixture")
            override fun getCacheDir(): File = throw AssertionError("Further cache access forbidden in SQLite fixture")
            override fun getNoBackupFilesDir(): File = throw AssertionError("Private storage forbidden in SQLite fixture")
            override fun getDir(name: String, mode: Int): File = throw AssertionError("Directories forbidden in SQLite fixture")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw AssertionError("Preferences forbidden")
            override fun startService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun startForegroundService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = throw AssertionError("Services forbidden")
            override fun sendBroadcast(intent: Intent) { throw AssertionError("Broadcasts forbidden") }
            override fun sendBroadcast(intent: Intent, receiverPermission: String?) { throw AssertionError("Broadcasts forbidden") }
            override fun startActivity(intent: Intent) { throw AssertionError("Activities forbidden") }
        }
        var store = AndroidOrbisGroupStorage(context, name)
        suspend fun reopen() { store.close(); store = AndroidOrbisGroupStorage(context, name) }
        override fun close() {
            runBlocking { store.close() }
            // Exact SQLite file + checked sidecars, then its empty random directory. Never recursive.
            requireOnlyOwnedDatabaseFiles()
            check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists())
            check(checkNotNull(directory.listFiles()).isEmpty())
            check(directory.delete())
        }
    }

    @Test fun fiveThousandRowsStayPagedAndPartialUpdatesKeepSequenceAfterReopen() = runBlocking {
        Database().use { fixture ->
            val room = OrbisGroupRoom(Uuid.random().toString(), "isolated-5000", createdAt = 1, updatedAt = 1)
            val round = GroupRound(Uuid.random().toString(), room.id, emptyList(), 1, GroupRoundStatus.FINISHED)
            fixture.store.commit(room, round)
            repeat(10) { chunk ->
                fixture.store.commit(messages = (1..500).map { item ->
                    val index = chunk * 500 + item
                    OrbisGroupMessage("message-$index", room.id, round.id, null, "人类", text = "text-$index",
                        createdAt = index.toLong(), updatedAt = index.toLong())
                })
            }
            val last = fixture.store.messages(room.id, 200)
            assertEquals(200, last.size)
            assertEquals("text-4801", last.first().text)
            assertEquals("text-5000", last.last().text)
            val originalSequence = last.last().sequence
            repeat(20) { step -> fixture.store.commit(messages = listOf(last.last().copy(text = "partial-$step",
                status = OrbisGroupMessageStatus.GENERATING, updatedAt = 6000L + step))) }
            fixture.reopen()
            assertEquals(5000L, fixture.store.countMessages(room.id))
            assertEquals(originalSequence, fixture.store.message(last.last().id)?.sequence)
            assertEquals("partial-19", fixture.store.message(last.last().id)?.text)
            val earlier = fixture.store.messages(room.id, 200, last.first().sequence)
            assertEquals("text-4601", earlier.first().text)
            assertEquals("text-4800", earlier.last().text)
            fixture.store.recoverInterrupted(7000)
            val recovered = fixture.store.message(last.last().id)!!
            assertEquals("partial-19", recovered.text)
            assertEquals(originalSequence, recovered.sequence)
            assertEquals(OrbisGroupMessageStatus.INTERRUPTED, recovered.status)
            assertEquals(OrbisGroupMessageStatus.COMPLETE, fixture.store.message("message-4999")?.status)
            fixture.reopen()
            assertEquals(recovered, fixture.store.message(last.last().id))
        }
    }

    @Test fun pendingAndStreamingRoundRecoversOnceWithoutLosingTextOrMemberBinding() = runBlocking {
        Database().use { fixture ->
            val member = OrbisGroupMember(Uuid.random().toString(), Uuid.random(), Uuid.random(), Uuid.random(), "隔离测试成员", joinedAt = 1)
            val room = OrbisGroupRoom(Uuid.random().toString(), "isolated-recovery", listOf(member), 1, 1)
            val round = GroupRound(Uuid.random().toString(), room.id, listOf(member.id), 1)
            val partial = OrbisGroupMessage("partial", room.id, round.id, member.id, member.name, member.modelId,
                "已安全写入半句", OrbisGroupMessageStatus.GENERATING, createdAt = 1, updatedAt = 2)
            val pending = partial.copy(id = "pending", text = "", status = OrbisGroupMessageStatus.QUEUED)
            fixture.store.commit(room, round, listOf(partial, pending))
            fixture.reopen()
            fixture.store.recoverInterrupted(10)
            val rows = fixture.store.messages(room.id, 200)
            assertEquals(listOf("已安全写入半句", ""), rows.map { it.text })
            assertTrue(rows.all { it.status == OrbisGroupMessageStatus.INTERRUPTED && it.errorReason == "interrupted" })
            assertEquals(GroupRoundStatus.INTERRUPTED, fixture.store.latestRound(room.id)?.status)
            assertEquals(member, fixture.store.room(room.id)?.members?.single())
            fixture.store.recoverInterrupted(20)
            assertEquals(rows, fixture.store.messages(room.id, 200))
            fixture.reopen()
            assertEquals(rows, fixture.store.messages(room.id, 200))
        }
    }

    @Test fun transactionRollsBackRoomRoundAndAllMessagesOnInvalidReference() = runBlocking {
        Database().use { fixture ->
            val room = OrbisGroupRoom(Uuid.random().toString(), "isolated-rollback", createdAt = 1, updatedAt = 1)
            val round = GroupRound(Uuid.random().toString(), room.id, emptyList(), 1)
            val valid = OrbisGroupMessage("valid", room.id, round.id, null, "human", text = "not-committed", createdAt = 1, updatedAt = 1)
            try {
                fixture.store.commit(room, round, listOf(valid, valid.copy(id = "invalid", roundId = "nonexistent-round")))
                fail("Expected foreign-key rejection")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { /* Expected rollback. */ }
            fixture.reopen()
            assertNull(fixture.store.room(room.id))
            assertNull(fixture.store.latestRound(room.id))
            assertEquals(0L, fixture.store.countMessages(room.id))
            assertNull(fixture.store.message("valid"))
        }
    }

    @Test fun crossRoomRoundReferenceCannotInsertOrReassignAMessage() = runBlocking {
        Database().use { fixture ->
            val first = OrbisGroupRoom(Uuid.random().toString(), "first", createdAt = 1, updatedAt = 1)
            val second = OrbisGroupRoom(Uuid.random().toString(), "second", createdAt = 1, updatedAt = 1)
            val firstRound = GroupRound(Uuid.random().toString(), first.id, emptyList(), 1)
            val secondRound = GroupRound(Uuid.random().toString(), second.id, emptyList(), 1)
            fixture.store.commit(first, firstRound)
            fixture.store.commit(second, secondRound)
            val valid = OrbisGroupMessage("own", second.id, secondRound.id, null, "human", text = "second-only", createdAt = 1, updatedAt = 1)
            try {
                fixture.store.commit(room = second.copy(title = "must-rollback"), messages = listOf(
                    valid, valid.copy(id = "cross-room", roundId = firstRound.id)))
                fail("Expected composite foreign-key rejection")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { /* Expected rollback. */ }
            fixture.reopen()
            assertEquals("second", fixture.store.room(second.id)?.title)
            assertEquals(0L, fixture.store.countMessages(first.id))
            assertEquals(0L, fixture.store.countMessages(second.id))
            assertNull(fixture.store.message("own"))
            fixture.store.commit(messages = listOf(valid))
            val saved = fixture.store.message("own")!!
            try {
                fixture.store.commit(messages = listOf(saved.copy(roomId = first.id, roundId = firstRound.id)))
                fail("Message identity must not be silently moved across rooms")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { /* Unique ID prevents reassignment. */ }
            assertEquals(saved, fixture.store.message("own"))
        }
    }
}
