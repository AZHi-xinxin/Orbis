package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.AppScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real Room transactions and AtomicFile journals, synthetic messages only. No Koin or tools. */
@RunWith(AndroidJUnit4::class)
class HumanContinuationRoomTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(cache.toPath(), "continuation-room-test-").toFile()
        private val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getFilesDir(): File = File(root, "synthetic-files").also {
                check(it.isDirectory || it.mkdir())
            }
            override fun getNoBackupFilesDir(): File = error("User journals forbidden in synthetic test")
            override fun getDatabasePath(name: String): File = error("Persistent DB forbidden in synthetic test")
        }
        private val scope = AppScope()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
        val oldStore = AndroidGenerationCheckpointStore.create(root)
        val humanStore = AndroidGenerationCheckpointStore.createHumanContinuation(root)
        val oldJournal = GenerationCheckpointJournal(oldStore)
        val baseline = Conversation(assistantId = Uuid.random(), title = "synthetic room",
            messageNodes = listOf(UIMessage.user("first").toMessageNode(),
                UIMessage.assistant("answer").toMessageNode(), UIMessage.user("MCP request").toMessageNode()))
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("synthetic-call", "synthetic_mcp", "{}"))).toMessageNode()
        val visible = baseline.copy(messageNodes = baseline.messageNodes + tool)
        val continued = visible.copy(messageNodes = visible.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.finishInterruptedHostTools().finishReasoning() })
        } + UIMessage.user("fresh text").toMessageNode() + UIMessage.assistant("fresh reply").toMessageNode())
        suspend fun seed() {
            repo.insertConversation(baseline)
            val handle = oldJournal.begin(baseline)
            oldJournal.checkpoint(handle, baseline.assistantId, baseline.compactionEpoch,
                baseline.messageNodes.takeLast(1) + tool,
                GenerationToolTransition("synthetic-call", "synthetic_mcp", GenerationToolStatus.STARTED))
            HumanContinuationJournal(humanStore).checkpoint(Uuid.random(), visible, continued)
        }
        suspend fun load() = checkNotNull(repo.getConversationById(baseline.id))
        suspend fun save(expected: Conversation = visible, next: Conversation = continued): Conversation? =
            commitHumanContinuationSnapshot(expected, next, ::load,
                oldJournal::provesInFlightBaseline, repo::commitHumanContinuation)
        override fun close() {
            scope.cancel()
            db.close()
            // Delete only this newly created, canonical synthetic directory, never user journals.
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("continuation-room-test-"))
            val owned = root.walkBottomUp().toList()
            owned.forEach {
                check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(root.toPath()))
            }
            owned.forEach { check(it.delete()) }
            check(!LcExternalRecoveryGate.isAllowed())
        }
    }

    @Test fun missingStreamedTailSavesToRoomAndSurvivesJournalReopen() = runBlocking {
        Fixture().use { f ->
            f.seed()
            assertNull(f.repo.commitHumanContinuation(f.visible, f.continued))
            f.repo.updateConversation(f.load().copy(title = "newer title", customSystemPrompt = "newer prompt"))
            val restored = HumanContinuationJournal(f.humanStore).recover(f.load(), f.oldJournal::provesInFlightBaseline)!!
            val saved = f.save(restored.durableBaseline!!, restored.snapshot)!!
            assertEquals(f.continued.messageNodes, f.load().messageNodes)
            assertEquals("newer title", saved.title)
            assertEquals("newer prompt", saved.customSystemPrompt)
            assertNull(HumanContinuationJournal(f.humanStore).recover(f.load()))
            assertTrue(f.oldJournal.clearSettledInDurableContinuation(f.load(), true))
            assertNull(GenerationCheckpointJournal(f.oldStore).recover(f.load()))
            // A second new turn now commits normally, without old MCP replay.
            val next = saved.copy(messageNodes = saved.messageNodes + UIMessage.user("next turn").toMessageNode())
            assertNotNull(f.save(saved, next))
            assertEquals(next.messageNodes, f.load().messageNodes)
        }
    }

    @Test fun transactionFailureKeepsOriginalRoomAndRecoverableIndependentJournal() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_fresh_index BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")
            assertTrue(runCatching { f.save() }.isFailure)
            assertEquals(f.baseline.messageNodes, f.load().messageNodes)
            val recovered = HumanContinuationJournal(f.humanStore).recover(f.load(), f.oldJournal::provesInFlightBaseline)!!
            assertEquals(f.continued.messageNodes, recovered.snapshot.messageNodes)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_fresh_index")
            assertNotNull(f.save(recovered.durableBaseline!!, recovered.snapshot))
            assertEquals(f.continued.messageNodes, f.load().messageNodes)
        }
    }

    @Test fun ordinaryInputRollbackDoesNotBecomeContinuationBaselineWithoutGenerationJournal() = runBlocking {
        Fixture().use { f ->
            f.repo.insertConversation(f.baseline)
            var published = f.load()
            val baseline = published
            val attempted = baseline.copy(messageNodes = baseline.messageNodes + UIMessage.user("new input").toMessageNode())
            assertFalse(f.oldJournal.hasCheckpoint(baseline.id))
            f.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_input_index BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")

            assertTrue(runCatching {
                commitConversationBeforePublish(
                    commit = { f.repo.updateConversation(attempted) },
                    publish = { published = attempted },
                )
            }.isFailure)

            assertEquals(baseline.messageNodes, published.messageNodes)
            assertEquals(baseline.messageNodes, f.load().messageNodes)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_input_index")
            val continued = attempted.copy(messageNodes = attempted.messageNodes + UIMessage.assistant("continued answer").toMessageNode())
            val journal = HumanContinuationJournal(f.humanStore); val runId = Uuid.random()
            journal.checkpoint(runId, published, continued)
            assertNotNull(commitHumanContinuationSnapshot(published, continued, f::load,
                f.oldJournal::provesInFlightBaseline, f.repo::commitHumanContinuation,
                beforeCommit = { disk, candidate -> journal.prepareRoomCommit(runId, published, disk, candidate) }))
            assertEquals(continued.messageNodes, f.load().messageNodes)
            assertNull(HumanContinuationJournal(f.humanStore).recover(f.load()))
        }
    }

    @Test fun sameNodeStreamingAppendSurvivesAtomicFileReopenAndRoomCompareAndSet() = runBlocking {
        Fixture().use { f ->
            f.repo.insertConversation(f.baseline)
            val baseline = f.load()
            val early = baseline.copy(messageNodes = baseline.messageNodes +
                UIMessage.user("new input").toMessageNode() + UIMessage.assistant("A").toMessageNode())
            val replyNode = early.messageNodes.last()
            val latest = early.copy(messageNodes = early.messageNodes.dropLast(1) + replyNode.copy(messages = listOf(
                replyNode.currentMessage.copy(parts = listOf(UIMessagePart.Text("AB"))))))
            val journal = HumanContinuationJournal(f.humanStore); val runId = Uuid.random()
            journal.checkpoint(runId, baseline, early)
            journal.prepareRoomCommit(runId, baseline, baseline, early)
            assertNotNull(f.repo.commitHumanContinuation(baseline, early))
            journal.checkpoint(runId, baseline, latest)
            val bytes = f.humanStore.read(baseline.id)

            val reopened = HumanContinuationJournal(f.humanStore)
            val recovery = reopened.recover(f.load())!!

            assertEquals(latest.messageNodes, recovery.snapshot.messageNodes)
            assertEquals(early.messageNodes, recovery.durableBaseline!!.messageNodes)
            assertArrayEquals(bytes, f.humanStore.read(baseline.id))
            assertNotNull(commitHumanContinuationSnapshot(recovery.durableBaseline!!, recovery.snapshot, f::load,
                f.oldJournal::provesInFlightBaseline, f.repo::commitHumanContinuation,
                beforeCommit = { disk, candidate -> reopened.prepareRoomCommit(runId, recovery.baseline, disk, candidate) }))
            assertEquals(latest.messageNodes, f.load().messageNodes)
            assertNull(HumanContinuationJournal(f.humanStore).recover(f.load()))
        }
    }

    @Test fun concurrentHistoryEditWinsAndDuplicateCommitDoesNotDuplicateRows() = runBlocking {
        Fixture().use { f ->
            f.seed()
            val result = commitHumanContinuationSnapshot(f.visible, f.continued, f::load,
                f.oldJournal::provesInFlightBaseline) { expected, next ->
                f.repo.updateConversation(f.load().copy(messageNodes = f.baseline.messageNodes +
                    UIMessage.user("concurrent human edit").toMessageNode()))
                f.repo.commitHumanContinuation(expected, next)
            }
            assertNull(result)
            assertEquals("concurrent human edit", f.load().currentMessages.last().toText())
        }
        Fixture().use { f ->
            f.seed()
            assertNotNull(f.save())
            assertNotNull(f.save())
            assertEquals(f.continued.messageNodes, f.load().messageNodes)
        }
    }

    @Test fun ordinaryFirstInputCommittedBeforeCancellationIsRetainedBySecondLaneAndContinuesSaving() = runBlocking {
        Fixture().use { f ->
            f.repo.insertConversation(f.baseline)
            val baseline = f.load()
            var published = baseline
            val first = UIMessage.user("first accepted input").toMessageNode()
            val candidate = baseline.copy(messageNodes = baseline.messageNodes + first)
            var pending: HumanContinuationOrdinaryInputHandoff? =
                HumanContinuationOrdinaryInputHandoff(baseline, candidate, first.currentMessage.id)
            val mutex = Mutex()
            val entered = CompletableDeferred<Unit>(); val lostReceipt = CompletableDeferred<Unit>()
            val ordinary = async {
                try {
                    mutex.withLock {
                        commitConversationBeforePublish(commit = {
                            f.repo.updateConversation(candidate)
                            entered.complete(Unit); lostReceipt.await()
                        }, publish = { published = candidate })
                    }
                } finally { pending = null }
            }
            entered.await()
            val captured = checkNotNull(pending)
            assertEquals(candidate.messageNodes, f.load().messageNodes)
            assertEquals(baseline.messageNodes, published.messageNodes)
            assertFalse(f.oldJournal.hasCheckpoint(baseline.id))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val journal = HumanContinuationJournal(f.humanStore)
                lateinit var lane: HumanContinuationLane
                val storage = HumanContinuationContextStore(baseline, baseline, Uuid.random(), mutex, journal,
                    read = f::load, proveBaseline = captured::provesCommittedInput,
                    commit = f.repo::commitHumanContinuation, canWrite = { true },
                    project = { lane.projectContext(it) }, latest = { lane.state.value },
                    onStored = {}, onRebased = { a, b -> lane.acceptCompaction(a, b) }, retireOldJournal = {})
                // Explicitly drive both production storage operations below; no app singleton,
                // real account, model call or device-level data is involved.
                lane = HumanContinuationLane(baseline, scope, { false }, { throw it },
                    initial = checkNotNull(captured.initialFor(published)), contextStore = storage)
                lane.send(UIMessage.user("second accepted input"), answer = false) { _, _, _ -> error("no model") }.join()
                ordinary.cancelAndJoin()
                assertNull(pending)
                assertEquals(baseline.messageNodes, published.messageNodes)
                assertEquals(listOf("first accepted input", "second accepted input"), lane.state.value.currentMessages.takeLast(2).map { it.toText() })
                storage.checkpoint(lane.state.value)
                assertTrue(storage.save(lane.state.value))
                assertEquals(lane.state.value.messageNodes, f.load().messageNodes)
                lane.send(UIMessage.user("third accepted input"), answer = false) { _, _, _ -> error("no model") }.join()
                assertTrue(storage.save(lane.state.value))
                storage.checkpoint(lane.state.value)
                assertEquals(listOf("first accepted input", "second accepted input", "third accepted input"), f.load().currentMessages.takeLast(3).map { it.toText() })
                assertNull(HumanContinuationJournal(f.humanStore).recover(f.load()))
                assertFalse(f.oldJournal.hasCheckpoint(baseline.id))
            } finally {
                ordinary.cancelAndJoin()
                scope.cancel()
            }
        }
    }
}
