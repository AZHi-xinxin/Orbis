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
    private suspend fun compact(f: Fixture, before: Conversation, summary: String = "I wrote this synthetic summary", keep: Int = 2,
        captureLocalMemory: Boolean = false, retainOriginals: Boolean = false, preserveBranch: Boolean = false): OrbisCompactionCommit {
        val start = UIMessage.assistant(summary).toMessageNode()
        return f.repository.commit(before, listOf(start) + before.messageNodes.takeLast(keep),
            OrbisCompactionMetadata(start.currentMessage.id, summary, keep, 350_000, 2_000, "synthetic estimate", "synthetic estimate",
                captureLocalMemory = captureLocalMemory, retainOriginals = retainOriginals, preserveBranch = preserveBranch))
    }

    @Test fun branchDisabledOrEnabledDoesNotChangeCompactedContextOrReadOnlyRetention() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before)
            assertNull(first.preservedBranchId)
            assertEquals(1, f.db.conversationDao().getAllIds().size)
            val second = compact(f, first.conversation, preserveBranch = true)
            val archive = f.load(checkNotNull(second.preservedBranchId))
            assertEquals(2, f.db.conversationDao().getAllIds().size)
            assertEquals(first.conversation.currentMessages.map { it.parts }, archive.currentMessages.map { it.parts })
            assertEquals(first.conversation.assistantId, archive.assistantId)
            assertEquals(0L, archive.compactionEpoch)
            assertEquals("synthetic window · AI 压缩前原文", archive.title)
            assertEquals(3, second.conversation.messageNodes.size)
            assertEquals(first.conversation.messageNodes.takeLast(2), second.conversation.messageNodes.drop(1))
            assertEquals(0, f.repository.originalArchiveStats(before.assistantId).count)
            assertNull(f.repository.latestRollback(archive.id, archive.assistantId))
            assertStored(second.conversation, f.load(before.id))
            // It is a normal conversation and can persist a new human turn independently.
            val continued = archive.copy(messageNodes = archive.messageNodes + UIMessage.user("continue here").toMessageNode())
            f.save(continued)
            assertStored(continued, f.load(archive.id))
            assertStored(second.conversation, f.load(before.id))
        }
    }

    @Test fun failedBranchInsertOrLaterActiveWriteKeepsOriginalAndNoOrphanWindow() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_branch BEFORE INSERT ON ConversationEntity BEGIN SELECT RAISE(ABORT, 'synthetic branch failure'); END")
            assertTrue(runCatching { compact(f, before, preserveBranch = true) }.isFailure)
            assertStored(before, f.load(before.id))
            assertEquals(listOf(before.id.toString()), f.db.conversationDao().getAllIds())
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_branch")
            // The branch (including FTS) is inserted first; failing the later main write undoes both.
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_active_fts BEFORE INSERT ON message_fts WHEN NEW.conversation_id = '${before.id}' BEGIN SELECT RAISE(ABORT, 'synthetic active failure'); END")
            assertTrue(runCatching { compact(f, before, preserveBranch = true) }.isFailure)
            assertStored(before, f.load(before.id))
            assertEquals(listOf(before.id.toString()), f.db.conversationDao().getAllIds())
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_fts").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun repeatedOldCommitCreatesNoDuplicateAndOldBranchSurvivesLaterCompactionAndDeletion() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before, preserveBranch = true, retainOriginals = true)
            val archive = f.load(checkNotNull(first.preservedBranchId))
            assertEquals(1, f.repository.originalArchiveStats(before.assistantId).count)
            assertTrue(runCatching { compact(f, before, preserveBranch = true) }.isFailure)
            assertEquals(2, f.db.conversationDao().getAllIds().size)
            val second = compact(f, first.conversation, preserveBranch = true)
            assertNotEquals(first.preservedBranchId, second.preservedBranchId)
            assertEquals(3, f.db.conversationDao().getAllIds().size)
            f.repository.deleteHistory(before.assistantId, first.event.id)
            f.repository.deleteHistory(before.assistantId, second.event.id)
            f.db.conversationDao().deleteById(before.id.toString())
            assertStored(archive, f.load(archive.id))
            assertEquals(2, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun branchPreservesAlternativesFilesAndCompletedLiveToolReceiptWithoutOldHostBindings() = runBlocking {
        Fixture().use { f ->
            val url = "file:///synthetic/branch-only.png"
            val image = UIMessage.user("").copy(parts = listOf(UIMessagePart.Image(url)))
            val before = original().let { it.copy(messageNodes = listOf(MessageNode(
                messages = listOf(UIMessage.user("alternative"), image), selectIndex = 1)) + it.messageNodes) }
            f.save(before, true)
            val tool = UIMessage.assistant("live summary").copy(orbisVoiceCallId = "voice", orbisVoiceCallKind = "turn",
                parts = listOf(UIMessagePart.Tool("already-done", "compact", "{}", listOf(UIMessagePart.Text("complete")))))
            val live = before.copy(messageNodes = before.messageNodes + tool.toMessageNode())
            val summary = UIMessage.assistant("summary").toMessageNode()
            val result = f.repository.commit(live, listOf(summary, live.messageNodes.last()),
                OrbisCompactionMetadata(summary.currentMessage.id, "summary", 1, 1000, 100, "test", "test", preserveBranch = true), before)
            val archive = f.load(checkNotNull(result.preservedBranchId))
            assertEquals(1, archive.messageNodes.first().selectIndex)
            assertEquals(2, archive.messageNodes.first().messages.size)
            assertEquals(live.currentMessages.map { it.parts }, archive.currentMessages.map { it.parts })
            assertNull(archive.currentMessages.last().orbisVoiceCallId)
            assertTrue(archive.currentMessages.last().getTools().single().isExecuted)
            assertNotEquals(tool.id, archive.currentMessages.last().id)
            compact(f, result.conversation) // Replace the single old undo slot.
            assertTrue(f.repository.retainedFileReferences(listOf(url)).isEmpty())
            assertTrue(f.db.messageNodeDao().hasFileReferenceOutsideConversation(JsonInstant.encodeToString(url), before.id.toString()))
        }
    }

    @Test fun consultationCompactionDoesNotCreateAnOrdinaryChatBranch() = runBlocking {
        Fixture().use { f ->
            val before = original().copy(consultation = ConsultationConversationBinding("session", "subject"))
            f.save(before, true)
            assertNull(compact(f, before, preserveBranch = true).preservedBranchId)
            assertEquals(1, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun optionalOriginalsDefaultOffAndDeleteDoesNotTouchCurrentOrRollback() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val first = compact(f, before)
            assertEquals(0, f.repository.originalArchiveStats(before.assistantId).count)
            val second = compact(f, first.conversation, retainOriginals = true)
            val id = second.event.id.toString()
            assertEquals(1, f.repository.originalArchives(before.assistantId).size)
            assertEquals(first.conversation.messageNodes.first(), f.repository.originalArchiveNode(before.assistantId, id, 0))
            assertTrue(runCatching { f.repository.originalArchiveNode(Uuid.random(), id, 0) }.isFailure)
            assertEquals(0, f.repository.deleteOriginalArchives(Uuid.random(), setOf(id)))
            assertEquals(1, f.repository.deleteOriginalArchives(before.assistantId, setOf(id)))
            assertEquals(0, f.repository.originalArchiveStats(before.assistantId).count)
            assertStored(second.conversation, f.load(before.id))
            assertEquals(second.event.id, f.repository.latestRollback(before.id, before.assistantId)!!.id)
        }
    }

    @Test fun originalsKeepBranchesAndAttachmentsAcrossLaterCompactionsAndOwnerScopedClear() = runBlocking {
        Fixture().use { f ->
            val url = "file:///synthetic/archive-only.png"
            val firstNode = UIMessage.user("synthetic original").toMessageNode().let { it.copy(messages = listOf(it.currentMessage,
                UIMessage.user("synthetic alternative").copy(parts = listOf(UIMessagePart.Image(url)))), selectIndex = 1) }
            val before = original().let { it.copy(messageNodes = listOf(firstNode) + it.messageNodes) }; f.save(before, true)
            val other = original(); f.save(other, true); compact(f, other, retainOriginals = true)
            val first = compact(f, before, retainOriginals = true)
            val second = compact(f, first.conversation, retainOriginals = true)
            assertEquals(firstNode, f.repository.originalArchiveNode(before.assistantId, first.event.id.toString(), 0))
            assertEquals(setOf(url), f.repository.retainedFileReferences(listOf(url)))
            assertEquals(2, f.repository.deleteOriginalArchives(before.assistantId, emptySet(), all = true))
            assertEquals(1, f.repository.originalArchiveStats(other.assistantId).count)
            assertTrue(f.repository.retainedFileReferences(listOf(url)).isEmpty())
            assertStored(second.conversation, f.load(before.id))
        }
    }

    @Test fun archiveInsertFailureRollsBackCompactionAndConversationDeletionCascadesArchive() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_archive BEFORE INSERT ON orbis_original_chunk BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { compact(f, before, retainOriginals = true) }.isFailure)
            assertStored(before, f.load(before.id)); assertEquals(0, f.repository.originalArchiveStats(before.assistantId).count)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_archive")
            compact(f, before, retainOriginals = true)
            f.db.conversationDao().deleteById(before.id.toString())
            assertEquals(0, f.repository.originalArchiveStats(before.assistantId).count)
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM orbis_original_chunk").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun aiCompactionSavesFullSummaryAsStaticAndManualDefaultDoesNot() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            val text = "完整原文".repeat(200)
            val result = compact(f, before, text, captureLocalMemory = true)
            val notes = f.db.orbisMemoryDao().search(before.assistantId.toString(), "", false, 10, 0)
            assertEquals(1, notes.size)
            assertEquals(text, notes.single().body)
            assertEquals("static", notes.single().state)
            assertEquals("", notes.single().summary)
            compact(f, result.conversation, "manual synthetic summary", keep = 1)
            assertEquals(1, f.db.orbisMemoryDao().stats(before.assistantId.toString()).total)
        }
    }

    @Test fun failedCompactionRollsBackMemoryCaptureToo() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_fts BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
            assertTrue(runCatching { compact(f, before, captureLocalMemory = true) }.isFailure)
            assertEquals(0, f.db.orbisMemoryDao().stats(before.assistantId.toString()).total)
            assertStored(before, f.load(before.id))
        }
    }

    @Test fun memoryWriteFailureKeepsWholeOriginalConversation() = runBlocking {
        Fixture().use { f ->
            val before = original(); f.save(before, true)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_memory BEFORE INSERT ON orbis_memory_note BEGIN SELECT RAISE(ABORT, 'synthetic memory failure'); END")
            assertTrue(runCatching { compact(f, before, captureLocalMemory = true) }.isFailure)
            assertStored(before, f.load(before.id))
            assertTrue(f.repository.listHistory(before.assistantId).isEmpty())
        }
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
