package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
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

/** Synthetic, in-memory Room only. No app singleton, real history, files, model, queue or sentry. */
@RunWith(AndroidJUnit4::class)
class OrbisManualContextPersistenceTest {
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
            db.messageNodeDao().insertAll(c.messageNodes.mapIndexed { i, n -> MessageNodeEntity(n.id.toString(), c.id.toString(), i,
                JsonInstant.encodeToString(n.messages), n.selectIndex) })
        }
        suspend fun load(id: Uuid): Conversation {
            val nodes = db.messageNodeDao().getNodesOfConversationPaged(id.toString(), 1000, 0).map {
                MessageNode(Uuid.parse(it.id), JsonInstant.decodeFromString<List<UIMessage>>(it.messages), it.selectIndex)
            }
            return decodeConversationEntity(db.conversationDao().getConversationById(id.toString())!!, nodes)
        }
        override fun close() = db.close()
    }
    private fun original() = Conversation(assistantId = Uuid.random(), title = "synthetic original",
        messageNodes = (0..5).map { UIMessage.user("synthetic message $it").toMessageNode() })
    private suspend fun manual(f: Fixture, c: Conversation, count: Int = 3): OrbisManualContextCommit {
        val p = prepareOrbisManualContext(c, count)
        return f.repository.commitManual(c, p.replacementNodes, p.metadata, p.expectedFingerprint)
    }
    private fun assertSameStored(a: Conversation, b: Conversation) = assertEquals(manualContextFingerprint(a), manualContextFingerprint(b))

    @Test fun archiveAndCompactedWindowCommitTogetherWithIndependentIdentities() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            val result = manual(f, c)
            val archive = f.load(result.archiveId)
            val active = f.load(c.id)
            assertSameStored(result.commit.conversation, active)
            assertEquals(2, f.db.conversationDao().getAllIds().size)
            assertEquals(c.assistantId, archive.assistantId)
            assertEquals(c.title + " · 原文存档", archive.title)
            assertEquals(c.messageNodes.size, archive.messageNodes.size)
            c.messageNodes.zip(archive.messageNodes).forEach { (old, fresh) ->
                assertNotEquals(old.id, fresh.id)
                assertEquals(old.messages.size, fresh.messages.size)
                old.messages.zip(fresh.messages).forEach { (a, b) -> assertNotEquals(a.id, b.id); assertEquals(a, b.copy(id = a.id)) }
            }
            assertNotNull(f.repository.latestRollback(c.id, c.assistantId))
            assertNull(f.repository.latestRollback(archive.id, archive.assistantId))
        }
    }

    @Test fun failureDuringActiveCommitAlsoRollsBackAlreadyInsertedArchiveAndFts() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            // Archive FTS succeeds; only the later original-window replacement fails.
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_active_fts BEFORE INSERT ON message_fts WHEN NEW.conversation_id = '${c.id}' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            assertTrue(runCatching { manual(f, c) }.isFailure)
            assertSameStored(c, f.load(c.id))
            assertEquals(listOf(c.id.toString()), f.db.conversationDao().getAllIds())
            assertTrue(f.repository.listHistory(c.assistantId).isEmpty())
            assertNull(f.repository.latestRollback(c.id, c.assistantId))
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun staleLastNodeIsRejectedInsteadOfUsingLiveAiCommitAllowance() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            val preview = prepareOrbisManualContext(c, 3)
            val changed = c.copy(messageNodes = c.messageNodes.dropLast(1) + c.messageNodes.last().let { n ->
                n.copy(messages = listOf(n.currentMessage.copy(parts = listOf(UIMessagePart.Text("synthetic edited latest"))))) })
            f.save(changed)
            assertTrue(runCatching { f.repository.commitManual(c, preview.replacementNodes, preview.metadata, preview.expectedFingerprint) }
                .exceptionOrNull() is OrbisCompactionConflictException)
            assertSameStored(changed, f.load(c.id))
            assertEquals(1, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun changedTitleOrOwnerRejectsPreviewWithoutCreatingArchive() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            val p = prepareOrbisManualContext(c, 3)
            val changed = c.copy(title = "synthetic renamed")
            f.save(changed)
            assertTrue(runCatching { f.repository.commitManual(c, p.replacementNodes, p.metadata, p.expectedFingerprint) }.isFailure)
            assertSameStored(changed, f.load(c.id))
            assertEquals(1, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun archivePreservesAlternateBranchesAndUsageWhileActiveClearsUsage() = runBlocking {
        Fixture().use { f ->
            val c = original().let { it.copy(messageNodes = it.messageNodes + MessageNode(messages = listOf(
                UIMessage.assistant("synthetic alternative 1").copy(usage = TokenUsage(promptTokens = 123)),
                UIMessage.assistant("synthetic alternative 2").copy(usage = TokenUsage(promptTokens = 456))), selectIndex = 1)) }
            f.save(c, true)
            val result = manual(f, c)
            val archived = f.load(result.archiveId).messageNodes.last()
            assertEquals(1, archived.selectIndex)
            assertEquals(listOf(123, 456), archived.messages.map { it.usage!!.promptTokens })
            assertTrue(f.load(c.id).messageNodes.last().messages.all { it.usage == null })
        }
    }

    @Test fun durableArchiveSurvivesLaterAiCompactionAndDeletionOfItsRollbackEvent() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            val first = manual(f, c)
            val archive = f.load(first.archiveId)
            val summary = UIMessage.assistant("synthetic AI-authored second summary").toMessageNode()
            val second = f.repository.commit(first.commit.conversation, listOf(summary),
                OrbisCompactionMetadata(summary.currentMessage.id, summary.currentMessage.toText(), 0, 1000, 10, "test", "test"))
            f.repository.deleteHistory(c.assistantId, second.event.id)
            assertSameStored(archive, f.load(archive.id))
            assertEquals(2, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun originalAttachmentRemainsReferencedAfterLaterSlotReplacement() = runBlocking {
        Fixture().use { f ->
            val url = "file:///synthetic/manual-kept-image.png"
            val c = original().let { it.copy(messageNodes = listOf(UIMessage.user("").copy(parts = listOf(UIMessagePart.Image(url))).toMessageNode()) + it.messageNodes) }
            f.save(c, true)
            val first = manual(f, c)
            val summary = UIMessage.assistant("synthetic second summary").toMessageNode()
            f.repository.commit(first.commit.conversation, listOf(summary),
                OrbisCompactionMetadata(summary.currentMessage.id, summary.currentMessage.toText(), 0, 1000, 10, "test", "test"))
            assertTrue(f.repository.retainedFileReferences(listOf(url)).isEmpty()) // Old undo slot really is gone.
            assertTrue(f.db.messageNodeDao().hasFileReference(JsonInstant.encodeToString(url))) // Independent archive still owns file.
            assertTrue(f.db.messageNodeDao().hasFileReferenceOutsideConversation(JsonInstant.encodeToString(url), c.id.toString()))
            assertFalse(f.db.messageNodeDao().hasFileReferenceOutsideConversation(JsonInstant.encodeToString(url), first.archiveId.toString()))
            assertEquals(url, (f.load(first.archiveId).currentMessages.first().parts.single() as UIMessagePart.Image).url)
        }
    }

    @Test fun malformedReplacementIsRejectedBeforeArchiveInsert() = runBlocking {
        Fixture().use { f ->
            val c = original(); f.save(c, true)
            val p = prepareOrbisManualContext(c, 3)
            assertTrue(runCatching { f.repository.commitManual(c, p.replacementNodes.dropLast(1), p.metadata, p.expectedFingerprint) }.isFailure)
            assertSameStored(c, f.load(c.id))
            assertEquals(1, f.db.conversationDao().getAllIds().size)
        }
    }

    @Test fun multiMegabyteManualArchiveAndRollbackRemainPerNodeAndExact() = runBlocking {
        Fixture().use { f ->
            val c = original().copy(messageNodes = (0 until 160).map { index ->
                UIMessage.user("synthetic $index " + "long text ".repeat(3500)).toMessageNode()
            })
            f.save(c, true)
            val result = manual(f, c, 150)
            assertEquals(11, f.load(c.id).messageNodes.size)
            val archive = f.load(result.archiveId)
            assertEquals(160, archive.messageNodes.size)
            assertEquals(c.currentMessages.map { it.parts }, archive.currentMessages.map { it.parts })
            val restored = f.repository.rollbackLatest(result.commit.conversation, 0, { 2_000_000 }).conversation
            assertEquals(c.messageNodes, restored.messageNodes)
            assertSameStored(archive, f.load(result.archiveId))
        }
    }
}
