package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.TokenUsage
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

/** In-memory synthetic Room only. Never opens production history, a queue, files or a model. */
@RunWith(AndroidJUnit4::class)
class OrbisMessageBatchPersistenceTest {
    private class Fixture : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        val repository = OrbisCompactionRepository(db, MessageFtsManager(db))
        suspend fun save(c: Conversation, insert: Boolean = false) {
            if (insert) db.conversationDao().insert(encodeConversationEntity(c)) else db.conversationDao().update(encodeConversationEntity(c))
            db.messageNodeDao().deleteByConversation(c.id.toString())
            db.messageNodeDao().insertAll(c.messageNodes.mapIndexed { index, node ->
                MessageNodeEntity(node.id.toString(), c.id.toString(), index, JsonInstant.encodeToString(node.messages), node.selectIndex)
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
    private fun source() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = List(4) { UIMessage.user("synthetic $it").toMessageNode() })
    private suspend fun batch(f: Fixture, c: Conversation, ids: Set<Uuid>, op: OrbisMessageBatchOperation = OrbisMessageBatchOperation.DELETE) =
        f.repository.commitMessageBatch(c, prepareOrbisMessageBatch(c, ids, op))
    private suspend fun compact(f: Fixture, c: Conversation): Conversation {
        val p = prepareOrbisManualContext(c, 2)
        return f.repository.commit(c, p.replacementNodes, p.metadata).conversation
    }
    private fun assertStored(a: Conversation, b: Conversation) = assertEquals(manualContextFingerprint(a), manualContextFingerprint(b))

    @Test fun entirePageDeleteKeepsWindowAndDoesNotInventRollbackSlot() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true)
            val deleted = batch(f, c, c.messageNodes.map { it.id }.toSet()).conversation
            assertTrue(deleted.messageNodes.isEmpty()); assertEquals(c.compactionEpoch + 1, deleted.compactionEpoch)
            assertStored(deleted, f.load(c.id)); assertEquals(1, f.db.conversationDao().getAllIds().size)
            assertNull(f.db.orbisCompactionDao().getRollback(c.id.toString()))
            assertTrue(f.repository.listHistory(c.assistantId).isEmpty())
        }
    }

    @Test fun retainedUsageAndAllAlternativeInvalidationFlagsSurviveRoomRoundTrip() = runBlocking {
        Fixture().use { f ->
            val branch = MessageNode(messages = listOf(
                UIMessage.assistant("a").copy(usage = TokenUsage(promptTokens = 100)),
                UIMessage.assistant("b").copy(usage = TokenUsage(promptTokens = 200))), selectIndex = 1)
            val c = source().let { it.copy(messageNodes = it.messageNodes + branch) }; f.save(c, true)
            batch(f, c, setOf(c.messageNodes.first().id))
            val stored = f.load(c.id).messageNodes.last()
            assertEquals(branch.messages.map { it.usage }, stored.messages.map { it.usage })
            assertTrue(stored.messages.all { it.usageContextInvalidated })
            assertEquals(branch.selectIndex, stored.selectIndex)
        }
    }

    @Test fun deletedRetainedNodeDoesNotResurrectAndCompactedHistoryStillRestores() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true); val active = compact(f, c)
            val oldSlot = f.db.orbisCompactionDao().getRollback(c.id.toString())!!
            val backups = f.db.orbisCompactionDao().getBackupNodes(c.id.toString(), 100, 0)
            val deletedId = c.messageNodes[2].id
            val deleted = batch(f, active, setOf(deletedId)).conversation
            val advanced = f.db.orbisCompactionDao().getRollback(c.id.toString())!!
            assertEquals(oldSlot.copy(compactionEpoch = deleted.compactionEpoch), advanced)
            assertEquals(backups, f.db.orbisCompactionDao().getBackupNodes(c.id.toString(), 100, 0))
            val restored = f.repository.rollbackLatest(deleted, 0, { 100L }).conversation
            assertEquals(c.messageNodes.filterNot { it.id == deletedId }.map { it.id }, restored.messageNodes.map { it.id })
            assertStored(restored, f.load(c.id)); assertNull(f.repository.latestRollback(c.id, c.assistantId))
        }
    }

    @Test fun deletingSummaryAndLaterNewNodesPreservesOnlyTheOldCompactionUndo() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true); val compacted = compact(f, c)
            val later = UIMessage.user("synthetic later").toMessageNode()
            val appended = compacted.copy(messageNodes = compacted.messageNodes + later); f.save(appended)
            val deleted = batch(f, appended, setOf(compacted.messageNodes.first().id, later.id)).conversation
            val restored = f.repository.rollbackLatest(deleted, 0, { 100L }).conversation
            assertEquals(c.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
            assertFalse(restored.messageNodes.any { it.id == later.id })
        }
    }

    @Test fun deletingAllActiveNodesStillAllowsRestoringOnlyPreviouslyCompactedHistory() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true); val compacted = compact(f, c)
            val deleted = batch(f, compacted, compacted.messageNodes.map { it.id }.toSet()).conversation
            assertTrue(deleted.messageNodes.isEmpty())
            val restored = f.repository.rollbackLatest(deleted, 0, { 100L }).conversation
            assertEquals(c.messageNodes.take(2).map { it.id }, restored.messageNodes.map { it.id })
        }
    }

    @Test fun staleStoredConversationRejectsDeleteOrArchiveWithoutPartialWrites() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true)
            val changed = c.copy(messageNodes = c.messageNodes + UIMessage.user("synthetic concurrent append").toMessageNode())
            f.save(changed)
            OrbisMessageBatchOperation.entries.forEach { op ->
                assertTrue(runCatching { batch(f, c, setOf(c.messageNodes.first().id), op) }
                    .exceptionOrNull() is OrbisCompactionConflictException)
                assertStored(changed, f.load(c.id)); assertEquals(1, f.db.conversationDao().getAllIds().size)
                assertNull(f.repository.latestRollback(c.id, c.assistantId))
            }
        }
    }

    @Test fun lostRollbackEpochCasRollsBackTheWholeDeletionWithoutDroppingBackups() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true); val active = compact(f, c)
            val slot = f.db.orbisCompactionDao().getRollback(c.id.toString())!!
            val backups = f.db.orbisCompactionDao().getBackupNodes(c.id.toString(), 100, 0)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_refuse_epoch BEFORE UPDATE ON orbis_compaction_rollback BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(runCatching { batch(f, active, setOf(active.messageNodes.last().id)) }.isFailure)
            assertStored(active, f.load(c.id)); assertEquals(slot, f.db.orbisCompactionDao().getRollback(c.id.toString()))
            assertEquals(backups, f.db.orbisCompactionDao().getBackupNodes(c.id.toString(), 100, 0))
        }
    }

    @Test fun archiveKeepsFullOriginalIncludingUnselectedBranchesAndAttachments() = runBlocking {
        Fixture().use { f ->
            val node = MessageNode(messages = listOf(UIMessage.assistant("synthetic one"),
                UIMessage.assistant("synthetic two").copy(parts = listOf(UIMessagePart.Image("file:///synthetic/retained.png")))), selectIndex = 1)
            val c = source().let { it.copy(messageNodes = it.messageNodes + node) }; f.save(c, true)
            val result = batch(f, c, setOf(c.messageNodes[1].id, node.id), OrbisMessageBatchOperation.ARCHIVE)
            val archive = f.load(checkNotNull(result.result.archiveId))
            assertEquals(c.messageNodes.size, archive.messageNodes.size)
            c.messageNodes.zip(archive.messageNodes).forEach { (old, fresh) ->
                assertNotEquals(old.id, fresh.id); assertEquals(old.selectIndex, fresh.selectIndex)
                assertEquals(old.messages.map { it.parts }, fresh.messages.map { it.parts })
            }
            assertFalse(result.conversation.messageNodes.any { it.id == node.id })
            val restored = f.repository.rollbackLatest(result.conversation, 0, { 100L }).conversation
            assertEquals(c.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
            assertStored(archive, f.load(archive.id))
        }
    }

    @Test fun archiveFailureAfterInsertRollsBackArchiveActivePageAndFtsTogether() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_active BEFORE INSERT ON message_fts WHEN NEW.conversation_id = '${c.id}' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            assertTrue(runCatching { batch(f, c, setOf(c.messageNodes[1].id), OrbisMessageBatchOperation.ARCHIVE) }.isFailure)
            assertStored(c, f.load(c.id)); assertEquals(listOf(c.id.toString()), f.db.conversationDao().getAllIds())
            assertNull(f.repository.latestRollback(c.id, c.assistantId)); assertTrue(f.repository.listHistory(c.assistantId).isEmpty())
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun failedPageWriteAlsoRollsBackTheAdvancedExistingSlotEpoch() = runBlocking {
        Fixture().use { f ->
            val c = source(); f.save(c, true); val active = compact(f, c)
            val slot = f.db.orbisCompactionDao().getRollback(c.id.toString())!!
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_delete_fts BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            assertTrue(runCatching { batch(f, active, setOf(active.messageNodes.last().id)) }.isFailure)
            assertStored(active, f.load(c.id)); assertEquals(slot, f.db.orbisCompactionDao().getRollback(c.id.toString()))
        }
    }
}
