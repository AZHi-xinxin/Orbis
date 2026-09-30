package me.rerere.rikkahub.data.repository

import android.app.Application
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Isolated in-memory Room with synthetic messages only. Never opens user files/databases. */
@RunWith(AndroidJUnit4::class)
class GeneratedSuggestionPersistenceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        // FrameworkSQLiteOpenHelper constructs a ProcessLock even for an in-memory DB.
        // Borrow only the target's cache parent, then expose one new, checked lock-only child.
        // The test APK context can have a different UID and cannot own this directory.
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val lockDirectory = Files.createTempDirectory(cache.toPath(), "orbis-suggestion-sqlite-lock-").toFile()
        private fun ownedLockDirectory(): File {
            check(cache.canonicalFile == cache && cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
            check(lockDirectory.canonicalFile == lockDirectory && lockDirectory.parentFile == cache)
            check(lockDirectory.name.startsWith("orbis-suggestion-sqlite-lock-"))
            check(!Files.isSymbolicLink(lockDirectory.toPath()) && lockDirectory.isDirectory)
            return lockDirectory
        }
        private fun deleteOwnedLockDirectory() {
            val directory = ownedLockDirectory()
            val locks = checkNotNull(directory.listFiles())
            locks.forEach { file ->
                check(file.name.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.lck")))
                check(file.canonicalFile == file && file.parentFile == directory &&
                    !Files.isSymbolicLink(file.toPath()) && file.isFile)
            }
            // Delete checked regular lock files only, then the empty exact directory. No recursion.
            locks.forEach { file ->
                check(ownedLockDirectory() == directory && file.canonicalFile == file &&
                    file.parentFile == directory && !Files.isSymbolicLink(file.toPath()) && file.isFile)
                check(file.delete())
            }
            check(checkNotNull(directory.listFiles()).isEmpty())
            check(ownedLockDirectory() == directory && directory.delete())
        }
        // Resource/system-service lookup is delegated; every other persistent store is rejected.
        private val context: Context = object : ContextWrapper(instrumentation.context) {
            private fun forbidden(): Nothing = throw AssertionError("Persistent access forbidden in suggestion fixture")
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = forbidden()
            override fun getCacheDir(): File = ownedLockDirectory()
            override fun getCodeCacheDir(): File = forbidden()
            override fun getNoBackupFilesDir(): File = forbidden()
            override fun getDir(name: String, mode: Int): File = forbidden()
            override fun getDatabasePath(name: String): File = forbidden()
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbidden()
            override fun openFileInput(name: String): FileInputStream = forbidden()
            override fun openFileOutput(name: String, mode: Int): FileOutputStream = forbidden()
            override fun getFileStreamPath(name: String): File = forbidden()
            override fun deleteFile(name: String): Boolean = forbidden()
            override fun fileList(): Array<String> = forbidden()
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = forbidden()
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?): SQLiteDatabase = forbidden()
            override fun deleteDatabase(name: String): Boolean = forbidden()
            override fun databaseList(): Array<String> = forbidden()
            override fun getContentResolver(): ContentResolver = forbidden()
            override fun startService(service: Intent): ComponentName? = forbidden()
            override fun startForegroundService(service: Intent): ComponentName? = forbidden()
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = forbidden()
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))

        fun forbidHistoryAndIndexWrites() {
            val sqlite = db.openHelper.writableDatabase
            for (table in listOf("message_node", "message_fts")) {
                for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                    sqlite.execSQL("CREATE TEMP TRIGGER guard_${table}_$operation BEFORE $operation ON $table " +
                        "BEGIN SELECT RAISE(ABORT, 'suggestions must not touch history or search'); END")
                }
            }
        }

        override fun close() {
            scope.cancel()
            db.close()
            check(!LcExternalRecoveryGate.isAllowed())
            deleteOwnedLockDirectory()
        }
    }

    private fun original(count: Int = 129) = Conversation(assistantId = Uuid.random(),
        title = "synthetic suggestions", messageNodes = List(count) {
            UIMessage.user("synthetic message $it").toMessageNode()
        })

    @Test fun onlySuggestionsChangeAndNoHistoryOrSearchWriteIsAttempted() = runBlocking {
        Fixture().use { fixture ->
            val expected = original()
            fixture.repository.insertConversation(expected)
            val before = fixture.db.conversationDao().getConversationById(expected.id.toString())!!
            val nodes = fixture.db.messageNodeDao().getNodesOfConversation(expected.id.toString())
            // Optional title/pin changes do not require rewriting a request's messages.
            fixture.db.conversationDao().updateTitle(expected.id.toString(), "new manual title")
            fixture.db.conversationDao().updatePinStatus(expected.id.toString(), true)
            fixture.forbidHistoryAndIndexWrites()
            assertTrue(fixture.repository.applyGeneratedSuggestionsIfUnchanged(expected, List(12) { "suggestion $it" }))
            val stored = fixture.db.conversationDao().getConversationById(expected.id.toString())!!
            assertEquals(before.copy(title = "new manual title", isPinned = true),
                stored.copy(chatSuggestions = before.chatSuggestions))
            assertEquals(nodes, fixture.db.messageNodeDao().getNodesOfConversation(expected.id.toString()))
            assertEquals(List(10) { "suggestion $it" }, fixture.repository.getConversationById(expected.id)!!.chatSuggestions)
        }
    }

    @Test fun exactSameEpochAndTimestampStillRejectAnyChangedHistoryPage() = runBlocking {
        for (kind in 0..5) Fixture().use { fixture ->
            val expected = original()
            fixture.repository.insertConversation(expected)
            val changed = when (kind) {
                0 -> expected.copy(messageNodes = expected.messageNodes + UIMessage.user("next turn").toMessageNode())
                1 -> expected.copy(messageNodes = expected.messageNodes.dropLast(1))
                2 -> expected.copy(messageNodes = expected.messageNodes.mapIndexed { index, node ->
                    if (index == 65) node.copy(messages = listOf(UIMessage.user("edited second-page history"))) else node
                })
                3 -> expected.copy(messageNodes = expected.messageNodes.mapIndexed { index, node ->
                    if (index == 0) node.copy(messages = node.messages + UIMessage.user("alternative"), selectIndex = 1) else node
                })
                4 -> expected.copy(messageNodes = expected.messageNodes.mapIndexed { index, node ->
                    if (index == 0) node.copy(messages = node.messages + UIMessage.user("unselected alternative")) else node
                })
                else -> expected.copy(messageNodes = expected.messageNodes.toMutableList().apply {
                    val first = this[0]; this[0] = this[1]; this[1] = first
                })
            }
            fixture.repository.updateConversation(changed)
            val before = fixture.db.conversationDao().getConversationById(expected.id.toString())
            val nodes = fixture.db.messageNodeDao().getNodesOfConversation(expected.id.toString())
            fixture.forbidHistoryAndIndexWrites()
            assertFalse("mutation $kind", fixture.repository.applyGeneratedSuggestionsIfUnchanged(expected, listOf("stale")))
            assertEquals(before, fixture.db.conversationDao().getConversationById(expected.id.toString()))
            assertEquals(nodes, fixture.db.messageNodeDao().getNodesOfConversation(expected.id.toString()))
        }
    }

    @Test fun ownerEpochAndTimestampChangesRejectTheOldRequest() = runBlocking {
        for (kind in 0..2) Fixture().use { fixture ->
            val expected = original(2)
            fixture.repository.insertConversation(expected)
            val dao = fixture.db.conversationDao()
            val row = dao.getConversationById(expected.id.toString())!!
            val changed = when (kind) {
                0 -> row.copy(assistantId = Uuid.random().toString())
                1 -> row.copy(compactionEpoch = row.compactionEpoch + 1)
                else -> row.copy(updateAt = row.updateAt + 1)
            }
            dao.update(changed)
            fixture.forbidHistoryAndIndexWrites()
            assertFalse(fixture.repository.applyGeneratedSuggestionsIfUnchanged(expected, listOf("stale")))
            assertEquals(changed, dao.getConversationById(expected.id.toString()))
        }
    }

    @Test fun deletedWindowCannotBeResurrectedAndOtherWindowIsUntouched() = runBlocking {
        Fixture().use { fixture ->
            val expected = original(2)
            val other = original(3)
            fixture.repository.insertConversation(expected)
            fixture.repository.insertConversation(other)
            fixture.db.conversationDao().deleteById(expected.id.toString())
            val otherBefore = fixture.db.conversationDao().getConversationById(other.id.toString())
            val otherNodes = fixture.db.messageNodeDao().getNodesOfConversation(other.id.toString())
            fixture.forbidHistoryAndIndexWrites()
            assertFalse(fixture.repository.applyGeneratedSuggestionsIfUnchanged(expected, listOf("stale")))
            assertNull(fixture.db.conversationDao().getConversationById(expected.id.toString()))
            assertEquals(otherBefore, fixture.db.conversationDao().getConversationById(other.id.toString()))
            assertEquals(otherNodes, fixture.db.messageNodeDao().getNodesOfConversation(other.id.toString()))
        }
    }

    @Test fun daoCasCannotBypassOwnerEpochOrGenerationTimestamp() = runBlocking {
        Fixture().use { fixture ->
            val expected = original(1)
            fixture.repository.insertConversation(expected)
            val dao = fixture.db.conversationDao()
            val row = dao.getConversationById(expected.id.toString())!!
            fixture.forbidHistoryAndIndexWrites()
            assertEquals(0, dao.updateSuggestionsIfUnchanged(row.id, Uuid.random().toString(), row.compactionEpoch, row.updateAt, "[]"))
            assertEquals(0, dao.updateSuggestionsIfUnchanged(row.id, row.assistantId, row.compactionEpoch + 1, row.updateAt, "[]"))
            assertEquals(0, dao.updateSuggestionsIfUnchanged(row.id, row.assistantId, row.compactionEpoch, row.updateAt + 1, "[]"))
            assertEquals(row, dao.getConversationById(row.id))
        }
    }
}
