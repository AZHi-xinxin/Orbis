package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Synthetic schema-25 projection only. Never opens a real backup or initializes normal services. */
@RunWith(AndroidJUnit4::class)
class RikkaSchemaDiagnosticsTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val chat = "11111111-1111-4111-8111-111111111111"
    private val node = "22222222-2222-4222-8222-222222222222"

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "rikka-schema-test-").toFile()
    }

    @After fun tearDown() { if (::directory.isInitialized) directory.deleteRecursively() }

    private fun database(change: (SQLiteDatabase) -> Unit = {}): File = File(directory, "source-${System.nanoTime()}.db").also { file ->
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 25
            db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, nodes TEXT NOT NULL, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE message_node (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ConversationEntity VALUES (?, 'synthetic', '[]', 1000, 2000)", arrayOf(chat))
            db.execSQL("INSERT INTO message_node VALUES (?, ?, 0, '[]', 0)", arrayOf(node, chat))
            change(db)
        }
    }

    @Test fun standardSchema25ProjectionAndEmptyLegacyNodesRemainSupportedWithoutSourceMutation() {
        val file = database()
        val before = file.readBytes()
        RikkaChatSnapshotReader.open(context, file).use { source ->
            assertEquals(1, source.conversations().size)
            assertEquals(1, source.nodes(chat).size)
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun retainedLegacyTextHasSpecificSafeCodeInsteadOfGenericSchemaFailure() {
        val file = database { it.execSQL("UPDATE ConversationEntity SET nodes='[{\"private\":\"synthetic body\"}]'") }
        val before = file.readBytes()
        val failure = runCatching { RikkaChatSnapshotReader.open(context, file).close() }.exceptionOrNull()!!
        assertTrue(ArchiveCapacity.publicError(failure).contains("[RIKKA_SCHEMA/LEGACY_NODES]"))
        assertFalse(ArchiveCapacity.publicError(failure).contains("synthetic body"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun repeatedPositionIsDifferentFromOrphanedMessage() {
        val duplicate = database { it.execSQL("INSERT INTO message_node VALUES ('33333333-3333-4333-8333-333333333333', ?, 0, '[]', 0)", arrayOf(chat)) }
        val orphan = database { it.execSQL("UPDATE message_node SET conversation_id='44444444-4444-4444-8444-444444444444'") }
        listOf(duplicate to "NODE_ORDER", orphan to "ORPHAN_NODES").forEach { (file, check) ->
            val failure = runCatching { RikkaChatSnapshotReader.open(context, file).close() }.exceptionOrNull()!!
            assertTrue(ArchiveCapacity.publicError(failure).contains("[RIKKA_SCHEMA/$check]"))
        }
    }
}
