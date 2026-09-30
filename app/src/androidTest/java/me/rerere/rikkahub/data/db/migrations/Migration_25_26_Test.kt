package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses its own synthetic database only. Never opens the user's conversation database. */
@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun originalMessagesAndPromptSurviveCompactionSchemaUpgrade() {
        val name = "orbis-synthetic-compaction-migration-25-26"
        val text = """[{"role":"USER","parts":[{"type":"text","text":"synthetic original"}]}]"""
        helper.createDatabase(name, 25).use { db ->
            db.execSQL("INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, orbis_prompt, workspace_cwd) VALUES ('synthetic-conversation', 'synthetic-ai', 'synthetic window', '[]', 1000, 2000, '[]', 1, 'keep prompt', '{" + "\"text\":\"synthetic draft\"" + "}', '/workspace')")
            db.execSQL("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>("synthetic-node", "synthetic-conversation", 0, text, 0))
        }
        helper.runMigrationsAndValidate(name, 26, true).use { db ->
            db.query("SELECT title, custom_system_prompt, orbis_prompt, workspace_cwd, compaction_epoch FROM ConversationEntity WHERE id='synthetic-conversation'").use {
                assertTrue(it.moveToFirst())
                assertEquals("synthetic window", it.getString(0))
                assertEquals("keep prompt", it.getString(1))
                assertEquals("{\"text\":\"synthetic draft\"}", it.getString(2))
                assertEquals("/workspace", it.getString(3))
                assertEquals(0L, it.getLong(4))
            }
            db.query("SELECT messages FROM message_node WHERE id='synthetic-node'").use {
                assertTrue(it.moveToFirst()); assertEquals(text, it.getString(0))
            }
            listOf("orbis_compaction_event", "orbis_compaction_rollback", "orbis_compaction_backup_node").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
            }
        }
    }
}
