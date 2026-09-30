package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
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

/** Synthetic in-memory Room only; never opens a user database or imports private messages. */
@RunWith(AndroidJUnit4::class)
class ConversationLoadSafetyTest {
    @Test fun consultationUsesNormalRowsButIsHiddenFromHumanLists() = runBlocking {
        Fixture().use { fixture ->
            val normal = conversation(2)
            val hidden = conversation(3).copy(assistantId = normal.assistantId,
                consultation = me.rerere.rikkahub.data.model.ConsultationConversationBinding("synthetic-session", "synthetic-subject"))
            fixture.repository.insertConversation(normal)
            fixture.repository.insertConversation(hidden)
            assertEquals(listOf(normal.id.toString()), fixture.db.conversationDao().getAll().first().map { it.id })
            assertEquals(listOf(normal.id), fixture.repository.getConversationsOfAssistant(normal.assistantId).first().map { it.id })
            val reloaded = fixture.repository.getConversationById(hidden.id)!!
            assertEquals(hidden.id, reloaded.id)
            assertEquals(hidden.assistantId, reloaded.assistantId)
            assertEquals(hidden.consultation, reloaded.consultation)
            assertEquals(hidden.messageNodes, reloaded.messageNodes)
            assertNull(fixture.db.conversationDao().getSummaryOfAssistant(hidden.id.toString(), hidden.assistantId.toString()))
            val changed = hidden.copy(messageNodes = hidden.messageNodes + UIMessage.user("synthetic peer wake").toMessageNode())
            fixture.repository.updateConversation(changed, hidden.assistantId)
            assertEquals(changed.messageNodes, fixture.repository.getConversationById(hidden.id)?.messageNodes)
            assertTrue(runCatching { fixture.repository.updateConversation(changed.copy(consultation = null)) }.isFailure)
            fixture.db.conversationDao().deleteById(hidden.id.toString())
            assertTrue(runCatching { fixture.repository.updateConversation(changed, hidden.assistantId) }.isFailure)
            assertFalse(fixture.repository.existsConversationById(hidden.id))
        }
    }
    private class Fixture(
        decorate: (MessageNodeDAO) -> MessageNodeDAO = { it },
    ) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val scope = AppScope()
        val repository = ConversationRepository(db.conversationDao(), decorate(db.messageNodeDao()),
            db.favoriteDao(), db, FilesManager(context, FilesRepository(db.managedFileDao()), scope),
            MessageFtsManager(db))
        override fun close() { scope.cancel(); db.close() }
    }

    private fun conversation(count: Int = 129) = Conversation(
        assistantId = Uuid.random(), title = "synthetic-load-safety",
        messageNodes = List(count) { UIMessage.user("synthetic message $it").toMessageNode() },
    )

    @Test fun completeMultiplePagesRetainEveryNodeInOrder() = runBlocking {
        Fixture().use { fixture ->
            val original = conversation()
            fixture.repository.insertConversation(original)
            assertEquals(original.messageNodes, fixture.repository.getConversationById(original.id)?.messageNodes)
        }
    }

    @Test fun cursorAndStateFailuresNeverSkipAPageOrPublishPartialHistory() = runBlocking {
        for (failure in listOf(
            SQLiteBlobTooBigException("synthetic-private-payload"),
            IllegalStateException("synthetic-private-payload"),
        )) {
            val offsets = mutableListOf<Int>()
            Fixture { delegate ->
                object : MessageNodeDAO by delegate {
                    override suspend fun getNodesOfConversationPaged(
                        conversationId: String, limit: Int, offset: Int,
                    ): List<MessageNodeEntity> {
                        offsets += offset
                        if (offset == 64) throw failure
                        return delegate.getNodesOfConversationPaged(conversationId, limit, offset)
                    }
                }
            }.use { fixture ->
                val original = conversation()
                fixture.repository.insertConversation(original)
                val before = fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString())
                val result = runCatching { fixture.repository.getConversationById(original.id) }
                assertTrue(result.isFailure)
                assertNull(result.getOrNull())
                assertEquals(listOf(0, 64), offsets)
                assertEquals("对话加载失败，原记录未改动。请返回后重试。", result.exceptionOrNull()?.message)
                assertNull(result.exceptionOrNull()?.cause)
                assertFalse(result.exceptionOrNull().toString().contains("synthetic-private-payload"))
                assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString()))
            }
        }
    }

    @Test fun malformedJsonFailsWholeLoadWithoutExposingItsTextOrChangingRows() = runBlocking {
        Fixture().use { fixture ->
            val original = conversation()
            fixture.repository.insertConversation(original)
            fixture.db.messageNodeDao().updateMessages(original.id.toString(),
                original.messageNodes[65].id.toString(), "[synthetic-private-payload")
            val before = fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString())
            val failure = runCatching { fixture.repository.getConversationById(original.id) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertNull(failure?.cause)
            assertFalse(failure.toString().contains("synthetic-private-payload"))
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString()))
        }
    }

    @Test fun emptyOrInvalidSelectedNodeIsNotSilentlyFilteredFromHistory() = runBlocking {
        for (empty in listOf(true, false)) Fixture().use { fixture ->
            val original = conversation(2)
            fixture.repository.insertConversation(original)
            val row = fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString()).last()
            fixture.db.messageNodeDao().update(if (empty) row.copy(messages = "[]") else row.copy(selectIndex = 3))
            val before = fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString())
            assertTrue(runCatching { fixture.repository.getConversationById(original.id) }.isFailure)
            assertEquals(before, fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString()))
        }
    }

    @Test fun cancellationIsPropagatedRatherThanReportedAsCorruptHistory() = runBlocking {
        val cancelled = CancellationException("synthetic cancellation")
        Fixture { delegate ->
            object : MessageNodeDAO by delegate {
                override suspend fun getNodesOfConversationPaged(
                    conversationId: String, limit: Int, offset: Int,
                ): List<MessageNodeEntity> = throw cancelled
            }
        }.use { fixture ->
            val original = conversation(1)
            fixture.repository.insertConversation(original)
            val failure = runCatching { fixture.repository.getConversationById(original.id) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, fixture.db.messageNodeDao().getNodesOfConversation(original.id.toString()).size)
        }
    }

    @Test fun missingConversationRemainsAValidNewConversationCase() = runBlocking {
        Fixture().use { fixture -> assertNull(fixture.repository.getConversationById(Uuid.random())) }
    }
}
