package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic in-memory Room only; no phone database, real conversations, network or tool execution. */
@RunWith(AndroidJUnit4::class)
class OrbisHistoryCachePersistenceTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT,node_id TEXT,message_id TEXT,conversation_id TEXT,title TEXT,update_at TEXT)")
                }
            }).build()
        val scope = AppScope()
        val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        val compact = OrbisCompactionRepository(db, MessageFtsManager(db))
        suspend fun load(id: Uuid) = repo.getConversationById(id)!!
        override fun close() { scope.cancel(); db.close() }
    }
    private fun source() = Conversation(assistantId = Uuid.random(), title = "synthetic history",
        messageNodes = (0..5).map { UIMessage.user("original $it").toMessageNode() })
    private suspend fun compact(f: Fixture, before: Conversation, keep: Int): Conversation {
        val summary = UIMessage.assistant("synthetic summary").copy(parts = listOf(UIMessagePart.Text(
            "synthetic summary", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) }))).toMessageNode()
        return f.compact.commit(before, listOf(summary) + before.messageNodes.takeLast(keep),
            OrbisCompactionMetadata(summary.currentMessage.id, "synthetic summary", keep, 1000, 100,
                "synthetic", "synthetic")).conversation
    }
    @Test fun deletionCacheCommitsWithPageAndSurvivesReload() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            val current = f.load(before.id)
            val listed = f.repo.listRemovedHistory(current, 20, 0)
            assertEquals(before.currentMessages.first().id.toString(), listed.single().messageId)
            assertEquals(before.currentMessages.first(), f.repo.readRemovedHistory(current, listed.single().messageId))
            assertEquals(before.messageNodes.drop(1), current.messageNodes)
        }
    }
    @Test fun alternativeSelectionAndMetadataCasAreNotDeletionsButRemovingAlternativeIs() = runBlocking {
        Fixture().use { f ->
            val first = UIMessage.assistant("branch A")
            val second = UIMessage.assistant("branch B")
            val before = source().copy(messageNodes = listOf(first.toMessageNode().copy(messages = listOf(first, second))))
            f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.map { it.copy(selectIndex = 1) }))
            var current = f.load(before.id)
            assertTrue(f.repo.listRemovedHistory(current, 20, 0).isEmpty())
            val changed = current.copy(messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.copy(orbisVoiceCallId = null, orbisVoiceCallKind = null) })
            })
            assertNotNull(f.repo.commitHumanContinuation(current, changed))
            current = f.load(before.id)
            assertTrue(f.repo.listRemovedHistory(current, 20, 0).isEmpty())
            f.repo.updateConversation(current.copy(messageNodes = listOf(current.messageNodes.single().copy(
                messages = listOf(second), selectIndex = 0))))
            val final = f.load(before.id)
            assertEquals(first, f.repo.readRemovedHistory(final, first.id.toString()))
        }
    }
    @Test fun compactionDropsEarlierDeletedTurnAndKeepsOnlyDeletedTurnInsideRetainedSuffix() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            // Tombstones at positions 1 and 4; compact retains originals 3 and 5.
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.filterIndexed { i, _ -> i !in setOf(1, 4) }))
            val current = f.load(before.id)
            assertEquals(2, f.repo.listRemovedHistory(current, 20, 0).size)
            val after = compact(f, current, 2)
            assertNull(f.repo.readRemovedHistory(after, before.currentMessages[1].id.toString()))
            assertEquals(before.currentMessages[4], f.repo.readRemovedHistory(after, before.currentMessages[4].id.toString()))
            assertEquals(1, f.repo.listRemovedHistory(after, 20, 0).size)
        }
    }
    @Test fun noRetainedOriginalsAndRollbackCannotResurrectDeletedCache() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            val compacted = compact(f, f.load(before.id), 0)
            assertNull(f.db.orbisHistoryCacheDao().scope(before.id.toString()))
            val restored = f.compact.rollbackLatest(compacted, 0, { 1000 }).conversation
            assertTrue(f.repo.listRemovedHistory(restored, 20, 0).isEmpty())
            assertNull(f.repo.readRemovedHistory(restored, before.currentMessages.first().id.toString()))
        }
    }
    @Test fun failedPageWriteRollsBackDeletedCacheToo() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_history BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1))) }.isFailure)
            assertEquals(before.messageNodes, f.load(before.id).messageNodes)
            assertNull(f.db.orbisHistoryCacheDao().scope(before.id.toString()))
        }
    }
    @Test fun failedCompactionRestoresPageAndItsUnprunedCacheAtomically() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            val current = f.load(before.id)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_compact BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { compact(f, current, 0) }.isFailure)
            assertEquals(current.messageNodes, f.load(before.id).messageNodes)
            assertEquals(before.currentMessages.first(), f.repo.readRemovedHistory(current, before.currentMessages.first().id.toString()))
        }
    }
    @Test fun bulkDeleteAllKeepsOnlyWindowCacheUntilNewTurnsAreCompacted() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            val preview = prepareOrbisMessageBatch(before, before.messageNodes.map { it.id }.toSet(), OrbisMessageBatchOperation.DELETE)
            val empty = f.compact.commitMessageBatch(before, preview).conversation
            assertTrue(empty.messageNodes.isEmpty())
            assertEquals(6, f.repo.listRemovedHistory(empty, 20, 0).size)
            val next = empty.copy(messageNodes = listOf(UIMessage.user("new turn").toMessageNode()))
            f.repo.updateConversation(next)
            val compacted = compact(f, f.load(before.id), 1)
            assertTrue(f.repo.listRemovedHistory(compacted, 20, 0).isEmpty())
        }
    }
    @Test fun deletingWholeWindowCascadesCacheAndChangingOwnerNeverSharesIt() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            val current = f.load(before.id)
            assertTrue(runCatching { f.repo.listRemovedHistory(current.copy(assistantId = Uuid.random()), 20, 0) }.isFailure)
            // Cascade is tested through the real conversation table, without attachment deletion.
            f.db.conversationDao().delete(encodeConversationEntity(current))
            assertNull(f.db.orbisHistoryCacheDao().scope(before.id.toString()))
            assertTrue(f.db.orbisHistoryCacheDao().listRemoved(before.id.toString(), 20, 0).isEmpty())
        }
    }
    @Test fun compactedEpochRejectsStaleReaderEvenBeforeDisplayedStateUpdates() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            val after = compact(f, before, 1)
            assertTrue(runCatching { f.repo.verifyCurrentHistoryScope(before) }.isFailure)
            f.repo.verifyCurrentHistoryScope(after)
        }
    }
    @Test fun removingSyntheticSummaryDoesNotCreateDeletedHumanHistory() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            val after = compact(f, before, 1)
            f.repo.updateConversation(after.copy(messageNodes = after.messageNodes.drop(1)))
            assertTrue(f.repo.listRemovedHistory(f.load(before.id), 20, 0).isEmpty())
        }
    }
    @Test fun ownerChangeAndDeletionInSameSaveCannotTransferDeletedHistory() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(assistantId = Uuid.random(), messageNodes = before.messageNodes.drop(1)))
            val current = f.load(before.id)
            assertTrue(f.repo.listRemovedHistory(current, 20, 0).isEmpty())
            assertNull(f.repo.readRemovedHistory(current, before.currentMessages.first().id.toString()))
        }
    }
    @Test fun legacyOversizedDeletedMessageUsesBoundedCellsAndRemainsReadable() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            val old = before.currentMessages.first().copy(parts = listOf(UIMessagePart.Text("synthetic".repeat(300_000))))
            f.db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages=? WHERE id=?",
                arrayOf(JsonInstant.encodeToString(listOf(old)), before.messageNodes.first().id.toString()))
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            val current = f.load(before.id)
            val chunks = f.db.orbisHistoryCacheDao().removedChunks(before.id.toString(), old.id.toString())
            assertTrue(chunks.size > 1)
            assertTrue(chunks.all { it.body.toByteArray(Charsets.UTF_8).size < 64 * 1024 })
            assertEquals(old, f.repo.readRemovedHistory(current, old.id.toString()))
            f.db.openHelper.writableDatabase.execSQL("DELETE FROM orbis_history_removed WHERE conversation_id=? AND chunk_index=1",
                arrayOf(before.id.toString()))
            assertTrue(runCatching { f.repo.readRemovedHistory(current, old.id.toString()) }.isFailure)
        }
    }
    @Test fun consultationExclusionDeleteCascadesAllCacheTables() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            f.db.openHelper.writableDatabase.execSQL("UPDATE conversationentity SET consultation_binding='synthetic' WHERE id=?",
                arrayOf(before.id.toString()))
            f.db.openHelper.writableDatabase.execSQL("DELETE FROM conversationentity WHERE consultation_binding != ''")
            assertNull(f.db.orbisHistoryCacheDao().scope(before.id.toString()))
            assertTrue(f.db.orbisHistoryCacheDao().slots(before.id.toString()).isEmpty())
            assertTrue(f.db.orbisHistoryCacheDao().listRemoved(before.id.toString(), 20, 0).isEmpty())
        }
    }
    @Test fun invalidDeletedPayloadRollsBackAndNeverExposesDecoderBodyOrCause() = runBlocking {
        Fixture().use { f ->
            val before = source(); f.repo.insertConversation(before)
            val malformed = "[\"synthetic-private-marker\"]"
            val firstId = before.messageNodes.first().id.toString()
            f.db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages=? WHERE id=?",
                arrayOf(malformed, firstId))
            val error = runCatching {
                f.repo.updateConversation(before.copy(messageNodes = before.messageNodes.drop(1)))
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals("history_cache_source_decode_failed", error!!.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains("synthetic-private-marker"))
            assertNull(f.db.orbisHistoryCacheDao().scope(before.id.toString()))
            assertEquals(before.messageNodes.size,
                f.db.messageNodeDao().getNodeStorageMetadataOfConversation(before.id.toString()).size)
            f.db.openHelper.readableDatabase.query("SELECT messages FROM message_node WHERE id=?", arrayOf(firstId)).use {
                assertTrue(it.moveToFirst())
                assertEquals(malformed, it.getString(0))
            }
        }
    }
}
