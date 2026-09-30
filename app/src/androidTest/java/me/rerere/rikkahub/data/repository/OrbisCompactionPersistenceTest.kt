package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid
import java.time.Instant

/** Entirely in-memory Room, synthetic messages and fake FTS table; no Koin, cloud or real files. */
@RunWith(AndroidJUnit4::class)
class OrbisCompactionPersistenceTest {
    private fun assertStored(expected: Conversation, actual: Conversation) {
        fun normalize(value: Conversation) = value.copy(
            createAt = Instant.ofEpochMilli(value.createAt.toEpochMilli()),
            updateAt = Instant.ofEpochMilli(value.updateAt.toEpochMilli()),
        )
        assertEquals(normalize(expected), normalize(actual))
    }
    private class Fixture : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        val repository = OrbisCompactionRepository(db, MessageFtsManager(db))
        suspend fun save(conversation: Conversation, insert: Boolean = false) {
            if (insert) db.conversationDao().insert(encodeConversationEntity(conversation))
            else db.conversationDao().update(encodeConversationEntity(conversation))
            db.messageNodeDao().deleteByConversation(conversation.id.toString())
            db.messageNodeDao().insertAll(conversation.messageNodes.mapIndexed { index, node ->
                MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                    JsonInstant.encodeToString(node.messages), node.selectIndex)
            })
        }
        suspend fun load(id: Uuid): Conversation {
            val nodes = db.messageNodeDao().getNodesOfConversationPaged(id.toString(), 1000, 0).map {
                MessageNode(Uuid.parse(it.id), JsonInstant.decodeFromString<List<UIMessage>>(it.messages), it.selectIndex)
            }
            return decodeConversationEntity(db.conversationDao().getConversationById(id.toString())!!, nodes)
        }
        override fun close() = db.close()
    }
    private fun original(count: Int = 8) = Conversation(assistantId = Uuid.random(), title = "synthetic window",
        customSystemPrompt = "synthetic unchanged system", workspaceCwd = "/synthetic-workspace",
        messageNodes = (0 until count).map { i ->
            (if (i % 2 == 0) UIMessage.user("synthetic original $i") else UIMessage.assistant("synthetic reply $i")).toMessageNode()
        })
    private suspend fun compact(f: Fixture, before: Conversation, summary: String = "I wrote this synthetic summary", keep: Int = 2): OrbisCompactionCommit {
        val start = UIMessage.assistant(summary).toMessageNode()
        return f.repository.commit(before, listOf(start) + before.messageNodes.takeLast(keep),
            OrbisCompactionMetadata(start.currentMessage.id, summary, keep, 350_000, 2_000, "synthetic estimate", "synthetic estimate"))
    }

    @Test fun atomicCommitKeepsAssistantAuthorshipAndIndependentFields() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val result = compact(f, before)
            val stored = f.load(before.id)
            assertStored(result.conversation, stored)
            assertEquals(MessageRole.ASSISTANT, stored.currentMessages.first().role)
            assertEquals("I wrote this synthetic summary", stored.currentMessages.first().toText())
            assertEquals(before.messageNodes.takeLast(2), stored.messageNodes.drop(1))
            assertEquals(before.customSystemPrompt, stored.customSystemPrompt)
            assertEquals(before.workspaceCwd, stored.workspaceCwd)
            assertEquals(1L, stored.compactionEpoch)
            assertEquals(orbisCompactionSummaryHash(stored.currentMessages.first().toText()), result.event.summaryHash)
        }
    }

    @Test fun lateTransactionFailureRestoresWholeOriginalConversationAndLeavesNoEvent() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_fts BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
            assertTrue(runCatching { compact(f, before) }.isFailure)
            assertStored(before, f.load(before.id))
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
            assertNull(f.repository.latestRollback(before.id, before.assistantId))
        }
    }

    @Test fun failedSecondCommitDoesNotDestroyFirstRollbackSlot() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_fts BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { compact(f, first.conversation, "synthetic second summary", 1) }.isFailure)
            assertStored(first.conversation, f.load(before.id))
            assertEquals(first.event.id, f.repository.latestRollback(before.id, before.assistantId)!!.id)
            assertEquals(1, f.repository.listHistory(before.assistantId).size)
        }
    }

    @Test fun staleEpochAndChangedBaselineCannotOverwriteNewPage() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before)
            assertTrue(runCatching { compact(f, before) }.exceptionOrNull() is OrbisCompactionConflictException)
            val stale = first.conversation
            val edited = stale.copy(messageNodes = stale.messageNodes + UIMessage.user("synthetic later").toMessageNode())
            f.save(edited)
            assertTrue(runCatching { compact(f, stale) }.exceptionOrNull() is OrbisCompactionConflictException)
            assertStored(edited, f.load(before.id))
        }
    }

    @Test fun rollbackPreservesNewMessagesAndCanOnlyRunOnce() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val result = compact(f, before)
            val extra = UIMessage.user("synthetic after compaction").toMessageNode()
            val latest = result.conversation.copy(messageNodes = result.conversation.messageNodes + extra); f.save(latest)
            val restored = f.repository.rollbackLatest(latest, 350_000, { 399_999 }).conversation
            assertEquals(before.messageNodes + extra, restored.messageNodes)
            assertEquals(2L, restored.compactionEpoch)
            assertNull(f.repository.latestRollback(before.id, before.assistantId))
            assertTrue(runCatching { f.repository.rollbackLatest(restored, 0, { 1 }) }.isFailure)
            assertEquals("rolled_back", f.repository.listHistory(before.assistantId).single().status)
        }
    }

    @Test fun rollbackLimitFailureKeepsBothCurrentPageAndRollbackAvailable() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val result = compact(f, before)
            assertTrue(runCatching { f.repository.rollbackLatest(result.conversation, 350_000, { 400_001 }) }.exceptionOrNull() is OrbisRollbackLimitException)
            assertStored(result.conversation, f.load(before.id))
            assertNotNull(f.repository.latestRollback(before.id, before.assistantId))
            assertEquals(before.messageNodes, f.repository.rollbackLatest(result.conversation, 0, { 400_001 }).conversation.messageNodes)
        }
    }

    @Test fun secondCompactionReplacesSlotAndCannotReachEarlierHistory() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before)
            val second = compact(f, first.conversation, "synthetic second", 1)
            val events = f.repository.listHistory(before.assistantId)
            assertEquals(2, events.size)
            assertEquals(listOf(second.event.id), events.filter { it.rollbackAvailable }.map { it.id })
            val restored = f.repository.rollbackLatest(second.conversation, 0, { 100 }).conversation
            assertEquals(first.conversation.messageNodes, restored.messageNodes)
            assertNull(f.repository.latestRollback(before.id, before.assistantId))
        }
    }

    @Test fun historyDeletionIsOwnerScopedAndDoesNotDeleteActiveChat() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val result = compact(f, before)
            assertFalse(f.repository.deleteHistory(Uuid.random(), result.event.id))
            assertTrue(f.repository.listHistory(Uuid.random()).isEmpty())
            assertTrue(f.repository.deleteHistory(before.assistantId, result.event.id))
            assertStored(result.conversation, f.load(before.id))
            assertNull(f.repository.latestRollback(before.id, before.assistantId))
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
        }
    }

    @Test fun backupProtectsOnlyLatestAttachmentReferences() = runBlocking {
        Fixture().use { f ->
            val url = "file:///synthetic/original-attachment.png"
            val before = original().let { c -> c.copy(messageNodes = listOf(
                UIMessage.user("synthetic file").copy(parts = listOf(UIMessagePart.Image(url))).toMessageNode()
            ) + c.messageNodes) }
            f.save(before, true)
            val first = compact(f, before)
            assertEquals(setOf(url), f.repository.retainedFileReferences(listOf(url, "file:///synthetic/absent")))
            compact(f, first.conversation, "synthetic next", 1)
            assertTrue(f.repository.retainedFileReferences(listOf(url)).isEmpty())
        }
    }

    @Test fun severalMegabytesOfHistoryAreStoredPerNodeNotAsOneCursorBlob() = runBlocking {
        Fixture().use { f ->
            val before = original(160).let { c -> c.copy(messageNodes = c.messageNodes.map { node ->
                node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text("synthetic ".repeat(3500))))))
            }) }
            f.save(before, true)
            val result = compact(f, before)
            val restored = f.repository.rollbackLatest(result.conversation, 0, { 2_000_000 }).conversation
            assertEquals(before.messageNodes, restored.messageNodes)
        }
    }

    @Test fun currentUnpersistedAssistantRoundIsIncludedInRollbackBackup() = runBlocking {
        Fixture().use { f ->
            val baseline = original(); f.save(baseline, true)
            val live = baseline.copy(messageNodes = baseline.messageNodes + UIMessage.assistant("synthetic live tool round").toMessageNode())
            val summary = UIMessage.assistant("synthetic summary").toMessageNode()
            val result = f.repository.commit(live, listOf(summary) + live.messageNodes.takeLast(1),
                OrbisCompactionMetadata(summary.currentMessage.id, summary.currentMessage.toText(), 1, 1000, 100,
                    "synthetic estimate", "synthetic estimate"), persistedBaseline = baseline)
            val restored = f.repository.rollbackLatest(result.conversation, 0, { 1000 }).conversation
            assertEquals(live.messageNodes, restored.messageNodes)
        }
    }

    @Test fun foreignWindowNodeCannotBeReplacedThroughCompaction() = runBlocking {
        Fixture().use { f ->
            val before = original(); val other = original()
            f.save(before, true); f.save(other, true)
            val summary = UIMessage.assistant("synthetic summary").toMessageNode()
            assertTrue(runCatching {
                f.repository.commit(before, listOf(summary, other.messageNodes.first()),
                    OrbisCompactionMetadata(summary.currentMessage.id, summary.currentMessage.toText(), 1,
                        1000, 100, "synthetic estimate", "synthetic estimate"))
            }.isFailure)
            assertStored(before, f.load(before.id)); assertStored(other, f.load(other.id))
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
        }
    }

    @Test fun deletingConversationCascadesItsMetadataAndRollbackOnly() = runBlocking {
        Fixture().use { f ->
            val before = original(); val other = original()
            f.save(before, true); f.save(other, true)
            compact(f, before)
            f.db.conversationDao().deleteById(before.id.toString())
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
            assertNull(f.db.orbisCompactionDao().getRollback(before.id.toString()))
            assertTrue(f.db.orbisCompactionDao().getBackupNodes(before.id.toString(), 32, 0).isEmpty())
            assertStored(other, f.load(other.id))
        }
    }
}
