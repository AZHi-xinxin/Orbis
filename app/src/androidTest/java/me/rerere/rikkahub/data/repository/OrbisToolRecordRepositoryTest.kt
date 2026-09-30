package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisToolRecordEdit
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic in-memory DB only. Never opens the app DB or executes an actual tool. */
@RunWith(AndroidJUnit4::class)
class OrbisToolRecordRepositoryTest {
    private val now = LocalDateTime(2026, 9, 24, 21, 30)
    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        suspend fun load(id: Uuid) = repository.getConversationById(id)!!
        override fun close() { scope.cancel(); db.close() }
    }
    private fun tool(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "synthetic_read",
        input = "{}", output = listOf(UIMessagePart.Text("synthetic-result-$id")))
    private fun original(): Conversation {
        val answer = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text("before"), tool("a"), tool("b"), UIMessagePart.Text("after")))
        val hidden = UIMessage.assistant("hidden branch").copy(parts = listOf(tool("hidden"), UIMessagePart.Text("hidden branch")))
        return Conversation(assistantId = Uuid.random(), title = "synthetic-tools",
            messageNodes = listOf(UIMessage.user("synthetic question").toMessageNode(),
                answer.toMessageNode().copy(messages = listOf(answer, hidden))))
    }
    private fun deletion(c: Conversation) = OrbisToolRecordEdit(c.currentMessages.last().id, "a", false)

    @Test fun commitAndReloadPreserveProseSiblingAndHiddenBranchAndUndoDoesNotReplay() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.repository.insertConversation(before)
            val edited = f.repository.saveToolRecordEdit(before, deletion(before), now)
            val stored = f.load(before.id)
            assertEquals(listOf("b"), stored.currentMessages.last().getTools().map { it.toolCallId })
            assertEquals(before.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>(),
                edited.parts.filterIsInstance<UIMessagePart.Text>())
            assertEquals(before.messageNodes.last().messages[1], stored.messageNodes.last().messages[1])
            assertEquals("a", stored.currentMessages.last().deletedToolRecords.single().tool.toolCallId)
            f.repository.saveToolRecordEdit(stored, deletion(before).copy(restore = true), now)
            assertEquals(before.currentMessages.last().parts, f.load(before.id).currentMessages.last().parts)
            assertTrue(f.load(before.id).currentMessages.last().deletedToolRecords.isEmpty())
        }
    }

    @Test fun lateFtsFailureRollsBackBothCallAndUndoRecord() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.repository.insertConversation(before)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_fts BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic transaction failure'); END")
            assertTrue(runCatching { f.repository.saveToolRecordEdit(before, deletion(before), now) }.isFailure)
            assertEquals(before.messageNodes, f.load(before.id).messageNodes)
        }
    }

    @Test fun staleFullSaveCannotResurrectDeletedCallOrLoseUndo() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.repository.insertConversation(before)
            f.repository.saveToolRecordEdit(before, deletion(before), now)
            f.repository.updateConversation(before.copy(title = "new synthetic title"))
            val stored = f.load(before.id)
            assertEquals("new synthetic title", stored.title)
            assertEquals(listOf("b"), stored.currentMessages.last().getTools().map { it.toolCallId })
            assertEquals("a", stored.currentMessages.last().deletedToolRecords.single().tool.toolCallId)
        }
    }

    @Test fun hiddenAlternativeAndWrongConversationCannotBeChanged() = runBlocking {
        Fixture().use { f ->
            val before = original(); val other = original()
            f.repository.insertConversation(before); f.repository.insertConversation(other)
            val hiddenId = before.messageNodes.last().messages[1].id
            assertTrue(runCatching { f.repository.saveToolRecordEdit(before, OrbisToolRecordEdit(hiddenId, "hidden", false), now) }.isFailure)
            assertTrue(runCatching { f.repository.saveToolRecordEdit(other, deletion(before), now) }.isFailure)
            assertEquals(before.messageNodes, f.load(before.id).messageNodes)
            assertEquals(other.messageNodes, f.load(other.id).messageNodes)
        }
    }

    @Test fun staleTransactionCannotOverwriteAnAlreadyEditedCall() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.repository.insertConversation(before)
            f.repository.saveToolRecordEdit(before, deletion(before), now)
            val committed = f.load(before.id)
            assertTrue(runCatching { f.repository.saveToolRecordEdit(before, deletion(before).copy(toolCallId = "b"), now) }.isFailure)
            assertEquals(committed.messageNodes, f.load(before.id).messageNodes)
        }
    }
}
