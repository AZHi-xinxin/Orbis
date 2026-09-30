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
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * Synthetic opt-in device pressure test, never a real ZIP or user database.
 * Run only with IsolatedGenerationLoopRunner and an explicit class allowlist.
 * This tests importer/Room/loading/saving, NOT Compose rendering or model context.
 */
@RunWith(AndroidJUnit4::class)
class DeepSeekLargeImportTest {
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
        val directory = Files.createTempDirectory(testCache.toPath(), "deepseek-synthetic-long-").toFile().also {
            check(it.canonicalFile == it && it.parentFile == testCache &&
                it.name.startsWith("deepseek-synthetic-long-") && !Files.isSymbolicLink(it.toPath())) {
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
                    directory.name.startsWith("deepseek-synthetic-long-"))
                check(directory.deleteRecursively()) { "Synthetic fixture cleanup failed" }
            }
        }
    }

    private companion object {
        const val COUNT = 8_400
        const val BIG_INDEX = 4_200
        const val SOURCE_ID = "synthetic-deepseek-long"
        const val LEAF = "n8399"
        const val UNCHOSEN = "SYNTHETIC_UNCHOSEN_BRANCH_MUST_NOT_ENTER_NATIVE_CHAT"
        const val STAMP = "2025-01-02T03:04:05.123456+00:00"
    }

    private fun body(index: Int): String = if (index == BIG_INDEX)
        "synthetic-large-start|" + "L".repeat(100_000) + "|synthetic-large-end"
    else "synthetic-message-$index|" + "data ".repeat(40) + "|end-$index\n"

    private fun sourceNode(id: String, parent: String?, children: List<String>, index: Int?, text: String = "") = buildJsonObject {
        put("id", id); put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("children", JsonArray(children.map(::JsonPrimitive)))
        put("message", if (index == null) JsonNull else buildJsonObject {
            put("model", "deepseek-synthetic"); put("inserted_at", STAMP)
            put("fragments", JsonArray(listOf(buildJsonObject {
                put("type", if (index % 2 == 0) "REQUEST" else "RESPONSE"); put("content", text)
            })))
        })
    }

    /** Stream synthetic source JSON so the fixture itself does not allocate a full source tree. */
    private fun createArchive(directory: File): File {
        val file = File(directory, "synthetic-only.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("conversations.json"))
            val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered()
            writer.append("[{\"id\":\"$SOURCE_ID\",\"title\":\"synthetic-long-history\",\"inserted_at\":\"$STAMP\",\"updated_at\":\"$STAMP\",\"current_node\":\"$LEAF\",\"mapping\":{")
            writer.append("\"root\":").append(sourceNode("root", null, listOf("n0"), null).toString())
            repeat(COUNT) { index ->
                val children = buildList {
                    if (index + 1 < COUNT) add("n${index + 1}")
                    if (index == BIG_INDEX - 1) add("unchosen")
                }
                writer.append(",\"n$index\":").append(sourceNode("n$index", if (index == 0) "root" else "n${index - 1}",
                    children, index, body(index)).toString())
            }
            writer.append(",\"unchosen\":").append(sourceNode("unchosen", "n${BIG_INDEX - 1}", emptyList(), BIG_INDEX, UNCHOSEN).toString())
            writer.append("}}]"); writer.flush(); zip.closeEntry()
        }
        return file
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun messageHash(conversation: Conversation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        conversation.messageNodes.forEach { node ->
            val encoded = JsonInstant.encodeToString(node.messages).toByteArray(Charsets.UTF_8)
            digest.update(encoded.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte()); digest.update(encoded)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertSelectedHistory(conversation: Conversation) {
        assertEquals(COUNT, conversation.messageNodes.size)
        var chars = 0L
        conversation.messageNodes.forEachIndexed { index, node ->
            assertEquals(0, node.selectIndex); assertEquals(1, node.messages.size)
            val message = node.messages.single()
            val text = message.toText()
            chars += text.length
            assertEquals(if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, message.role)
            assertEquals(sha(body(index)), sha(text))
            assertFalse(text.contains(UNCHOSEN))
            assertTrue(message.parts.all { it is UIMessagePart.Text })
            assertTrue(message.getTools().isEmpty()); assertNull(message.orbisEvent)
            assertEquals(123_456_000, message.createdAt.nanosecond)
        }
        assertTrue(chars >= 1_000_000)
        assertEquals(sha(body(0)), sha(conversation.messageNodes.first().currentMessage.toText()))
        assertEquals(sha(body(COUNT - 1)), sha(conversation.messageNodes.last().currentMessage.toText()))
        assertTrue(conversation.messageNodes[BIG_INDEX].currentMessage.toText().length in 100_000..101_000)
        assertNull(conversation.customSystemPrompt); assertNull(conversation.workspaceCwd)
    }

    @Test fun synthetic8400MessageImportLoadSaveAndDuplicateRoundtripKeepsEverySelectedMessage() = runBlocking {
        val started = System.nanoTime()
        Fixture().use { fixture ->
            val repository = fixture.repository
            val assistant = Uuid.random()
            val old = Conversation(assistantId = assistant, title = "synthetic-existing-kept",
                messageNodes = listOf(UIMessage.user("synthetic existing window unchanged").toMessageNode()))
            repository.insertConversation(old)
            assertEquals(":memory:", fixture.database.openHelper.writableDatabase.path)
            val oldHash = sha(JsonInstant.encodeToString(checkNotNull(repository.getConversationById(old.id))))
            val archive = createArchive(fixture.directory)
            val archiveHash = fileHash(archive)
            val preview = DeepSeekArchive.inspect(archive).conversations.single()
            assertEquals(COUNT + 2, preview.totalNodes)
            assertEquals(COUNT + 1, preview.messageCount)
            assertEquals(2, preview.branches.size)
            assertEquals(COUNT, preview.branches.single { it.leafId == LEAF }.messageCount)
            val importer = DeepSeekChatImporter(repository)
            val result = importer.import(archive, assistant, mapOf(SOURCE_ID to LEAF))
            assertEquals(1, result.imported); assertEquals(COUNT, result.messages); assertEquals(0, result.failed)
            val importedId = deepSeekImportId("conversation", SOURCE_ID, LEAF)
            val loaded = checkNotNull(repository.getConversationById(importedId))
            assertSelectedHistory(loaded)
            val beforeHash = messageHash(loaded)
            val roundtripped = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(loaded))
            repository.updateConversation(roundtripped)
            val reloaded = checkNotNull(repository.getConversationById(importedId))
            assertSelectedHistory(reloaded)
            assertEquals(beforeHash, messageHash(reloaded))
            repeat(2) {
                val duplicate = importer.import(archive, assistant, mapOf(SOURCE_ID to LEAF))
                assertEquals(0, duplicate.imported); assertEquals(1, duplicate.skipped)
                assertEquals(2, repository.countConversations())
                assertEquals(beforeHash, messageHash(checkNotNull(repository.getConversationById(importedId))))
            }
            assertEquals(oldHash, sha(JsonInstant.encodeToString(checkNotNull(repository.getConversationById(old.id)))))
            assertFalse(repository.existsConversationById(deepSeekImportId("conversation", SOURCE_ID, "unchosen")))
            fixture.database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT count(*) FROM message_node WHERE conversation_id = ?", arrayOf(importedId.toString()))).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(COUNT, cursor.getInt(0))
            }
            assertEquals(archiveHash, fileHash(archive))
            println("DEEPSEEK_SYNTHETIC_ROOM nodes=$COUNT chars_at_least=1000000 max_body_about=100000 " +
                "duplicate_runs=2 load_save_roundtrip=true existing_window_unchanged=true " +
                "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}; no_real_data_or_ui_rendering")
        }
    }
}
