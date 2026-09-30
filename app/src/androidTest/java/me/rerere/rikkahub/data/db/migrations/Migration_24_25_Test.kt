package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated synthetic database only; never opens the app's conversation or memory database. */
@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun oldConversationGainsDisabledPromptWhileHistoryRemainsIntact() {
        val databaseName = "orbis-prompt-migration-24-25"
        val conversationId = "00000000-0000-0000-0000-000000000101"
        val assistantId = "00000000-0000-0000-0000-000000000102"
        val messageJson = """[{"synthetic":"history and file://synthetic-attachment.png"}]"""
        helper.createDatabase(databaseName, 24).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(conversationId, assistantId, "synthetic title", "[]", 1000, 2000, "[]", 1, "existing prompt"),
            )
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>("00000000-0000-0000-0000-000000000103", conversationId, 0, messageJson, 0),
            )
        }
        helper.runMigrationsAndValidate(databaseName, 25, true).use { db ->
            db.query("SELECT assistant_id, title, custom_system_prompt, orbis_prompt, is_pinned, create_at, update_at FROM ConversationEntity WHERE id = ?", arrayOf(conversationId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(assistantId, cursor.getString(0))
                assertEquals("synthetic title", cursor.getString(1))
                assertEquals("existing prompt", cursor.getString(2))
                assertEquals("{}", cursor.getString(3))
                assertEquals(OrbisConversationPrompt(), JsonInstant.decodeFromString<OrbisConversationPrompt>(cursor.getString(3)))
                assertEquals(1, cursor.getInt(4))
                assertEquals(1000L, cursor.getLong(5))
                assertEquals(2000L, cursor.getLong(6))
            }
            db.query("SELECT messages, select_index FROM message_node WHERE conversation_id = ?", arrayOf(conversationId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(messageJson, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test fun newColumnAcceptsAStoredDraftWithoutChangingTheOriginalSystemPrompt() {
        val databaseName = "orbis-prompt-draft-migration-24-25"
        helper.createDatabase(databaseName, 24).close()
        helper.runMigrationsAndValidate(databaseName, 25, true).use { db ->
            val prompt = OrbisConversationPrompt("synthetic draft", false)
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, orbis_prompt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("synthetic-draft", "synthetic-assistant", "", "[]", 1000, 1000, "[]", 0, "original system prompt", JsonInstant.encodeToString(prompt)),
            )
            db.query("SELECT custom_system_prompt, orbis_prompt FROM ConversationEntity WHERE id = 'synthetic-draft'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("original system prompt", cursor.getString(0))
                assertEquals(prompt, JsonInstant.decodeFromString<OrbisConversationPrompt>(cursor.getString(1)))
            }
        }
    }
}
