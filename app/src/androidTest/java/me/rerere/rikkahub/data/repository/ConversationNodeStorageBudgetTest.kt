package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.MAX_LEGACY_NODE_COMPARISON_BYTES
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic, isolated in-memory database only; never opens user chats, tools or network. */
@RunWith(AndroidJUnit4::class)
class ConversationNodeStorageBudgetTest {
    private class Fixture(decorate: (MessageNodeDAO) -> MessageNodeDAO = { it }) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(db.conversationDao(), decorate(db.messageNodeDao()), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        override fun close() { scope.cancel(); db.close() }
    }

    private fun original() = Conversation(assistantId = Uuid.random(), title = "synthetic-budget",
        messageNodes = listOf(UIMessage.user("question").toMessageNode(), UIMessage.assistant("answer").toMessageNode()))

    @Test fun oversizedNewNodeRollsBackHeaderRowsAndFts() = runBlocking {
        Fixture().use { f ->
            val old = original()
            f.repository.insertConversation(old)
            val rows = f.db.messageNodeDao().getNodesOfConversation(old.id.toString())
            val next = old.copy(title = "must roll back", messageNodes = old.messageNodes +
                UIMessage.assistant("中".repeat(MessageNodeBudget.MAX_NODE_BYTES / 3)).toMessageNode())
            assertTrue(runCatching { f.repository.updateConversation(next) }.exceptionOrNull() is MessageNodeCapacityException)
            assertEquals(old.title, f.db.conversationDao().getConversationById(old.id.toString())?.title)
            assertEquals(rows, f.db.messageNodeDao().getNodesOfConversation(old.id.toString()))
            f.db.openHelper.readableDatabase.query("SELECT DISTINCT title FROM message_fts").use {
                assertTrue(it.moveToFirst()); assertEquals(old.title, it.getString(0)); assertFalse(it.moveToNext())
            }
            val new = next.copy(id = Uuid.random(), messageNodes = next.messageNodes.map { it.copy(id = Uuid.random()) })
            assertTrue(runCatching { f.repository.insertConversation(new) }.isFailure)
            assertFalse(f.repository.existsConversationById(new.id))
        }
    }

    @Test fun allBranchesCountAndEarlierWritesRollBackWhenLaterNodeRejected() = runBlocking {
        Fixture().use { f ->
            val old = original(); f.repository.insertConversation(old)
            val head = old.messageNodes.first().copy(messages = listOf(UIMessage.user("should roll back")))
            val tail = old.messageNodes.last().copy(messages = listOf(
                UIMessage.assistant("a".repeat(400 * 1024)), UIMessage.assistant("b".repeat(400 * 1024))))
            assertTrue(runCatching { f.repository.updateConversation(old.copy(messageNodes = listOf(head, tail))) }
                .exceptionOrNull() is MessageNodeCapacityException)
            assertEquals(old.messageNodes, f.repository.getConversationById(old.id)?.messageNodes)
        }
    }

    @Test fun exactLegacyOversizedRowIsNeverReadDeletedOrRewrittenDuringOtherEdits() = runBlocking {
        var legacyId = ""
        Fixture { delegate -> object : MessageNodeDAO by delegate {
            override suspend fun getNodeOfConversation(conversationId: String, nodeId: String): MessageNodeEntity? {
                check(nodeId != legacyId) { "large row must remain inside SQL" }
                return delegate.getNodeOfConversation(conversationId, nodeId)
            }
            override suspend fun getBoundedNodeOfConversation(conversationId: String, nodeId: String): MessageNodeEntity? {
                check(nodeId != legacyId) { "do not request large row body" }
                return delegate.getBoundedNodeOfConversation(conversationId, nodeId)
            }
        } }.use { f ->
            val old = original(); f.repository.insertConversation(old)
            val legacy = old.messageNodes.first().copy(messages = listOf(UIMessage.user("x".repeat(900 * 1024))))
            legacyId = legacy.id.toString()
            val encoded = MessageNodeBudget.encodeNode(legacy.messages, MAX_LEGACY_NODE_COMPARISON_BYTES)
            // Test fixture represents a pre-upgrade oversized record, not an allowed new write.
            f.db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages = ? WHERE id = ?", arrayOf(encoded, legacyId))
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_legacy_body_write BEFORE UPDATE OF messages ON message_node " +
                "WHEN OLD.id = '$legacyId' BEGIN SELECT RAISE(ABORT, 'legacy body rewritten'); END")
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_legacy_delete BEFORE DELETE ON message_node " +
                "WHEN OLD.id = '$legacyId' BEGIN SELECT RAISE(ABORT, 'legacy row deleted'); END")
            val edited = old.messageNodes.last().copy(messages = listOf(UIMessage.assistant("new safe answer")))
            val next = old.copy(title = "safe title", messageNodes = listOf(legacy, edited))
            f.repository.updateConversation(next)
            assertTrue(f.db.messageNodeDao().hasExactMessages(old.id.toString(), legacyId, encoded))
            assertEquals("safe title", f.db.conversationDao().getConversationById(old.id.toString())?.title)
            assertEquals(MessageNodeBudget.encodeNode(edited.messages), f.db.messageNodeDao()
                .getBoundedNodeOfConversation(old.id.toString(), edited.id.toString())?.messages)
            assertTrue(f.repository.applyGeneratedSuggestionsIfUnchanged(next, listOf("safe suggestion")))
            val altered = legacy.copy(messages = listOf(UIMessage.user("y".repeat(900 * 1024))))
            assertTrue(runCatching { f.repository.updateConversation(next.copy(messageNodes = listOf(altered, edited))) }
                .exceptionOrNull() is MessageNodeCapacityException)
            assertTrue(f.db.messageNodeDao().hasExactMessages(old.id.toString(), legacyId, encoded))
        }
    }

    @Test fun daoWriteEntryPointsRejectOversizeAndAtomicBatchKeepsExistingRows() = runBlocking {
        Fixture().use { f ->
            val old = original(); f.repository.insertConversation(old)
            val dao = f.db.messageNodeDao()
            val before = dao.getNodesOfConversation(old.id.toString())
            val row = before.last(); val huge = "a".repeat(MessageNodeBudget.MAX_NODE_BYTES + 1)
            assertTrue(runCatching { dao.updateMessages(row.conversationId, row.id, huge) }.exceptionOrNull() is MessageNodeCapacityException)
            assertTrue(runCatching { dao.updateMessagesIfUnchanged(row.conversationId, row.id, row.messages, huge) }.exceptionOrNull() is MessageNodeCapacityException)
            assertTrue(runCatching { dao.update(row.copy(messages = huge)) }.exceptionOrNull() is MessageNodeCapacityException)
            assertTrue(runCatching { dao.insert(row.copy(messages = huge)) }.exceptionOrNull() is MessageNodeCapacityException)
            val added = row.copy(id = Uuid.random().toString(), nodeIndex = 2)
            assertTrue(runCatching { dao.insertAll(listOf(added, row.copy(messages = huge))) }.exceptionOrNull() is MessageNodeCapacityException)
            assertEquals(before, dao.getNodesOfConversation(old.id.toString()))
            assertEquals(0, dao.updateMessagesWithinBudget(row.conversationId, row.id, huge))
            assertEquals(before, dao.getNodesOfConversation(old.id.toString()))
        }
    }

    @Test fun unchangedLegacyCandidateAboveAbsoluteCeilingIsSafelyRefused() = runBlocking {
        Fixture().use { f ->
            val old = original(); f.repository.insertConversation(old)
            val id = old.messageNodes.first().id.toString()
            f.db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages = CAST(zeroblob(?) AS TEXT) WHERE id = ?",
                arrayOf<Any>(MAX_LEGACY_NODE_COMPARISON_BYTES + 1, id))
            val before = f.db.messageNodeDao().getRescueRawSize(old.id.toString())
            val legacy = old.messageNodes.first().copy(messages = listOf(UIMessage.user("a".repeat(MAX_LEGACY_NODE_COMPARISON_BYTES))))
            assertTrue(runCatching { f.repository.updateConversation(old.copy(title = "must not commit",
                messageNodes = listOf(legacy, old.messageNodes.last()))) }.exceptionOrNull() is MessageNodeCapacityException)
            assertEquals(before, f.db.messageNodeDao().getRescueRawSize(old.id.toString()))
            assertEquals(old.title, f.db.conversationDao().getConversationById(old.id.toString())?.title)
        }
    }

    @Test fun ignoredInsertCannotReportSuccessFromAnOlderLastInsertRowId() = runBlocking {
        Fixture().use { f ->
            val old = original(); f.repository.insertConversation(old)
            val before = f.db.messageNodeDao().getNodesOfConversation(old.id.toString())
            val row = before.last().copy(id = Uuid.random().toString(), nodeIndex = 2)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_synthetic_insert BEFORE INSERT ON message_node " +
                "WHEN NEW.id = '${row.id}' BEGIN SELECT RAISE(IGNORE); END")
            val failure = runCatching { f.db.messageNodeDao().insert(row) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("message_node_storage_write_failed", failure?.message)
            assertEquals(before, f.db.messageNodeDao().getNodesOfConversation(old.id.toString()))
        }
    }

    @Test fun nodeIdCannotMoveBetweenConversationOwners() = runBlocking {
        Fixture().use { f ->
            val first = original(); val second = original()
            f.repository.insertConversation(first); f.repository.insertConversation(second)
            assertTrue(runCatching { f.repository.updateConversation(second.copy(messageNodes = first.messageNodes)) }.isFailure)
            assertEquals(first.messageNodes, f.repository.getConversationById(first.id)?.messageNodes)
            assertEquals(second.messageNodes, f.repository.getConversationById(second.id)?.messageNodes)
        }
    }
}
