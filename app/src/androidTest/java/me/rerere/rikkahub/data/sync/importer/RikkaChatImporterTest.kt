package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.encodeConversationEntity
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Synthetic files/databases only; never initializes app services, model providers or the real database. */
@RunWith(AndroidJUnit4::class)
class RikkaChatImporterTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var importer: RikkaChatImporter
    private lateinit var scope: AppScope
    private val targetAssistant = Uuid.random()
    private val existingId = Uuid.random()

    @Before fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val base = instrumentation.targetContext
        assertEquals(Application::class.java, base.applicationContext.javaClass)
        directory = Files.createTempDirectory(base.cacheDir.toPath(), "orbis-import-test-").toFile()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "db/$name").also { it.parentFile!!.mkdirs() }
        }
        database = AppDatabaseFactory.create(context)
        scope = AppScope()
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database, files, MessageFtsManager(database))
        importer = RikkaChatImporter(context, repository, files)
        repository.insertConversation(Conversation(existingId, targetAssistant, "existing-kept", emptyList()))
        File(context.filesDir, "private-marker").writeText("unchanged")
    }

    @After fun cleanup() {
        database.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun archive(sourceId: Uuid, invalidMessage: Boolean = false, secondChat: Boolean = false): File {
        val sourceFile = File(directory, "source-${Uuid.random()}")
        val source = AppDatabaseFactory.create(context, sourceFile.path)
        val snapshot = File(directory, "snapshot-${Uuid.random()}")
        try {
            source.conversationDao().insert(encodeConversationEntity(Conversation(sourceId, Uuid.random(), "imported-title", emptyList(),
                customSystemPrompt = "must-not-import", workspaceCwd = "/danger", lorebookIds = setOf(Uuid.random()))))
            val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Text("synthetic history"), UIMessagePart.Image("file:///old/files/upload/test.png"),
                UIMessagePart.Tool("synthetic-call", "never_execute", "{}"))))
            source.messageNodeDao().insert(MessageNodeEntity(Uuid.random().toString(), sourceId.toString(), 0,
                if (invalidMessage) "invalid-json" else JsonInstant.encodeToString(messages), 0))
            source.messageNodeDao().insert(MessageNodeEntity(Uuid.random().toString(), sourceId.toString(), 1,
                JsonInstant.encodeToString(listOf(UIMessage.system("never-import-system-prompt"))), 0))
            if (secondChat) {
                val otherId = Uuid.random()
                source.conversationDao().insert(encodeConversationEntity(Conversation(otherId, Uuid.random(), "second-import", emptyList())))
                source.messageNodeDao().insert(MessageNodeEntity(Uuid.random().toString(), otherId.toString(), 0, JsonInstant.encodeToString(messages), 0))
            }
            DatabaseBackup.createSnapshot(source.openHelper.writableDatabase, snapshot)
        } finally { source.close() }
        val archive = File(directory, "import-${Uuid.random()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("rikka_hub.db")); snapshot.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json")); zip.write("must-not-read-invalid-json".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("upload/test.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        return archive
    }

    @Test fun importsChatsAndAttachmentsOnceWithoutOverwritingOrExecuting() = runBlocking {
        val sourceId = Uuid.random()
        val zip = archive(sourceId)
        val first = importer.import(zip, targetAssistant)
        assertEquals(1, first.imported)
        assertEquals(1, first.attachments)
        val imported = requireNotNull(repository.getConversationById(rikkaImportId("conversation", sourceId.toString())))
        assertEquals(targetAssistant, imported.assistantId)
        assertNull(imported.customSystemPrompt)
        assertNull(imported.workspaceCwd)
        assertTrue(imported.lorebookIds.isEmpty())
        assertTrue(imported.currentMessages.none { it.role == MessageRole.SYSTEM || it.toText().contains("never-import-system-prompt") })
        val part = imported.currentMessages.first().parts.filterIsInstance<UIMessagePart.Image>().single()
        assertTrue(part.url.contains("/upload/"))
        assertFalse(part.url.contains("/old/"))
        assertTrue(imported.currentMessages.first().getTools().single().isExecuted)
        assertEquals(0, importer.import(zip, targetAssistant).imported)
        assertEquals(2, repository.countConversations())
        assertEquals("existing-kept", repository.getConversationById(existingId)?.title)
        assertEquals("unchanged", File(context.filesDir, "private-marker").readText())
        assertTrue(repository.searchMessages("synthetic").isNotEmpty())
    }

    @Test fun malformedArchiveDoesNotChangeExistingDatabase() = runBlocking {
        var rejected = false
        try { importer.import(archive(Uuid.random(), invalidMessage = true), targetAssistant) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, repository.countConversations())
        assertEquals("existing-kept", repository.getConversationById(existingId)?.title)
        assertEquals("unchanged", File(context.filesDir, "private-marker").readText())
    }

    @Test fun sharedSourceAttachmentIsCopiedPerWindow() = runBlocking {
        val sourceId = Uuid.random()
        assertEquals(2, importer.import(archive(sourceId, secondChat = true), targetAssistant).attachments)
        val imported = repository.getRecentConversations(targetAssistant).filter { it.id != existingId }
        assertEquals(2, imported.size)
        val urls = imported.map { chat -> chat.currentMessages.first().parts.filterIsInstance<UIMessagePart.Image>().single().url }
        assertNotEquals(urls[0], urls[1])
        repository.deleteConversation(imported[0])
        assertTrue(File(java.net.URI(urls[1])).isFile)
    }

    private data class NativeArchive(val zip: File, val databaseFile: File, val sourceId: String)

    /** Deliberately independent of Room/entity encoders: only the portable chat projection exists. */
    private fun nativeArchive(
        version: Int = 25,
        upstreamSchema24: Boolean = false,
        conversationColumns: String = "id TEXT, title TEXT, create_at INTEGER, update_at INTEGER",
        nodeColumns: String = "id TEXT, conversation_id TEXT, node_index INTEGER, messages TEXT, select_index INTEGER",
        mutate: (SQLiteDatabase, String, String) -> Unit = { _, _, _ -> },
    ): NativeArchive {
        val sourceId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        val sourceFile = File(directory, "native-source-${Uuid.random()}.db")
        SQLiteDatabase.openOrCreateDatabase(sourceFile, null).use { source ->
            source.rawQuery("PRAGMA journal_mode=DELETE", null).use { assertTrue(it.moveToFirst()) }
            source.version = version
            source.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            source.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(
                if (upstreamSchema24) "0ea1aaebfa031c7995c45a1e35822e1a" else "049f05fd92fc292b92c652743f056693"))
            if (upstreamSchema24) {
                // Copied from app/schemas/...AppDatabase/24.json, with TABLE_NAME substituted.
                // No Room factory, generated entity, migration, or Orbis-only column is involved.
                source.execSQL("""CREATE TABLE IF NOT EXISTS `ConversationEntity` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', `title` TEXT NOT NULL, `nodes` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, `suggestions` TEXT NOT NULL DEFAULT '[]', `is_pinned` INTEGER NOT NULL DEFAULT 0, `custom_system_prompt` TEXT NOT NULL DEFAULT '', `mode_injection_ids` TEXT NOT NULL DEFAULT '[]', `lorebook_ids` TEXT NOT NULL DEFAULT '[]', `workspace_cwd` TEXT NOT NULL DEFAULT '', `folder_id` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))""")
                source.execSQL("""CREATE TABLE IF NOT EXISTS `message_node` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `node_index` INTEGER NOT NULL, `messages` TEXT NOT NULL, `select_index` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
                source.execSQL("CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id` ON `message_node` (`conversation_id`)")
                source.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES (?, ?, '[]', ?, ?)",
                    arrayOf<Any>(sourceId, "native-import-title", 1000L, 2000L))
            } else {
                // No constraints here: adversarial fixtures exercise the reader's own validation.
                source.execSQL("CREATE TABLE ConversationEntity ($conversationColumns)")
                source.execSQL("CREATE TABLE message_node ($nodeColumns)")
                source.execSQL("INSERT INTO ConversationEntity VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(sourceId, "native-import-title", 1000L, 2000L))
            }
            source.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(nodeId, sourceId, 0, nativeMessages(), 0))
            mutate(source, sourceId, nodeId)
        }
        val archive = File(directory, "native-import-${Uuid.random()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("rikka_hub.db")); sourceFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json")); zip.write("must-not-read-invalid-json".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("upload/test.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        return NativeArchive(archive, sourceFile, sourceId)
    }

    private fun nativeMessages(): String = JsonInstant.encodeToString(listOf(UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("native synthetic history"),
            UIMessagePart.Image("file:///old/files/upload/test.png")),
    )))

    private fun syntheticFiles(): Map<String, List<Byte>> = context.filesDir.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(context.filesDir).invariantSeparatorsPath to it.readBytes().toList() }

    private fun managedRows(): List<Pair<Long, String>> = database.openHelper.readableDatabase
        .query("SELECT id, relative_path FROM managed_files ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
        }

    private suspend fun assertNativeRejected(label: String, source: NativeArchive) {
        val archiveBytes = source.zip.readBytes()
        val beforeCount = repository.countConversations()
        val beforeFiles = syntheticFiles()
        val beforeManagedRows = managedRows()
        val failure = runCatching { importer.import(source.zip, targetAssistant) }.exceptionOrNull()
        assertNotNull("$label must be rejected", failure)
        assertEquals("$label must not append partial chats", beforeCount, repository.countConversations())
        assertNull("$label must not commit its conversation", repository.getConversationById(rikkaImportId("conversation", source.sourceId)))
        assertEquals("$label must roll back newly copied uploads and preserve existing bytes", beforeFiles, syntheticFiles())
        assertEquals("$label must roll back managed-file records", beforeManagedRows, managedRows())
        assertEquals("existing-kept", repository.getConversationById(existingId)?.title)
        assertEquals("unchanged", File(context.filesDir, "private-marker").readText())
        assertArrayEquals("$label must not change the original ZIP", archiveBytes, source.zip.readBytes())
    }

    @Test fun nativeV25DifferentRoomHashWithoutOrbisColumnAppendsOnceAndKeepsSourceBytes() = runBlocking {
        val source = nativeArchive()
        SQLiteDatabase.openDatabase(source.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(26, raw.version)
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("049f05fd92fc292b92c652743f056693", it.getString(0))
            }
            raw.rawQuery("PRAGMA table_info(ConversationEntity)", null).use { columns ->
                while (columns.moveToNext()) assertNotEquals("orbis_prompt", columns.getString(1))
            }
        }
        val zipBytes = source.zip.readBytes()
        val databaseBytes = source.databaseFile.readBytes()
        val first = importer.import(source.zip, targetAssistant)
        assertEquals(1, first.imported)
        assertEquals(0, first.skipped)
        assertEquals(1, first.attachments)
        val imported = requireNotNull(repository.getConversationById(rikkaImportId("conversation", source.sourceId)))
        assertEquals(targetAssistant, imported.assistantId)
        assertEquals("native-import-title", imported.title)
        assertEquals(1000L, imported.createAt.toEpochMilli())
        assertEquals(2000L, imported.updateAt.toEpochMilli())
        assertNull(imported.customSystemPrompt)
        val image = imported.currentMessages.single().parts.filterIsInstance<UIMessagePart.Image>().single()
        assertFalse(image.url.contains("/old/"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(java.net.URI(image.url)).readBytes())
        val filesAfterFirst = syntheticFiles()
        val rowsAfterFirst = managedRows()
        val second = importer.import(source.zip, targetAssistant)
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
        assertEquals(0, second.attachments)
        assertEquals(2, repository.countConversations())
        assertEquals(filesAfterFirst, syntheticFiles())
        assertEquals(rowsAfterFirst, managedRows())
        assertEquals("existing-kept", repository.getConversationById(existingId)?.title)
        assertEquals("unchanged", File(context.filesDir, "private-marker").readText())
        assertArrayEquals(zipBytes, source.zip.readBytes())
        assertArrayEquals(databaseBytes, source.databaseFile.readBytes())
    }

    @Test fun nativeV24ModernMessageProjectionIsCompatibleWithoutRoomMigration() = runBlocking {
        val source = nativeArchive(version = 24, upstreamSchema24 = true) { db, _, _ ->
            val branches = JsonInstant.encodeToString(listOf(UIMessage.user("unselected synthetic branch"),
                UIMessage.user("selected v24 synthetic branch")))
            db.execSQL("UPDATE message_node SET messages=?, select_index=1", arrayOf(branches))
        }
        val zipBytes = source.zip.readBytes()
        assertEquals(1, importer.import(source.zip, targetAssistant).imported)
        val imported = requireNotNull(repository.getConversationById(rikkaImportId("conversation", source.sourceId)))
        assertEquals("native-import-title", imported.title)
        assertEquals(1, imported.messageNodes.single().selectIndex)
        assertEquals(2, imported.messageNodes.single().messages.size)
        assertTrue(imported.currentMessages.single().toText().contains("selected v24 synthetic branch"))
        assertEquals(2, repository.countConversations())
        assertArrayEquals(zipBytes, source.zip.readBytes())
    }

    @Test fun nativeV16LowerBoundaryAcceptsModernNodesAndLegacyEmptyZeroBranch() = runBlocking {
        val source = nativeArchive(version = 16) { db, sourceId, _ ->
            db.execSQL("INSERT INTO message_node VALUES (?, ?, 1, '[]', 0)",
                arrayOf(Uuid.random().toString(), sourceId))
        }
        assertEquals(1, importer.import(source.zip, targetAssistant).imported)
        val imported = requireNotNull(repository.getConversationById(rikkaImportId("conversation", source.sourceId)))
        assertEquals(1, imported.messageNodes.size)
        assertEquals(2, repository.countConversations())
    }

    @Test fun nativeUnsupportedVersionsFailClosedWithoutChangingExistingState() = runBlocking {
        listOf(15, 26).forEach { version ->
            assertNativeRejected("unsupported version $version", nativeArchive(version = version))
        }
    }

    @Test fun nativeMissingTablesAndRequiredColumnsAreRejected() = runBlocking {
        listOf("DROP TABLE ConversationEntity", "DROP TABLE message_node",
            "ALTER TABLE ConversationEntity RENAME COLUMN title TO unavailable_title",
            "ALTER TABLE message_node RENAME COLUMN messages TO unavailable_messages").forEach { sql ->
            assertNativeRejected(sql, nativeArchive { db, _, _ -> db.execSQL(sql) })
        }
    }

    @Test fun nativeWrongDeclaredColumnTypesAreRejectedEvenWithOtherwiseReadableValues() = runBlocking {
        assertNativeRejected("title declared INTEGER", nativeArchive(conversationColumns =
            "id TEXT, title INTEGER, create_at INTEGER, update_at INTEGER"))
        assertNativeRejected("timestamp declared TEXT", nativeArchive(conversationColumns =
            "id TEXT, title TEXT, create_at TEXT, update_at INTEGER"))
        assertNativeRejected("ordering declared TEXT", nativeArchive(nodeColumns =
            "id TEXT, conversation_id TEXT, node_index TEXT, messages TEXT, select_index INTEGER"))
        assertNativeRejected("messages declared INTEGER", nativeArchive(nodeColumns =
            "id TEXT, conversation_id TEXT, node_index INTEGER, messages INTEGER, select_index INTEGER"))
    }

    @Test fun nativeWrongStoredTypesAndNullsAreRejectedRatherThanCoerced() = runBlocking {
        listOf("UPDATE ConversationEntity SET create_at=1.5", "UPDATE ConversationEntity SET title=X'6162'",
            "UPDATE ConversationEntity SET title=NULL", "UPDATE message_node SET node_index='not-an-integer'",
            "UPDATE message_node SET select_index=0.5", "UPDATE message_node SET messages=X'5b5d'").forEach { sql ->
            assertNativeRejected(sql, nativeArchive { db, _, _ -> db.execSQL(sql) })
        }
    }

    @Test fun nativeViewsAndVirtualTablesCannotMasqueradeAsChatTables() = runBlocking {
        listOf("ConversationEntity", "message_node").forEach { name ->
            assertNativeRejected("view $name", nativeArchive { db, _, _ ->
                db.execSQL("ALTER TABLE $name RENAME TO original_$name")
                db.execSQL("CREATE VIEW $name AS SELECT * FROM original_$name")
            })
        }
        assertNativeRejected("virtual message table", nativeArchive { db, _, _ ->
            db.execSQL("DROP TABLE message_node")
            db.execSQL("CREATE VIRTUAL TABLE message_node USING fts4(id, conversation_id, node_index, messages, select_index)")
        })
    }

    @Test fun nativeDuplicateAndEmptyIdsAreRejected() = runBlocking {
        listOf("INSERT INTO ConversationEntity SELECT * FROM ConversationEntity",
            "INSERT INTO message_node SELECT id, conversation_id, node_index+1, messages, select_index FROM message_node",
            "UPDATE ConversationEntity SET id=''", "UPDATE ConversationEntity SET id='   '",
            "UPDATE message_node SET id=''", "UPDATE message_node SET id=NULL").forEach { sql ->
            assertNativeRejected(sql, nativeArchive { db, _, _ -> db.execSQL(sql) })
        }
        listOf("chat/fragment", "not-a-uuid").forEach { invalidId ->
            assertNativeRejected("invalid conversation UUID $invalidId", nativeArchive { db, _, _ ->
                // Keep the relation intact so rejection cannot merely come from orphan detection.
                db.execSQL("UPDATE ConversationEntity SET id=?", arrayOf(invalidId))
                db.execSQL("UPDATE message_node SET conversation_id=?", arrayOf(invalidId))
            })
            assertNativeRejected("invalid node UUID $invalidId", nativeArchive { db, _, _ ->
                db.execSQL("UPDATE message_node SET id=?", arrayOf(invalidId))
            })
        }
    }

    @Test fun nativeDuplicateNodeOrderAndOrphanNodesAreRejected() = runBlocking {
        assertNativeRejected("duplicate node ordering", nativeArchive { db, _, _ ->
            db.execSQL("INSERT INTO message_node SELECT ?, conversation_id, node_index, messages, select_index FROM message_node",
                arrayOf(Uuid.random().toString()))
        })
        assertNativeRejected("orphan node", nativeArchive { db, _, _ ->
            db.execSQL("UPDATE message_node SET conversation_id=?", arrayOf(Uuid.random().toString()))
        })
    }

    @Test fun nativeOversizedSingleTitleAndMessageJsonFailWithoutPartialImport() = runBlocking {
        assertNativeRejected("title larger than 64 KiB", nativeArchive { db, _, _ ->
            db.execSQL("UPDATE ConversationEntity SET title=?", arrayOf("x".repeat(64 * 1024 + 1)))
        })
        assertNativeRejected("message JSON larger than 1 MiB", nativeArchive { db, _, _ ->
            val messages = JsonInstant.encodeToString(listOf(UIMessage.user("x".repeat(1024 * 1024))))
            db.execSQL("UPDATE message_node SET messages=?", arrayOf(messages))
        })
    }

    @Test fun nativeLegacyNodesColumnCannotSilentlyDiscardOldHistory() = runBlocking {
        listOf("[{\"legacy\":\"history\"}]", "nonempty legacy history").forEach { legacy ->
            assertNativeRejected("nonempty old nodes column", nativeArchive { db, _, _ ->
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN nodes TEXT")
                db.execSQL("UPDATE ConversationEntity SET nodes=?", arrayOf(legacy))
            })
        }
        assertNativeRejected("old nodes column with incompatible declaration", nativeArchive { db, _, _ ->
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN nodes INTEGER")
            db.execSQL("UPDATE ConversationEntity SET nodes=0")
        })
    }

    @Test fun nativeNoCaseIdColumnsKeepCaseDistinctConversationNodesSeparate() = runBlocking {
        val lowerId = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
        val upperId = "AAAAAAAA-1111-4111-8111-AAAAAAAAAAAA"
        val lowerMessages = JsonInstant.encodeToString(listOf(UIMessage.user("lowercase synthetic history")))
        val upperMessages = JsonInstant.encodeToString(listOf(UIMessage.user("uppercase synthetic history")))
        val source = nativeArchive(
            conversationColumns = "id TEXT COLLATE NOCASE, title TEXT, create_at INTEGER, update_at INTEGER",
            nodeColumns = "id TEXT, conversation_id TEXT COLLATE NOCASE, node_index INTEGER, messages TEXT, select_index INTEGER",
        ) { db, _, _ ->
            db.execSQL("UPDATE ConversationEntity SET id=?, title='lowercase window'", arrayOf(lowerId))
            db.execSQL("UPDATE message_node SET conversation_id=?, messages=?", arrayOf(lowerId, lowerMessages))
            db.execSQL("INSERT INTO ConversationEntity VALUES (?, 'uppercase window', 1000, 2000)", arrayOf(upperId))
            db.execSQL("INSERT INTO message_node VALUES (?, ?, 0, ?, 0)",
                arrayOf(Uuid.random().toString(), upperId, upperMessages))
        }
        assertEquals(2, importer.import(source.zip, targetAssistant).imported)
        val lower = requireNotNull(repository.getConversationById(rikkaImportId("conversation", lowerId)))
        val upper = requireNotNull(repository.getConversationById(rikkaImportId("conversation", upperId)))
        assertNotEquals(lower.id, upper.id)
        assertEquals("lowercase synthetic history", lower.currentMessages.single().toText())
        assertEquals("uppercase synthetic history", upper.currentMessages.single().toText())
        assertEquals(3, repository.countConversations())
    }

    @Test fun nativeBadJsonAndInvalidBranchesRollbackNewAttachmentsButKeepExistingUploads() = runBlocking {
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
        files.saveManagedFromBytes("upload", byteArrayOf(9, 8, 7), "existing-synthetic.png", "image/png")
        listOf("not-json" to 0, "{}" to 0, "[]" to 1, nativeMessages() to -1, nativeMessages() to 1)
            .forEach { (json, selected) ->
                // Node zero is valid and references an upload. A later node fails; either early
                // validation or rollback must leave no new file/managed row/partial conversation.
                assertNativeRejected("bad JSON/branch $selected: $json", nativeArchive { db, sourceId, _ ->
                    db.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(Uuid.random().toString(), sourceId, 1, json, selected))
                })
            }
    }
}
