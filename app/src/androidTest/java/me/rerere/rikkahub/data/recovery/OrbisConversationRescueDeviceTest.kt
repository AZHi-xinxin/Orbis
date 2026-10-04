package me.rerere.rikkahub.data.recovery

import android.app.Application
import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/** Real Room and checkpoint files, exclusively synthetic records in a private temporary tree.
 * No ChatService/application initialization, model call, network, or production DB/settings. */
@RunWith(AndroidJUnit4::class)
class OrbisConversationRescueDeviceTest {
    private class Fixture(private val heap: () -> Long = { 1024L * 1024 * 1024 }) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(cache.toPath(), "orbis-rescue-fixture-").toFile()
        private fun owned(): File {
            check(root.canonicalFile == root && root.parentFile == cache && root.name.startsWith("orbis-rescue-fixture-"))
            check(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
            return root
        }
        private fun child(name: String) = File(owned(), name).apply { check(isDirectory || mkdir()) }
        val context: Context = object : ContextWrapper(instrumentation.context) {
            private fun forbidden(): Nothing = throw AssertionError("Private production access forbidden in rescue fixture")
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = child("files")
            override fun getCacheDir(): File = child("cache")
            override fun getNoBackupFilesDir(): File = child("no-backup")
            override fun getCodeCacheDir(): File = forbidden()
            override fun getDir(name: String, mode: Int): File = forbidden()
            override fun getDatabasePath(name: String): File = forbidden()
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbidden()
            override fun openFileInput(name: String): FileInputStream = forbidden()
            override fun openFileOutput(name: String, mode: Int): FileOutputStream = forbidden()
            override fun getFileStreamPath(name: String): File = forbidden()
            override fun deleteFile(name: String): Boolean = forbidden()
            override fun fileList(): Array<String> = forbidden()
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = forbidden()
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = forbidden()
            override fun deleteDatabase(name: String): Boolean = forbidden()
            override fun databaseList(): Array<String> = forbidden()
            override fun getContentResolver(): ContentResolver = forbidden()
            override fun startService(service: Intent): ComponentName? = forbidden()
            override fun startForegroundService(service: Intent): ComponentName? = forbidden()
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = forbidden()
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        val store = AndroidGenerationCheckpointStore.create(context.noBackupFilesDir)
        val journal = GenerationCheckpointJournal(store)
        val rescue = OrbisConversationRescue(context, db, repository, heap)

        fun preservedCopies(): Int = File(context.noBackupFilesDir, "orbis-conversation-rescue-v1")
            .listFiles().orEmpty().count { it.isFile }

        override fun close() {
            scope.cancel()
            db.close()
            check(!LcExternalRecoveryGate.isAllowed())
            val exactRoot = owned().toPath()
            val entries = Files.walk(exactRoot).use { it.sorted(Comparator.reverseOrder()).iterator().asSequence().toList() }
            entries.forEach { path ->
                check(path.startsWith(exactRoot) && !Files.isSymbolicLink(path))
                check(path.toFile().canonicalFile.toPath() == path)
            }
            entries.forEach { Files.delete(it) }
        }
    }

    private data class Scenario(val conversation: Conversation, val handle: GenerationCheckpointHandle, val complete: String, val damaged: String)
    private suspend fun Fixture.seed(pendingTool: Boolean = false): Scenario {
        val base = Conversation(assistantId = Uuid.random(), title = "synthetic rescue only",
            messageNodes = List(3) { UIMessage.user("synthetic history $it").toMessageNode() })
        val tool = UIMessagePart.Tool("synthetic-call", "synthetic_never_executed", "{}", emptyList())
        val response = UIMessage(role = MessageRole.ASSISTANT, parts =
            (if (pendingTool) listOf(tool) else emptyList()) + UIMessagePart.Text("synthetic repair marker?")).toMessageNode()
        val handle = journal.begin(base)
        journal.checkpoint(handle, base.assistantId, base.compactionEpoch, base.messageNodes.takeLast(1) + response,
            if (pendingTool) GenerationToolTransition(tool.toolCallId, tool.toolName, GenerationToolStatus.STARTED) else null)
        val final = base.copy(messageNodes = base.messageNodes + response)
        repository.insertConversation(final)
        val row = db.messageNodeDao().getNodeOfConversation(final.id.toString(), response.id.toString())!!
        val damaged = row.messages.replace("synthetic repair marker?\"", "synthetic repair marker\uD83D\uDE00")
        check(isKnownQuoteEncodingDamage(damaged, row.messages))
        check(runCatching { JsonInstant.parseToJsonElement(damaged) }.isFailure)
        check(db.messageNodeDao().updateMessages(row.conversationId, row.id, damaged) == 1)
        return Scenario(final, handle, row.messages, damaged)
    }

    @Test fun brokenWindowCanBeListedAndSingleMatchingCellRestoredWithoutHistoryRewind() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id
            val beforeHeader = fixture.db.conversationDao().getConversationById(id.toString())!!
            val beforeRows = fixture.db.messageNodeDao().getNodesOfConversation(id.toString())
            val checkpoint = fixture.store.read(id)!!
            assertTrue(runCatching { fixture.repository.getConversationById(id) }.isFailure)
            assertTrue(fixture.rescue.windows().any { it.id == id })
            val inspection = fixture.rescue.inspect(id)
            assertEquals(1, inspection.damagedCount)
            assertTrue(inspection.repairAvailable)
            assertTrue(inspection.copy.isFile)
            ZipFile(inspection.copy).use { zip ->
                assertNotNull(zip.getEntry("raw-nodes.jsonl"))
                assertArrayEquals(checkpoint, zip.getInputStream(zip.getEntry("generation-checkpoint.json")).use { it.readBytes() })
            }
            // Exclusive fixture: no session/generation exists, so no production ChatService is created.
            val afterCopy = fixture.rescue.repair(inspection)
            assertTrue(afterCopy.isFile)
            val afterRows = fixture.db.messageNodeDao().getNodesOfConversation(id.toString())
            assertEquals(beforeHeader, fixture.db.conversationDao().getConversationById(id.toString()))
            assertEquals(beforeRows.dropLast(1), afterRows.dropLast(1))
            assertEquals(beforeRows.last().copy(messages = scenario.complete), afterRows.last())
            assertEquals(scenario.conversation.messageNodes, fixture.repository.getConversationById(id)!!.messageNodes)
            assertArrayEquals(checkpoint, fixture.store.read(id))
        }
    }

    @Test fun twoDamagedRowsOnlyOfferDiagnosticCopyAndNeverChangeEither() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id.toString()
            val second = fixture.db.messageNodeDao().getNodesOfConversation(id)[1]
            fixture.db.messageNodeDao().updateMessages(id, second.id, "{synthetic second damage")
            val before = fixture.db.messageNodeDao().getNodesOfConversation(id)
            val result = fixture.rescue.inspect(scenario.conversation.id)
            assertEquals(2, result.damagedCount)
            assertFalse(result.repairAvailable)
            assertTrue(result.copy.isFile)
            assertTrue(runCatching { fixture.rescue.repair(result) }.isFailure)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(id))
        }
    }

    @Test fun historyChangeAfterPreviewRejectsStaleRepair() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id
            val preview = fixture.rescue.inspect(id)
            assertTrue(preview.repairAvailable)
            fixture.db.conversationDao().updateTitle(id.toString(), "edited while preview was open")
            val before = fixture.db.messageNodeDao().getNodesOfConversation(id.toString())
            assertTrue(runCatching { fixture.rescue.repair(preview) }.isFailure)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(id.toString()))
            assertEquals("edited while preview was open", fixture.db.conversationDao().getConversationById(id.toString())!!.title)
        }
    }

    @Test fun newerCheckpointAfterPreviewIsNotMistakenForAuthorizedCopy() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val conversation = scenario.conversation
            val preview = fixture.rescue.inspect(conversation.id)
            assertTrue(preview.repairAvailable)
            val tail = conversation.messageNodes.takeLast(2).toMutableList()
            tail[1] = tail[1].copy(messages = tail[1].messages.map { it.copy(parts = listOf(UIMessagePart.Text("new synthetic tail"))) })
            fixture.journal.checkpoint(scenario.handle, conversation.assistantId, conversation.compactionEpoch, tail)
            val before = fixture.db.messageNodeDao().getNodesOfConversation(conversation.id.toString())
            assertTrue(runCatching { fixture.rescue.repair(preview) }.isFailure)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(conversation.id.toString()))
        }
    }

    @Test fun unknownToolEffectPreventsAutomaticRepairEvenWithMatchingEncodingDamage() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed(pendingTool = true)
            val before = fixture.db.messageNodeDao().getNodesOfConversation(scenario.conversation.id.toString())
            val preview = fixture.rescue.inspect(scenario.conversation.id)
            assertEquals(1, preview.damagedCount)
            assertFalse(preview.repairAvailable)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(scenario.conversation.id.toString()))
        }
    }

    @Test fun aValidCheckpointDoesNotAuthorizeRepairOfUnrelatedDamage() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id.toString()
            val row = fixture.db.messageNodeDao().getNodesOfConversation(id).last()
            fixture.db.messageNodeDao().updateMessages(id, row.id, scenario.complete.dropLast(6))
            val preview = fixture.rescue.inspect(scenario.conversation.id)
            assertEquals(1, preview.damagedCount)
            assertFalse(preview.repairAvailable)
            assertEquals(scenario.complete.dropLast(6), fixture.db.messageNodeDao().getNodeOfConversation(id, row.id)!!.messages)
        }
    }

    @Test fun databaseWriteFailureLeavesOriginalCellAndCheckpointUntouched() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id
            val preview = fixture.rescue.inspect(id)
            assertTrue(preview.repairAvailable)
            val checkpoint = fixture.store.read(id)
            val before = fixture.db.messageNodeDao().getNodesOfConversation(id.toString())
            fixture.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER rescue_deny_write BEFORE UPDATE ON message_node BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { fixture.rescue.repair(preview) }.isFailure)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(id.toString()))
            assertArrayEquals(checkpoint, fixture.store.read(id))
            assertTrue(preview.copy.isFile)
        }
    }

    @Test fun oversizedRawCellIsRefusedBySqlBeforeDecodingOrCreatingDiagnosticCopy() = runBlocking {
        Fixture().use { fixture ->
            val scenario = fixture.seed()
            val id = scenario.conversation.id
            val nodeId = scenario.conversation.messageNodes.last().id.toString()
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE message_node SET messages = CAST(zeroblob(?) AS TEXT) WHERE id = ?",
                arrayOf<Any>(RescueAdmission.MAX_ROW_BYTES + 1, nodeId))
            val before = fixture.db.messageNodeDao().getRescueRawSize(id.toString())
            assertEquals(RescueAdmission.MAX_ROW_BYTES + 1, before.largestRowBytes)
            assertTrue(fixture.rescue.windows().any { it.id == id })
            assertTrue(runCatching { fixture.rescue.inspect(id) }.exceptionOrNull() is RescueCapacityException)
            assertEquals(before, fixture.db.messageNodeDao().getRescueRawSize(id.toString()))
            assertEquals(0, fixture.preservedCopies())
        }
    }

    @Test fun aggregateAndNodeCountLimitsRejectWithoutTruncatingTheWindow() = runBlocking {
        Fixture().use { fixture ->
            val id = Conversation(assistantId = Uuid.random(), title = "synthetic capacity only", messageNodes = emptyList()).also {
                fixture.repository.insertConversation(it)
            }.id
            val rows = List(9) { index -> MessageNodeEntity(Uuid.random().toString(), id.toString(), index, "[]", 0) }
            fixture.db.messageNodeDao().insertAll(rows)
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE message_node SET messages = CAST(zeroblob(?) AS TEXT) WHERE conversation_id = ?",
                arrayOf<Any>(RescueAdmission.MAX_ROW_BYTES, id.toString()))
            assertTrue(runCatching { fixture.rescue.inspect(id) }.exceptionOrNull() is RescueCapacityException)
            assertEquals(9L * RescueAdmission.MAX_ROW_BYTES, fixture.db.messageNodeDao().getRescueRawSize(id.toString()).totalBytes)
            assertEquals(0, fixture.preservedCopies())
            fixture.db.messageNodeDao().deleteByConversation(id.toString())
            fixture.db.messageNodeDao().insertAll(List(RescueAdmission.MAX_NODES.toInt() + 1) { index ->
                MessageNodeEntity(Uuid.random().toString(), id.toString(), index, "[]", 0)
            })
            assertTrue(runCatching { fixture.rescue.inspect(id) }.exceptionOrNull() is RescueCapacityException)
            assertEquals(RescueAdmission.MAX_NODES + 1, fixture.db.messageNodeDao().getRescueRawSize(id.toString()).nodeCount)
            assertEquals(0, fixture.preservedCopies())
        }
    }

    @Test fun repairRechecksAvailableHeapAfterPreviewAndLeavesDamagedCellUntouched() = runBlocking {
        var headroom = 1024L * 1024 * 1024
        Fixture { headroom }.use { fixture ->
            val scenario = fixture.seed()
            val preview = fixture.rescue.inspect(scenario.conversation.id)
            assertTrue(preview.repairAvailable)
            val copies = fixture.preservedCopies()
            headroom = 0
            assertTrue(runCatching { fixture.rescue.repair(preview) }.exceptionOrNull() is RescueCapacityException)
            assertEquals(copies, fixture.preservedCopies())
            assertEquals(scenario.damaged, fixture.db.messageNodeDao().getNodesOfConversation(scenario.conversation.id.toString()).last().messages)
        }
    }

    @Test fun rescueCataloguePagesOnlyMetadataAndExcludesConsultationsEvenWithHugeLegacyHeader() = runBlocking {
        Fixture().use { fixture ->
            repeat(3) { index ->
                fixture.repository.insertConversation(Conversation(assistantId = Uuid.random(), title = "page-$index", messageNodes = emptyList()))
            }
            val first = fixture.rescue.windows("page-", limit = 2)
            val second = fixture.rescue.windows("page-", offset = 2, limit = 2)
            assertEquals(2, first.size); assertEquals(1, second.size)
            assertEquals(3, (first + second).map { it.id }.distinct().size)
            val id = first.first().id.toString()
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE conversationentity SET nodes = CAST(zeroblob(?) AS TEXT) WHERE id = ?",
                arrayOf<Any>(RescueAdmission.MAX_HEADER_BYTES + 1, id))
            assertEquals(3, fixture.rescue.windows("page-").size)
            assertTrue(runCatching { fixture.rescue.inspect(first.first().id) }.exceptionOrNull() is RescueCapacityException)
            assertEquals(0, fixture.preservedCopies())
            fixture.db.openHelper.writableDatabase.execSQL("UPDATE conversationentity SET consultation_binding = 'synthetic-hidden' WHERE id = ?", arrayOf(id))
            assertEquals(2, fixture.rescue.windows("page-").size)
        }
    }
}
