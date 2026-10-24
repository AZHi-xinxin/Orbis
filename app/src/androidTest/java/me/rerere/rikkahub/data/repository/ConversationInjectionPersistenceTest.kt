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
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationInjectionEdit
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationInjectionPersistenceTest {
    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT,node_id TEXT,message_id TEXT,conversation_id TEXT,title TEXT,update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        override fun close() { scope.cancel(); db.close() }
    }

    @Test fun pickerChangesOnlyColumnsAndOldWholeSaveCannotUndoIt() = runBlocking {
        Fixture().use { f ->
            val original = Conversation(assistantId = Uuid.random(), title = "synthetic",
                messageNodes = listOf(UIMessage.user("keep original history").toMessageNode()))
            f.repo.insertConversation(original)
            val book = Uuid.random()
            val edit = ConversationInjectionEdit.between(original, original.copy(lorebookIds = setOf(book)))
            val before = f.db.messageNodeDao().getNodesOfConversation(original.id.toString())
            f.repo.saveInjectionSelection(original.copy(messageNodes = emptyList()), edit)
            assertEquals(before, f.db.messageNodeDao().getNodesOfConversation(original.id.toString()))
            assertEquals(setOf(book), f.repo.getConversationById(original.id)!!.lorebookIds)
            f.repo.updateConversation(original.copy(title = "later title"))
            val loaded = f.repo.getConversationById(original.id)!!
            assertEquals(setOf(book), loaded.lorebookIds)
            assertEquals("later title", loaded.title)
            assertEquals(original.messageNodes, loaded.messageNodes)
            val continued = original.copy(messageNodes = original.messageNodes + UIMessage.assistant("new reply").toMessageNode())
            val stored = f.repo.commitHumanContinuation(original, continued)!!
            assertEquals(setOf(book), stored.lorebookIds)
            assertEquals(continued.messageNodes, stored.messageNodes)
            val off = ConversationInjectionEdit.between(loaded, loaded.copy(lorebookIds = emptySet()))
            f.repo.saveInjectionSelection(loaded, off)
            assertTrue(f.repo.getConversationById(original.id)!!.lorebookIds.isEmpty())
            assertEquals(continued.messageNodes, f.repo.getConversationById(original.id)!!.messageNodes)
        }
    }

    @Test fun emptyChatSelectionPersistsAndOwnerMismatchCannotWrite() = runBlocking {
        Fixture().use { f ->
            val original = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), newConversation = true)
            val chosen = original.copy(lorebookIds = setOf(Uuid.random()), modeInjectionIds = setOf(Uuid.random()))
            f.repo.saveInjectionSelection(original, ConversationInjectionEdit.between(original, chosen))
            val saved = f.repo.getConversationById(original.id)!!
            assertEquals(chosen.lorebookIds, saved.lorebookIds)
            assertEquals(chosen.modeInjectionIds, saved.modeInjectionIds)
            val foreign = original.copy(assistantId = Uuid.random())
            val result = runCatching { f.repo.saveInjectionSelection(foreign,
                ConversationInjectionEdit.between(foreign, foreign.copy(lorebookIds = setOf(Uuid.random())))) }
            assertTrue(result.isFailure)
            assertEquals(saved, f.repo.getConversationById(original.id))
        }
    }

    @Test fun freshPresetChatCanSelectButDeletedSavedChatCannotBeRecreated() = runBlocking {
        Fixture().use { f ->
            val fresh = Conversation(assistantId = Uuid.random(), newConversation = true,
                messageNodes = listOf(UIMessage.assistant("preset greeting").toMessageNode()))
            val chosen = fresh.copy(lorebookIds = setOf(Uuid.random()))
            f.repo.saveInjectionSelection(fresh, ConversationInjectionEdit.between(fresh, chosen))
            assertEquals(fresh.messageNodes, f.repo.getConversationById(fresh.id)!!.messageNodes)
            val deleted = fresh.copy(id = Uuid.random(), newConversation = false)
            assertTrue(runCatching { f.repo.saveInjectionSelection(deleted,
                ConversationInjectionEdit.between(deleted, deleted.copy(lorebookIds = chosen.lorebookIds))) }.isFailure)
            assertNull(f.repo.getConversationById(deleted.id))
        }
    }
}
