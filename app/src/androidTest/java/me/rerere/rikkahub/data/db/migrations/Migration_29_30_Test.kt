package me.rerere.rikkahub.data.db.migrations

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic version-29 file only; never opens an application's real chat database. */
@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val legacyTables = listOf("ConversationEntity", "message_node", "orbis_compaction_event",
        "orbis_compaction_rollback", "orbis_compaction_backup_node")
    private val cacheTables = listOf("orbis_history_scope", "orbis_history_slot", "orbis_history_removed")

    private fun legacySnapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        legacyTables.associateWith { table ->
            db.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList {
                    add((0 until cursor.columnCount).map { cursor.getColumnName(it) })
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
            }
        }

    private fun assertEmptyCaches(db: SupportSQLiteDatabase) {
        cacheTables.forEach { table -> db.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst()); assertEquals("$table must not be backfilled", 0, it.getInt(0))
        } }
    }

    @Test fun migrationPreservesEveryLegacyFieldAndAddsEmptyForeignKeyBoundCaches() {
        val name = "orbis-synthetic-history-cache-migration-29-30"
        val branches = """[{"id":"synthetic-old-branch","parts":[{"type":"text","text":"synthetic original"}]},{"id":"synthetic-selected","parts":[{"type":"text","text":"synthetic selected"},{"type":"tool","toolName":"compact","output":[{"type":"text","text":"synthetic receipt"}]}]}]"""
        val summary = """[{"id":"synthetic-summary","orbisVoiceCallId":"synthetic-call","orbisVoiceCallKind":"turn","parts":[{"type":"text","text":"synthetic summary","metadata":{"orbis_compaction_summary":true}}]}]"""
        val before = helper.createDatabase(name, 29).use { db ->
            db.execSQL("""INSERT INTO ConversationEntity
                (id,assistant_id,title,nodes,create_at,update_at,suggestions,is_pinned,custom_system_prompt,
                 orbis_prompt,mode_injection_ids,lorebook_ids,workspace_cwd,folder_id,compaction_epoch,consultation_binding)
                VALUES ('synthetic-window','synthetic-assistant','synthetic title','[]',1000,2000,'["synthetic"]',1,
                        'synthetic prompt','{"synthetic":true}','["synthetic-mode"]','["synthetic-lore"]',
                        'synthetic/workspace','synthetic-folder',75,'')""")
            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES (?,?,?,?,?)",
                arrayOf<Any>("synthetic-summary-node", "synthetic-window", 0, summary, 0))
            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES (?,?,?,?,?)",
                arrayOf<Any>("synthetic-branch-node", "synthetic-window", 1, branches, 1))
            db.execSQL("""INSERT INTO orbis_compaction_event
                (id,conversation_id,assistant_id,window_title,created_at,before_tokens,after_tokens,before_basis,
                 after_basis,summary_hash,summary_message_id,kept_recent,status)
                VALUES ('synthetic-event','synthetic-window','synthetic-assistant','synthetic title',1500,12000,2000,
                        'synthetic-before','synthetic-after','synthetic-hash','synthetic-summary',1,'compacted')""")
            db.execSQL("""INSERT INTO orbis_compaction_rollback
                (conversation_id,event_id,summary_node_id,compacted_node_ids,compaction_epoch)
                VALUES ('synthetic-window','synthetic-event','synthetic-summary-node','["synthetic-branch-node"]',75)""")
            db.execSQL("INSERT INTO orbis_compaction_backup_node(conversation_id,node_id,node_index,messages,select_index) VALUES (?,?,?,?,?)",
                arrayOf<Any>("synthetic-window", "synthetic-archived-node", 0, branches, 1))
            legacySnapshot(db)
        }
        helper.runMigrationsAndValidate(name, 30, true).use { db ->
            assertEquals("All legacy columns, message JSON, branch selection and archive rows must survive", before, legacySnapshot(db))
            assertEmptyCaches(db)
            db.execSQL("PRAGMA foreign_keys=ON")
            db.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse("Migration must not introduce orphan rows", it.moveToFirst()) }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO orbis_history_scope(conversation_id,assistant_id,compaction_epoch) VALUES ('missing-window','synthetic-assistant',75)")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO orbis_history_slot(conversation_id,node_id,node_order) VALUES ('missing-scope','synthetic-node',0)")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("""INSERT INTO orbis_history_removed
                    (conversation_id,message_id,source_node_id,role,created_at,removed_at,chunk_index,chunk_count,body)
                    VALUES ('missing-scope','synthetic-message','synthetic-node','assistant','synthetic-time',2000,0,1,'synthetic body')""")
            }
            db.execSQL("INSERT INTO orbis_history_scope(conversation_id,assistant_id,compaction_epoch) VALUES ('synthetic-window','synthetic-assistant',75)")
            db.execSQL("INSERT INTO orbis_history_slot(conversation_id,node_id,node_order) VALUES ('synthetic-window','synthetic-node',0)")
            db.execSQL("""INSERT INTO orbis_history_removed
                (conversation_id,message_id,source_node_id,role,created_at,removed_at,chunk_index,chunk_count,body)
                VALUES ('synthetic-window','synthetic-message','synthetic-node','assistant','synthetic-time',2000,0,1,'synthetic body')""")
            db.execSQL("DELETE FROM orbis_history_scope WHERE conversation_id='synthetic-window'")
            assertEmptyCaches(db)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals("Cache FK operations must not mutate legacy chat/archive tables", before, legacySnapshot(db))
        }
    }
}
