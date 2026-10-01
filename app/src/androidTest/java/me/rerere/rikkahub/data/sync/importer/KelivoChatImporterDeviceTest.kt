package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Conversation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Synthetic source SQLite + memory sink only. Never opens Room, preferences, real backups or network. */
@RunWith(AndroidJUnit4::class)
class KelivoChatImporterDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val micros = 1_790_755_200_123_456L
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var writes = 0
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            writes++
            if (conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }
    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val base = instrumentation.targetContext
        assertEquals(Application::class.java, base.applicationContext.javaClass)
        directory = Files.createTempDirectory(base.cacheDir.toPath(), "kelivo-synthetic-test-").toFile()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
    }
    @After fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private data class Fixture(val archive: File, val database: File)
    private fun fixture(mutate: (SQLiteDatabase) -> Unit = {}): Fixture {
        val database = File(directory, "source-${Uuid.random()}.db")
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).close()
            db.version = 3
            db.execSQL("CREATE TABLE conversation_rows(id TEXT PRIMARY KEY,title TEXT,created_at INTEGER,updated_at INTEGER,version_selections_json TEXT)")
            db.execSQL("CREATE TABLE message_rows(id TEXT PRIMARY KEY,conversation_id TEXT,role TEXT,timestamp INTEGER,group_id TEXT,version INTEGER,message_order INTEGER,reasoning_segments_json TEXT)")
            db.execSQL("CREATE TABLE message_part_rows(conversation_id TEXT,revision_id TEXT,ordinal INTEGER,kind TEXT,payload TEXT)")
            db.execSQL("CREATE TABLE message_asset_rows(conversation_id TEXT,revision_id TEXT,asset_id TEXT,kind TEXT)")
            db.execSQL("CREATE TABLE preference_rows(secret TEXT)")
            db.execSQL("INSERT INTO preference_rows VALUES ('SYNTHETIC_SECRET_NEVER_READ')")
            db.execSQL("CREATE TABLE message_prompt_rows(payload TEXT)")
            db.execSQL("INSERT INTO message_prompt_rows VALUES ('SYNTHETIC_PROMPT_NEVER_READ')")
            db.execSQL("INSERT INTO conversation_rows VALUES ('c','synthetic title',?,?,?)", arrayOf<Any>(micros, micros, "{\"g\":1}"))
            db.execSQL("INSERT INTO message_rows VALUES ('u','c','user',?,NULL,0,0,NULL)", arrayOf(micros))
            db.execSQL("INSERT INTO message_rows VALUES ('a0','c','assistant',?,'g',0,1,NULL)", arrayOf(micros))
            db.execSQL("INSERT INTO message_rows VALUES ('a1','c','assistant',?,'g',1,2,NULL)", arrayOf(micros))
            db.execSQL("INSERT INTO message_part_rows VALUES ('c','u',0,'text','synthetic question')")
            db.execSQL("INSERT INTO message_part_rows VALUES ('c','a0',0,'text','UNSELECTED_ANSWER')")
            db.execSQL("INSERT INTO message_part_rows VALUES ('c','a1',0,'text','selected answer')")
            db.execSQL("INSERT INTO message_part_rows VALUES ('c','a1',1,'tool_call','historical call only')")
            db.execSQL("INSERT INTO message_part_rows VALUES ('c','a1',2,'image','file:///private/source/photo.jpg')")
            db.execSQL("INSERT INTO message_asset_rows VALUES ('c','a1','asset','image')")
            // Read-only source access must neither execute this trigger nor change source bytes.
            db.execSQL("CREATE TRIGGER source_write AFTER UPDATE ON message_part_rows BEGIN DELETE FROM preference_rows; END")
            mutate(db)
        }
        val manifest = buildJsonObject {
            put("format", "kelivo-backup"); put("formatVersion", 2); put("minimumReadableFormatVersion", 2)
            put("payloadKind", "sqlite"); put("includeChats", true); put("includeFiles", true); put("secretsIncluded", true)
            put("database", buildJsonObject { put("entry", "database/kelivo.db"); put("schemaVersion", 3)
                put("minimumReadableSchemaVersion", 2); put("conversationCount", 1); put("messageCount", 3) })
            put("entries", buildJsonObject { put("database/kelivo.db", buildJsonObject {
                put("bytes", database.length()); put("sha256", hash(database))
            }) })
        }.toString()
        val archive = File(directory, "archive-${Uuid.random()}.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json")); out.write(manifest.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("database/kelivo.db")); database.inputStream().use { it.copyTo(out) }; out.closeEntry()
            out.putNextEntry(ZipEntry("settings.json")); out.write("INVALID_JSON_SYNTHETIC_SECRET".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("skills/example/SKILL.md")); out.write("must not execute".toByteArray()); out.closeEntry()
        }
        return Fixture(archive, database)
    }

    @Test fun previewsThenAppendsSelectedChatOnceWithoutSettingsToolsOrMediaExecution() = runBlocking {
        val fixture = fixture()
        val before = hash(fixture.database)
        val archiveHash = hash(fixture.archive)
        val sink = Sink()
        val importer = KelivoChatImporter(context, sink)
        val preview = importer.inspect(fixture.archive)
        assertEquals(1, preview.conversations.size)
        assertEquals(2, preview.conversations.single().messageCount)
        assertEquals(1, preview.omittedAttachmentReferences)
        assertEquals(1, importer.importSelected(fixture.archive, assistant, setOf("c"), preview.fingerprint).imported)
        val conversation = sink.saved.values.single()
        assertEquals(assistant, conversation.assistantId)
        assertNull(conversation.customSystemPrompt); assertNull(conversation.workspaceCwd)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), conversation.currentMessages.map { it.role })
        val text = conversation.currentMessages.joinToString { it.toText() }
        assertTrue(text.contains("selected answer")); assertFalse(text.contains("UNSELECTED_ANSWER"))
        assertFalse(text.contains("SYNTHETIC_SECRET")); assertFalse(text.contains("SYNTHETIC_PROMPT"))
        assertFalse(text.contains("file:///"))
        assertTrue(conversation.currentMessages.all { it.getTools().isEmpty() && it.parts.all { p -> p is UIMessagePart.Text } })
        assertEquals(1, importer.importSelected(fixture.archive, Uuid.random(), setOf("c"), preview.fingerprint).skipped)
        assertEquals(1, sink.writes)
        assertEquals(before, hash(fixture.database)); assertEquals(archiveHash, hash(fixture.archive))
        assertTrue(File(context.cacheDir, ChatImportStaging.ROOT).listFiles()!!.isEmpty())
    }
    @Test fun changedArchiveAfterPreviewNeverWrites() = runBlocking {
        val first = fixture(); val second = fixture { it.execSQL("UPDATE message_part_rows SET payload='different' WHERE revision_id='a1' AND kind='text'") }
        val sink = Sink(); val importer = KelivoChatImporter(context, sink)
        val preview = importer.inspect(first.archive)
        try { importer.importSelected(second.archive, assistant, setOf("c"), preview.fingerprint); fail("changed archive") }
        catch (_: DeepSeekImportException) { assertEquals(0, sink.writes) }
    }
    @Test fun sourceViewIsRejectedBeforeQueryingItsProjection() = runBlocking {
        val f = fixture { db ->
            db.execSQL("ALTER TABLE message_part_rows RENAME TO hidden_parts")
            db.execSQL("CREATE VIEW message_part_rows AS SELECT * FROM hidden_parts")
        }
        assertRejected(f)
    }
    @Test fun unknownDatabaseSchemaFailsClosed() = runBlocking {
        assertRejected(fixture { it.version = 4 })
    }
    @Test fun missingSelectedVersionFailsBeforeAnyWrite() = runBlocking {
        assertRejected(fixture { it.execSQL("UPDATE conversation_rows SET version_selections_json='{\"g\":99}'") })
    }
    @Test fun unknownRoleAndWrongSqliteTypeFailClosed() = runBlocking {
        assertRejected(fixture { it.execSQL("UPDATE message_rows SET role='developer' WHERE id='a1'") })
        assertRejected(fixture { it.execSQL("UPDATE message_rows SET timestamp='invalid' WHERE id='a1'") })
    }
    @Test fun oversizedPartIsRejectedByAggregateBeforeCursorMaterialization() = runBlocking {
        assertRejected(fixture { it.execSQL("UPDATE message_part_rows SET payload=? WHERE revision_id='a1' AND kind='text'",
            arrayOf("x".repeat(600 * 1024))) })
    }
    @Test fun orphanAndDuplicatePartOrderingAreRejected() = runBlocking {
        assertRejected(fixture { it.execSQL("INSERT INTO message_part_rows VALUES ('c','missing',0,'text','orphan')") })
        assertRejected(fixture { it.execSQL("INSERT INTO message_part_rows VALUES ('c','a1',0,'text','duplicate')") })
    }
    private suspend fun assertRejected(f: Fixture) {
        val before = hash(f.database); val sink = Sink()
        val importer = KelivoChatImporter(context, sink)
        try { importer.inspect(f.archive); fail("unsafe archive must fail") } catch (_: IllegalArgumentException) { }
        try { importer.importSelected(f.archive, assistant, setOf("c"), hash(f.archive)); fail("must fail before write") }
        catch (_: DeepSeekImportException) { }
        assertEquals(0, sink.writes); assertEquals(before, hash(f.database))
        assertTrue(File(context.cacheDir, ChatImportStaging.ROOT).listFiles()!!.isEmpty())
    }
}
