package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Migration_30_31_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun addsNullableEmotionWithoutRewritingOldMemoryOrRevision() {
        val name = "synthetic-body-memory-migration-30-31"
        val original = helper.createDatabase(name, 30).use { db ->
            val tables = listOf("orbis_memory_note", "orbis_memory_revision")
            tables.associateWith { table ->
                db.execSQL("""INSERT INTO `$table` (assistant_id,id,body,summary,body_chars,summary_chars,state,
                    previous_state,tags,keywords,important,min_interval_turns,created_at,updated_at,revision,deleted)
                    VALUES ('synthetic-owner','synthetic-note','synthetic untouched original','original summary',28,16,
                    'static','static','[]','[]',0,0,1000,2000,1,0)""")
                db.query("PRAGMA table_info(`$table`)").use { c ->
                    buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                }.also { assertTrue("Missing expected table $table", it.isNotEmpty()) }
            }
        }
        helper.runMigrationsAndValidate(name, 31, true).use { db ->
            original.forEach { (table, columns) ->
                db.query("SELECT body,summary,revision,emotion FROM `$table`").use {
                    assertTrue(it.moveToFirst()); assertEquals("synthetic untouched original", it.getString(0))
                    assertEquals("original summary", it.getString(1)); assertEquals(1, it.getInt(2)); assertTrue(it.isNull(3))
                    assertFalse(it.moveToNext())
                }
                db.query("PRAGMA table_info(`$table`)").use { c ->
                    val names = mutableListOf<String>()
                    while(c.moveToNext()) {
                        val column = c.getString(c.getColumnIndexOrThrow("name")); names.add(column)
                        if(column == "emotion") { assertEquals(0, c.getInt(c.getColumnIndexOrThrow("notnull"))); assertEquals("NULL", c.getString(c.getColumnIndexOrThrow("dflt_value"))) }
                    }
                    assertEquals(columns + "emotion", names)
                }
            }
            db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        }
    }
}
