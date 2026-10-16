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

@RunWith(AndroidJUnit4::class)
class Migration_28_29_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun migrationAddsEmptyOptionalArchivesWithoutBackfillingOrAlteringMemories() {
        val name = "orbis-synthetic-optional-originals-28-29"
        helper.createDatabase(name, 28).use { it.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES (1,'synthetic','preserved original')") }
        helper.runMigrationsAndValidate(name, 29, true).use { db ->
            db.query("SELECT content FROM MemoryEntity WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals("preserved original", it.getString(0)) }
            for (table in listOf("orbis_original_archive", "orbis_original_chunk")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }
}
