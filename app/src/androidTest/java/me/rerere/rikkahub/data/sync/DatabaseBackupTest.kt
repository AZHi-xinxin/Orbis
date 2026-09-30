package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import android.content.ContextWrapper
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "database-backup-test-").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        SQLiteConfiguration.configure(context, SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY)),
        null,
        null,
    )

    private fun createWalDatabase(file: File): SQLiteDatabase = open(file).apply {
        enableWriteAheadLogging()
        query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT)")
        query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst()) }
        execSQL("INSERT INTO messages(text) VALUES ('committed only in WAL')")
        assertTrue(File(file.path + "-wal").length() > 0)
    }

    @Test fun legacyWalIsMergedWithoutShmAndWithoutChangingOriginalArchiveFiles() {
        val source = File(directory, "source")
        val staged = File(directory, "rikka_hub")
        createWalDatabase(source).use {
            source.copyTo(staged)
            File(source.path + "-wal").copyTo(File(staged.path + "-wal"))
        }
        DatabaseBackup.normalize(context, staged)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        open(staged).use {
            assertEquals("committed only in WAL", it.stringForQuery("SELECT text FROM messages", null))
        }
    }

    @Test fun snapshotContainsWalCommitsAndFtsTablesAndNeedsNoSidecars() {
        val source = File(directory, "source")
        val snapshot = File(directory, "snapshot")
        createWalDatabase(source).use {
            it.execSQL("CREATE VIRTUAL TABLE search USING fts5(text, tokenize='simple')")
            it.execSQL("INSERT INTO search(text) VALUES ('hello backup')")
            DatabaseBackup.createSnapshot(it, snapshot)
            // Subsequent writes must not appear in the already-created snapshot.
            it.execSQL("INSERT INTO messages(text) VALUES ('after snapshot')")
        }
        assertFalse(File(snapshot.path + "-wal").exists())
        assertFalse(File(snapshot.path + "-shm").exists())
        DatabaseBackup.normalize(context, snapshot)
        open(snapshot).use {
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM messages", null))
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM search WHERE search MATCH 'hello'", null))
        }
    }

    @Test fun corruptDatabaseIsRejected() {
        val file = File(directory, "invalid")
        file.writeText("not a SQLite database")
        var failed = false
        try {
            DatabaseBackup.normalize(context, file)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Corrupt backup must not be published", failed)
    }

    @Test fun consultationRedactionOnlyDropsExclusiveUploadsAndOwnedFavoritesFromSnapshot() {
        val files = File(directory, "files").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val source = File(directory, "source-redaction")
        val snapshot = File(directory, "redacted")
        fun encoded(vararg names: String): String = JsonInstant.encodeToString(listOf(
            UIMessage.user("").copy(parts = names.map { UIMessagePart.Image("file://${File(files, "upload/$it").absolutePath}") }),
        ))
        open(source).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("CREATE TABLE conversationentity(id TEXT PRIMARY KEY, consultation_binding TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE message_node(id TEXT PRIMARY KEY, conversation_id TEXT REFERENCES conversationentity(id) ON DELETE CASCADE, messages TEXT)")
            db.execSQL("CREATE TABLE orbis_compaction_backup_node(conversation_id TEXT REFERENCES conversationentity(id) ON DELETE CASCADE, messages TEXT)")
            db.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY, ref_key TEXT, ref_json TEXT, snapshot_json TEXT, meta_json TEXT)")
            db.execSQL("CREATE TABLE managed_files(relative_path TEXT, display_name TEXT)")
            db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts5(text, conversation_id UNINDEXED, tokenize='simple')")
            db.execSQL("INSERT INTO conversationentity VALUES('normal', '')")
            db.execSQL("INSERT INTO conversationentity VALUES('hidden', 'binding')")
            db.execSQL("INSERT INTO message_node VALUES('normal-node', 'normal', ?)", arrayOf(encoded("shared.png", "normal-only.png")))
            db.execSQL("INSERT INTO message_node VALUES('hidden-node', 'hidden', ?)", arrayOf(encoded("exclusive.png", "shared.png", "settings.png", "rollback-shared.png")))
            db.execSQL("INSERT INTO orbis_compaction_backup_node VALUES('normal', ?)", arrayOf(encoded("rollback-shared.png")))
            db.execSQL("INSERT INTO orbis_compaction_backup_node VALUES('hidden', ?)", arrayOf(encoded("hidden-rollback.png")))
            db.execSQL("INSERT INTO favorites VALUES('hidden-fav','node:hidden:hidden-node','{\"conversationId\":\"hidden\"}', '', 'secret consultation preview')")
            db.execSQL("INSERT INTO favorites VALUES('normal-fav','node:normal:normal-node','{\"conversationId\":\"normal\"}', '', 'normal preview')")
            for (name in listOf("exclusive.png", "shared.png", "normal-only.png", "settings.png", "rollback-shared.png", "hidden-rollback.png")) {
                File(files, "upload/$name").apply { parentFile!!.mkdirs(); writeText("synthetic file") }
                db.execSQL("INSERT INTO managed_files VALUES(?,?)", arrayOf("upload/$name", "synthetic display name"))
            }
            db.execSQL("INSERT INTO message_fts VALUES('normal body','normal')")
            db.execSQL("INSERT INTO message_fts VALUES('secret body','hidden')")
            DatabaseBackup.createSnapshot(db, snapshot)
            val excluded = DatabaseBackup.excludeConsultations(wrapped, snapshot, "{\"background\":\"file:///files/upload/settings.png\"}")
            assertEquals(setOf("upload/exclusive.png", "upload/hidden-rollback.png"), excluded)
            assertEquals(2L, db.longForQuery("SELECT count(*) FROM conversationentity", null))
            assertEquals(2L, db.longForQuery("SELECT count(*) FROM favorites", null))
            assertTrue(File(files, "upload/exclusive.png").isFile)
        }
        open(snapshot).use { db ->
            assertEquals(1L, db.longForQuery("SELECT count(*) FROM conversationentity", null))
            assertEquals("normal", db.stringForQuery("SELECT id FROM conversationentity", null))
            assertEquals(1L, db.longForQuery("SELECT count(*) FROM message_node", null))
            assertEquals("normal-fav", db.stringForQuery("SELECT id FROM favorites", null))
            assertEquals(4L, db.longForQuery("SELECT count(*) FROM managed_files", null))
            assertEquals(1L, db.longForQuery("SELECT count(*) FROM message_fts", null))
            assertEquals(1L, db.longForQuery("SELECT count(*) FROM orbis_compaction_backup_node", null))
        }
    }
}
