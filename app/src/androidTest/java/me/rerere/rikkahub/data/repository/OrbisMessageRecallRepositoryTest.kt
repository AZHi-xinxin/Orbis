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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class OrbisMessageRecallRepositoryTest {
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

    @Test fun recallPersistsMasksBothSurfacesAndRejectsOppositeOwner() = runBlocking {
        Fixture().use { f ->
            val user = UIMessage.user("PRIVATE-HUMAN").copy(translation = "PRIVATE-TRANSLATION")
            val ai = UIMessage.assistant("PRIVATE-AI")
            val c = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user.toMessageNode(), ai.toMessageNode()))
            f.repository.insertConversation(c)
            assertTrue(runCatching { f.repository.saveMessageRecall(c, ai.id, MessageRole.USER) }.isFailure)
            assertTrue(runCatching { f.repository.saveMessageRecall(c, user.id, MessageRole.ASSISTANT) }.isFailure)
            f.repository.saveMessageRecall(c, user.id, MessageRole.USER)
            f.repository.saveMessageRecall(f.load(c.id), ai.id, MessageRole.ASSISTANT)
            val saved = f.load(c.id)
            assertEquals(HUMAN_RECALL_NOTICE, saved.currentMessages[0].recallModelInput().toText())
            assertNull(saved.currentMessages[0].recallModelInput().translation)
            assertTrue(saved.currentMessages[1].recallModelInput().toText().contains("PRIVATE-AI"))
            assertTrue(saved.currentMessages[1].recallModelInput().toText().endsWith(AI_RECALL_CONTEXT_NOTE))
            saved.currentMessages.forEach { message ->
                assertFalse(message.toDto().parts.toString().contains("PRIVATE-"))
                assertTrue(message.toDto().recalled)
            }
            // An old stream snapshot and a stale translation must not make it visible again.
            f.repository.updateConversation(c.copy(title = "late save"))
            assertTrue(f.load(c.id).currentMessages.all { it.orbisRecalled })
            f.db.openHelper.readableDatabase.query("SELECT text FROM message_fts").use { cursor ->
                while (cursor.moveToNext()) assertFalse(cursor.getString(0).contains("PRIVATE-"))
            }
        }
    }

    @Test fun recallRemovesReferencedQuoteAndKeepsOtherMessagesAndBranches() = runBlocking {
        Fixture().use { f ->
            val ai = UIMessage.assistant("PRIVATE-QUOTED")
            val node = ai.toMessageNode()
            val id = Uuid.random()
            val user = UIMessage.user("human reply").copy(orbisQuote = OrbisMessageQuote(id, node.id, ai.id,
                MessageRole.ASSISTANT, "PRIVATE-QUOTED", ai.createdAt.toString()))
            val c = Conversation(id = id, assistantId = Uuid.random(), messageNodes = listOf(node, user.toMessageNode()))
            f.repository.insertConversation(c)
            f.repository.saveMessageRecall(c, ai.id, MessageRole.ASSISTANT)
            val saved = f.load(id)
            assertTrue(saved.currentMessages[1].orbisQuoteRecalled)
            assertNull(saved.currentMessages[1].privateRoomSafePresentation().orbisQuote)
            assertNull(saved.currentMessages[1].recallModelInput().orbisQuote)
            assertEquals("human reply", saved.currentMessages[1].recallModelInput().toText())
            f.repository.updateConversation(c)
            assertTrue(f.load(id).currentMessages[1].orbisQuoteRecalled)
            assertTrue(runCatching { f.repository.saveMessageRecall(c.copy(assistantId = Uuid.random()), ai.id, MessageRole.ASSISTANT) }.isFailure)
        }
    }

    @Test fun failedTransactionDoesNotClaimRecallOrMutateHistory() = runBlocking {
        Fixture().use { f ->
            val message = UIMessage.assistant("unchanged")
            val c = Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode(), UIMessage.user("next").toMessageNode()))
            f.repository.insertConversation(c)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_recall BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { f.repository.saveMessageRecall(c, message.id, MessageRole.ASSISTANT) }.isFailure)
            assertFalse(f.load(c.id).currentMessages.first().orbisRecalled)
        }
    }
}
