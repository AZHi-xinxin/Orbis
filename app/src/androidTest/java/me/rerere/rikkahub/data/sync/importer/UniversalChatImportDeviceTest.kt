package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Synthetic universal JSON import through the real repository and an in-memory database.
 * Run only with IsolatedGenerationLoopRunner and an explicit class allowlist.
 * This tests importer/Room/loading/saving, NOT Compose rendering or model context.
 */
@RunWith(AndroidJUnit4::class)
class UniversalChatImportDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner) { "Isolated runner required" }
            check(it.targetContext.applicationContext.javaClass == Application::class.java) { "Plain Application required" }
            check(!LcExternalRecoveryGate.isAllowed()) { "External recovery must be disabled" }
        }
        // Instrumentation runs under the target UID; the test APK's cache may be absent/inaccessible.
        // Only this random child of target cache is used; persistent target stores remain forbidden.
        private val testCache = instrumentation.targetContext.cacheDir.canonicalFile.also {
            check(it.isDirectory || it.mkdirs()) { "Synthetic fixture cache cannot be created" }
            check(it.isDirectory && it.canWrite()) { "Synthetic fixture cache is not writable" }
        }
        val directory = Files.createTempDirectory(testCache.toPath(), "universal-synthetic-").toFile().also {
            check(it.canonicalFile == it && it.parentFile == testCache &&
                it.name.startsWith("universal-synthetic-") && !Files.isSymbolicLink(it.toPath())) {
                "Synthetic fixture must be a direct, non-linked cache child"
            }
            check(it.isDirectory && it.canWrite()) { "Synthetic fixture directory is not writable" }
        }
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(directory, "no_backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = throw AssertionError("Persistent databases forbidden in fixture")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw AssertionError("Preferences forbidden in fixture")
            override fun startService(service: Intent): ComponentName? = throw AssertionError("Services forbidden in fixture")
            override fun startForegroundService(service: Intent): ComponentName? = throw AssertionError("Services forbidden in fixture")
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
                throw AssertionError("Services forbidden in fixture")
            override fun sendBroadcast(intent: Intent) { throw AssertionError("Broadcasts forbidden in fixture") }
            override fun sendBroadcast(intent: Intent, receiverPermission: String?) { throw AssertionError("Broadcasts forbidden in fixture") }
            override fun startActivity(intent: Intent) { throw AssertionError("Activities forbidden in fixture") }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Same synthetic index table as ConversationLoadSafetyTest; no native tokenizer setup.
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database,
            FilesManager(context, FilesRepository(database.managedFileDao()), scope), MessageFtsManager(database))
        override fun close() {
            try { scope.cancel(); database.close() } finally {
                check(testCache.canonicalFile == testCache && directory.canonicalFile == directory &&
                    !Files.isSymbolicLink(directory.toPath()) && directory.parentFile?.canonicalFile == testCache &&
                    directory.name.startsWith("universal-synthetic-"))
                check(directory.deleteRecursively()) { "Synthetic fixture cleanup failed" }
            }
        }
    }

    @Test fun exampleAppendsThroughRoomReloadsAndDeduplicatesWithoutChangingExistingChat() = runBlocking {
        Fixture().use { fixture ->
            val owner = Uuid.parse("11111111-1111-4111-8111-111111111111")
            val file = File(fixture.directory, "example.json").apply { writeText(UniversalChatGuide.example) }
            val repo = fixture.repository
            val old = Conversation(assistantId = owner, title = "已有合成聊天", messageNodes = emptyList())
            assertEquals(1, repo.insertImportedConversations(listOf(old)))
            val before = JsonInstant.encodeToString(checkNotNull(repo.getConversationById(old.id)))
            val preview = UniversalChatArchive.inspect(file)
            val selected = preview.conversations.associate { it.sourceId to it.defaultLeafId }
            val importer = UniversalChatImporter(repo)
            assertEquals(1, importer.import(file, owner, selected, UniversalChatImporter.archiveFingerprint(file)).imported)
            val source = UniversalChatArchive.open(file).use { it.conversations().toList().single() }
            val id = UniversalChatImporter.convert(source, owner, java.time.Instant.EPOCH).id
            val loaded = checkNotNull(repo.getConversationById(id))
            assertEquals(2, loaded.messageNodes.size)
            assertEquals("今天想聊聊星星。", (loaded.messageNodes.first().messages.single().parts.single() as UIMessagePart.Text).text)
            val second = loaded.messageNodes.last().messages.single()
            assertTrue(second.parts.first() is UIMessagePart.Reasoning)
            assertEquals("好呀，你想从哪颗星说起？", (second.parts.last() as UIMessagePart.Text).text)
            val saved = JsonInstant.encodeToString(loaded)
            assertEquals(1, importer.import(file, owner, selected, UniversalChatImporter.archiveFingerprint(file)).skipped)
            assertEquals(saved, JsonInstant.encodeToString(checkNotNull(repo.getConversationById(id))))
            assertEquals(before, JsonInstant.encodeToString(checkNotNull(repo.getConversationById(old.id))))
        }
    }
}
