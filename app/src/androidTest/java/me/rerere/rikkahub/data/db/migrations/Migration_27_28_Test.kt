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

/** Migration of a separately named synthetic database; no app database or memory files. */
@RunWith(AndroidJUnit4::class)
class Migration_27_28_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun newAssistantMemoryTablesDoNotMigrateOrModifyLegacyMemories() {
        val name = "orbis-synthetic-memory-migration-27-28"
        helper.createDatabase(name, 27).use { db ->
            db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES (1,'synthetic-old-owner','synthetic legacy full original')")
        }
        helper.runMigrationsAndValidate(name, 28, true).use { db ->
            db.query("SELECT assistant_id,content FROM MemoryEntity WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("synthetic-old-owner", it.getString(0))
                assertEquals("synthetic legacy full original", it.getString(1))
            }
            listOf("orbis_memory_note", "orbis_memory_revision", "orbis_memory_operation",
                "orbis_memory_turn", "orbis_memory_surfacing").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }
}
