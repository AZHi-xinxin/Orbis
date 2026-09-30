package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.service.createForkConversation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Production Room repository + real FTS extension, in an isolated temporary database.
 * Same fixture boundary as RikkaChatImporterTest: never the app DB, Koin or ChatService.
 * No device state, sender, actual event inbox, model request or user attachment is touched.
 */
@RunWith(AndroidJUnit4::class)
class OrbisEventTargetPersistenceTest {
    private lateinit var rootCache: File
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var scope: AppScope
    private val owner = Uuid.parse("00000000-0000-4000-8000-000000000011")
    private val otherOwner = Uuid.parse("00000000-0000-4000-8000-000000000012")
    private val conversationId = Uuid.parse("00000000-0000-4000-8000-000000000013")
    private val eventText = "  Synthetic external event\n保留原文与空白。  "

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val base = instrumentation.targetContext
        assertEquals(Application::class.java, base.applicationContext.javaClass)
        rootCache = base.cacheDir.canonicalFile
        directory = Files.createTempDirectory(rootCache.toPath(), "orbis-event-owner-test-").toFile().canonicalFile
        val isolatedContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File {
                val path = (if (File(name).isAbsolute) File(name) else File(directory, "db/$name")).canonicalFile
                check(path.toPath().startsWith(directory.toPath())) { "isolated_database_path_required" }
                path.parentFile!!.mkdirs()
                return path
            }
        }
        database = AppDatabaseFactory.create(isolatedContext, "event-target-test")
        scope = AppScope()
        val files = FilesManager(isolatedContext, FilesRepository(database.managedFileDao()), scope)
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
    }

    @After fun cleanup() {
        if (::scope.isInitialized) scope.cancel()
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) {
            check(directory.parentFile == rootCache && directory.name.startsWith("orbis-event-owner-test-"))
            check(directory.deleteRecursively()) { "isolated_test_cleanup_failed" }
        }
    }

    private fun original() = Conversation(
        id = conversationId,
        assistantId = owner,
        title = "Synthetic fixed target",
        messageNodes = listOf(UIMessage.user("Synthetic original history").toMessageNode()),
        orbisPrompt = OrbisConversationPrompt(text = "Initial synthetic prompt", enabled = true),
    )

    private fun withQueuedEvent(conversation: Conversation): Conversation {
        val message = UIMessage(
            id = Uuid.parse("00000000-0000-4000-8000-000000000014"),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(eventText)),
            orbisEvent = OrbisEventMetadata("synthetic-receipt", "lc_sentinel", "synthetic-event", 1_000L, occurredAt = 900L),
        )
        return conversation.copy(messageNodes = conversation.messageNodes + message.toMessageNode())
    }

    private suspend fun assertTargetRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("An unavailable or reassigned target must reject the event update")
        } catch (error: IllegalStateException) {
            assertEquals("event_target_missing_or_changed", error.message)
        }
    }

    private fun indexedText(): List<String> = database.openHelper.readableDatabase.query(
        "SELECT text FROM message_fts WHERE conversation_id = ? ORDER BY message_id",
        arrayOf(conversationId.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Test fun deletedTargetRejectsQueuedSnapshotWithoutResurrectingRowsOrIndex() = runBlocking {
        val original = original()
        repository.insertConversation(original)
        val queuedSnapshot = withQueuedEvent(original)
        repository.deleteConversation(original)

        assertTargetRejected { repository.updateConversation(queuedSnapshot, requireExistingOwner = owner) }

        assertNull(repository.getConversationById(conversationId))
        assertEquals(0, repository.countConversations())
        assertTrue(database.messageNodeDao().getNodesOfConversation(conversationId.toString()).isEmpty())
        assertTrue(indexedText().isEmpty())
    }

    @Test fun reassignedTargetRejectsOldOwnerWithoutReplacingNewOwnerMessagesOrIndex() = runBlocking {
        val original = original()
        repository.insertConversation(original)
        val stored = requireNotNull(database.conversationDao().getConversationById(conversationId.toString()))
        database.conversationDao().update(stored.copy(assistantId = otherOwner.toString(), title = "Reassigned synthetic target"))
        val before = requireNotNull(repository.getConversationById(conversationId))
        val beforeNodes = database.messageNodeDao().getNodesOfConversation(conversationId.toString())
        val beforeIndex = indexedText()

        assertTargetRejected { repository.updateConversation(withQueuedEvent(original), requireExistingOwner = owner) }

        val after = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(otherOwner, after.assistantId)
        assertEquals(before, after)
        assertEquals(beforeNodes, database.messageNodeDao().getNodesOfConversation(conversationId.toString()))
        assertEquals(beforeIndex, indexedText())
        assertFalse(indexedText().contains(eventText))
    }

    @Test fun matchingOwnerSavesOriginalEventAndRealIndexWhilePreservingCommittedPrompt() = runBlocking {
        val original = original()
        repository.insertConversation(original)
        val committedPrompt = OrbisConversationPrompt(text = "New committed synthetic prompt", enabled = true,
            worldBookText = "Committed synthetic world", worldBookEnabled = true)
        repository.saveOrbisPrompt(original, committedPrompt)
        // Simulate an event snapshot captured before the separate prompt edit committed.
        val staleQueuedSnapshot = withQueuedEvent(original)

        repository.updateConversation(staleQueuedSnapshot, requireExistingOwner = owner)

        val saved = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(owner, saved.assistantId)
        assertEquals(committedPrompt, saved.orbisPrompt)
        assertEquals(staleQueuedSnapshot.messageNodes, saved.messageNodes)
        assertEquals(eventText, saved.currentMessages.last().toText())
        assertEquals(staleQueuedSnapshot.currentMessages.last().orbisEvent, saved.currentMessages.last().orbisEvent)
        assertEquals(2, database.messageNodeDao().getNodesOfConversation(conversationId.toString()).size)
        assertEquals(setOf("Synthetic original history", eventText), indexedText().toSet())
        assertEquals(1, repository.countConversations())
    }

    private fun presentationEdit(conversation: Conversation) = OrbisEventPresentationEdit(
        nodeId = conversation.messageNodes.last().id,
        messageId = conversation.currentMessages.last().id,
        expected = requireNotNull(conversation.currentMessages.last().orbisEvent),
        originalText = eventText,
        read = true,
        collapsed = false,
    )

    private suspend fun assertPresentationRejected(block: suspend () -> Unit) {
        try { block(); fail("presentation target must reject") }
        catch (error: IllegalStateException) {
            assertTrue(error.message in setOf("event_target_missing_or_changed", "event_message_missing_or_changed"))
        }
    }

    @Test fun presentationCommitChangesOnlyTwoFlagsWithoutActivityOrIndexChanges() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val beforeConversation = database.conversationDao().getConversationById(conversationId.toString())
        val beforeNodes = database.messageNodeDao().getNodesOfConversation(conversationId.toString())
        val beforeIndex = indexedText()
        val edit = presentationEdit(original)

        repository.saveOrbisEventPresentation(conversationId, owner, edit)

        val restored = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(beforeConversation, database.conversationDao().getConversationById(conversationId.toString()))
        assertEquals(original.updateAt.toEpochMilli(), restored.updateAt.toEpochMilli())
        assertEquals(beforeNodes.first(), database.messageNodeDao().getNodesOfConversation(conversationId.toString()).first())
        assertEquals(beforeIndex, indexedText())
        assertEquals(original.currentMessages.last().copy(orbisEvent = edit.expected.copy(read = true, collapsed = false)), restored.currentMessages.last())
        assertEquals(original.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
        assertEquals(original.messageNodes.map { it.selectIndex }, restored.messageNodes.map { it.selectIndex })
        assertEquals(eventText, restored.currentMessages.last().toText())
    }

    @Test fun staleFullSaveKeepsCommittedFlagsAlongsideNewStreamingTextAndNewNodes() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val edit = presentationEdit(original)
        repository.saveOrbisEventPresentation(conversationId, owner, edit)
        val newReply = UIMessage.assistant("Synthetic new streamed reply after event").toMessageNode()
        val newHuman = UIMessage.user("Synthetic newly queued human text").toMessageNode()
        val staleFlagsWithNewContent = original.copy(messageNodes = original.messageNodes + newReply + newHuman)

        repository.updateConversation(staleFlagsWithNewContent, requireExistingOwner = owner)

        val restored = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(edit.expected.copy(read = true, collapsed = false), restored.messageNodes[1].currentMessage.orbisEvent)
        assertEquals(listOf(newReply, newHuman), restored.messageNodes.drop(2))
        assertEquals(eventText, restored.messageNodes[1].currentMessage.toText())
        assertTrue(indexedText().contains("Synthetic new streamed reply after event"))
    }

    @Test fun deletedConversationCannotBeResurrectedByPresentationEdit() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val edit = presentationEdit(original)
        repository.deleteConversation(original)
        assertPresentationRejected { repository.saveOrbisEventPresentation(conversationId, owner, edit) }
        assertNull(repository.getConversationById(conversationId))
        assertEquals(0, repository.countConversations())
        assertTrue(database.messageNodeDao().getNodesOfConversation(conversationId.toString()).isEmpty())
        assertTrue(indexedText().isEmpty())
    }

    @Test fun changedOwnerRejectsPresentationWithoutChangingAnyRow() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val entity = requireNotNull(database.conversationDao().getConversationById(conversationId.toString()))
        database.conversationDao().update(entity.copy(assistantId = otherOwner.toString()))
        val before = requireNotNull(repository.getConversationById(conversationId))
        val beforeIndex = indexedText()
        assertPresentationRejected { repository.saveOrbisEventPresentation(conversationId, owner, presentationEdit(original)) }
        assertEquals(before, repository.getConversationById(conversationId))
        assertEquals(beforeIndex, indexedText())
    }

    @Test fun mismatchedSourceTriggerTimeIdsAndOriginalTextCannotChangeFlags() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val edit = presentationEdit(original)
        val beforeRows = database.messageNodeDao().getNodesOfConversation(conversationId.toString())
        val invalid = listOf(
            edit.copy(expected = edit.expected.copy(source = "self_reminder")),
            edit.copy(expected = edit.expected.copy(occurredAt = null)),
            edit.copy(expected = edit.expected.copy(occurredAt = 901L)),
            edit.copy(expected = edit.expected.copy(receivedAt = 1_001L)),
            edit.copy(expected = edit.expected.copy(recordId = "different")),
            edit.copy(expected = edit.expected.copy(eventId = "different")),
            edit.copy(nodeId = Uuid.random()),
            edit.copy(messageId = Uuid.random()),
            edit.copy(originalText = eventText.trim()),
        )
        invalid.forEach { request ->
            assertPresentationRejected { repository.saveOrbisEventPresentation(conversationId, owner, request) }
            assertEquals(beforeRows, database.messageNodeDao().getNodesOfConversation(conversationId.toString()))
        }
    }

    @Test fun alternateBranchSelectionAndOtherMessagesSurvivePresentationEdit() = runBlocking {
        val original = withQueuedEvent(original())
        val edit = presentationEdit(original)
        val alternate = UIMessage.user("Synthetic alternate branch")
        val branched = original.copy(messageNodes = listOf(original.messageNodes.first(), original.messageNodes.last().copy(
            messages = original.messageNodes.last().messages + alternate, selectIndex = 1,
        )))
        repository.insertConversation(branched)
        repository.saveOrbisEventPresentation(conversationId, owner, edit)
        val restored = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(1, restored.messageNodes.last().selectIndex)
        assertEquals(alternate, restored.messageNodes.last().messages[1])
        assertEquals(edit.expected.copy(read = true, collapsed = false), restored.messageNodes.last().messages[0].orbisEvent)
        assertEquals(branched.messageNodes.first(), restored.messageNodes.first())
    }

    @Test fun abortedDatabaseUpdateRollsBackAndPropagatesFailure() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val beforeConversation = requireNotNull(repository.getConversationById(conversationId))
        val beforeRows = database.messageNodeDao().getNodesOfConversation(conversationId.toString())
        val beforeIndex = indexedText()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER synthetic_event_failure BEFORE UPDATE OF messages ON message_node " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END"
        )
        var failed = false
        try { repository.saveOrbisEventPresentation(conversationId, owner, presentationEdit(original)) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(beforeRows, database.messageNodeDao().getNodesOfConversation(conversationId.toString()))
        assertEquals(beforeIndex, indexedText())
        assertEquals(beforeConversation, repository.getConversationById(conversationId))
    }

    @Test fun removedEventNodeCannotBeInsertedAgainByPresentationEdit() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val edit = presentationEdit(original)
        database.messageNodeDao().deleteById(edit.nodeId.toString())
        val beforeRows = database.messageNodeDao().getNodesOfConversation(conversationId.toString())
        assertPresentationRejected { repository.saveOrbisEventPresentation(conversationId, owner, edit) }
        assertEquals(beforeRows, database.messageNodeDao().getNodesOfConversation(conversationId.toString()))
        assertNull(database.messageNodeDao().getNodeOfConversation(conversationId.toString(), edit.nodeId.toString()))
    }

    @Test fun forkedHistoryReadsOriginalEventWithoutRequiringOriginalDeliveryTarget() = runBlocking {
        val original = withQueuedEvent(original())
        repository.insertConversation(original)
        val originalBefore = requireNotNull(repository.getConversationById(conversationId))
        // Same node-copy boundary as forkConversationAtMessage; message/provenance IDs survive.
        val fork = createForkConversation(original, original.messageNodes.map { it.copy(id = Uuid.random()) })
        repository.insertConversation(fork)
        val request = presentationEdit(fork)

        repository.saveOrbisEventPresentation(fork.id, fork.assistantId, request)

        val restoredFork = requireNotNull(repository.getConversationById(fork.id))
        assertNotEquals(original.id, restoredFork.id)
        assertEquals(request.expected.copy(read = true, collapsed = false), restoredFork.currentMessages.last().orbisEvent)
        assertEquals(eventText, restoredFork.currentMessages.last().toText())
        assertEquals(originalBefore, repository.getConversationById(conversationId))
        assertFalse(File(directory, "no-backup/orbis-event-inbox-v1.json").exists())
    }

    @Test fun importedLegacyEventWithoutPrivateReceiptCanBeReadUnderItsExistingOwner() = runBlocking {
        val event = UIMessage.user(eventText).copy(orbisEvent = OrbisEventMetadata(
            "receipt-from-another-device", "self_reminder", "imported-event", 1_000L,
        ))
        val imported = original().copy(assistantId = otherOwner, messageNodes = listOf(event.toMessageNode()))
        repository.insertConversation(imported)
        val request = presentationEdit(imported)
        val beforeEntity = database.conversationDao().getConversationById(conversationId.toString())
        val beforeIndex = indexedText()

        repository.saveOrbisEventPresentation(conversationId, otherOwner, request)

        val restored = requireNotNull(repository.getConversationById(conversationId))
        assertEquals(request.expected.copy(read = true, collapsed = false), restored.currentMessages.last().orbisEvent)
        assertNull(restored.currentMessages.last().orbisEvent?.occurredAt)
        assertEquals(eventText, restored.currentMessages.last().toText())
        assertEquals(beforeEntity, database.conversationDao().getConversationById(conversationId.toString()))
        assertEquals(beforeIndex, indexedText())
        assertFalse(File(directory, "no-backup/orbis-event-inbox-v1.json").exists())
        // Reading needs the imported chat's owner, not an arbitrary caller-supplied old owner.
        assertPresentationRejected { repository.saveOrbisEventPresentation(conversationId, owner, request) }
    }
}
