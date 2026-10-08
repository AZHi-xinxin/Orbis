package me.rerere.rikkahub.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.checkpoint.AndroidGenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStorage
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.OrbisCompactionRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSManager
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/**
 * Actual ChatService enqueue -> production Job -> cancelVoiceCallReply -> Room/journal -> queue.
 * Unlike policy-only tests, these never write partialSafelySaved or call settlement helpers.
 * Requires the isolated runner: only fixture-owned FilesManager/SettingsStore bindings; no app modules,
 * user DB/settings, camera, mic, UI, ST or external requests. Run this class in its own process.
 * Persistence gates and the actual ingress mutex only delay work; their implementations remain real.
 * A legacy hold fixture writes a real restriction, never a successful remote ACK or settlement.
 */
@RunWith(AndroidJUnit4::class)
class ChatServiceVoiceCancellationDeviceTest {
    @Test fun dismissNoticeInvalidatesAnOldUtteranceWaitingForIngressButKeepsItsTranscript() = runBlocking {
        fixture { f ->
            val original = "synthetic accepted speech still waiting for ingress"
            val ingress = f.voiceIngressMutex()
            lateinit var oldReply: Deferred<String?>
            ingress.lock()
            try {
                oldReply = f.utterance(original)
                assertFalse(oldReply.isCompleted)
                assertEquals(0, checkNotNull(f.service.voiceCalls.get(f.callId)).transcript.count { it.content == original })
                f.dismissPausedNotice()
                assertFalse(f.paused())
                assertFalse("Dismissal must finish without waiting for old ingress", oldReply.isCompleted)
                assertEquals(0, f.peer.requests.get())
            } finally {
                ingress.unlock()
            }
            assertNull(oldReply.await())
            assertEquals(1, checkNotNull(f.service.voiceCalls.get(f.callId)).transcript.count { it.content == original })
            f.assertAbsentFromChatAndQueue(original)
            assertEquals(0, f.peer.requests.get())
            assertFalse(f.paused())
            f.assertFreshInputCanDrain()
            f.assertAbsentFromChatAndQueue(original)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun detachedLegacyHoldAllowsNewReplyCallAndPreflightBargeInWithoutClearingOldEvidence() = runBlocking {
        fixture(maxRequests = 2) { f ->
            val originalHold = f.installLegacyGatewayHold()
            f.dismissPausedNotice()
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.persistedFreshRecoveryStatus())
            f.assertLegacyGatewayHoldUnchanged(originalHold)
            val newCallId = f.assertNewReplyAndNewCallWork()
            f.assertLegacyGatewayHoldUnchanged(originalHold)

            val interrupted = "synthetic detached new call preflight interruption"
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance(interrupted, newCallId)
            f.beforeHistorySave.await()
            assertEquals("The new call has not submitted its interrupted input", 1, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply, newCallId)
            job.join()
            assertNull(reply.await())
            assertFalse("Old detached evidence must not poison a new pre-model barge-in", f.paused())
            assertEquals(1, checkNotNull(f.service.voiceCalls.get(newCallId)).transcript.count { it.content == interrupted })
            f.assertLegacyGatewayHoldUnchanged(originalHold)

            val following = "synthetic genuinely new spoken input after detached barge-in"
            val nextReply = f.utterance(following, newCallId)
            assertEquals(ANSWER, nextReply.await())
            withContext(Dispatchers.Main) {
                f.service.getGenerationJobStateFlow(f.conversationId).first { it == null }
            }
            f.assertRetainedExactlyOnce(following, substring = true)
            assertEquals(2, f.peer.requests.get())
            assertFalse(f.paused())
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.persistedFreshRecoveryStatus())
            f.assertLegacyGatewayHoldUnchanged(originalHold)
            assertFalse(f.service.mayAcceptPeriodicVideoFrame(f.conversationId, f.callId))
            assertTrue(f.service.mayAcceptPeriodicVideoFrame(f.conversationId, newCallId))
        }
    }

    @Test fun dismissNoticeRetainsOrdinaryInputCancelledBeforeHistoryAndAllowsANewReply() = runBlocking {
        fixture { f ->
            val original = "synthetic unsaved ordinary input"
            f.pauseNextHistorySave.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text(original))))
            }
            f.beforeHistorySave.await()
            val oldJob = f.currentJob()
            f.dismissPausedNotice()
            assertTrue(oldJob.isCompleted)
            assertFalse(f.paused())
            assertEquals(0, f.peer.requests.get())
            f.assertRetainedExactlyOnce(original)
            f.assertNewReplyAndNewCallWork()
            f.assertRetainedExactlyOnce(original)
        }
    }

    @Test fun dismissNoticeRetainsSpokenInputCancelledBeforeHistoryWithoutReplayingIt() = runBlocking {
        fixture { f ->
            val original = "synthetic unsaved dictated input"
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance(original)
            f.beforeHistorySave.await()
            val oldJob = f.currentJob()
            f.dismissPausedNotice()
            assertTrue(oldJob.isCompleted)
            assertFalse(f.paused())
            assertEquals(0, f.peer.requests.get())
            assertTrue(reply.isCompleted)
            f.assertRetainedExactlyOnce(original, substring = true)
            f.assertFreshInputCanDrain()
            f.assertRetainedExactlyOnce(original, substring = true)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun dismissNoticeFinishesAnAttemptedInputSaveOnlyOnceWithoutSendingIt() = runBlocking {
        fixture { f ->
            val original = "synthetic input with interrupted history save"
            f.historyChecksBeforeGate.set(1)
            f.pauseNextHistorySave.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text(original))))
            }
            f.beforeHistorySave.await()
            assertEquals(0, f.peer.requests.get())
            f.dismissPausedNotice()
            assertFalse(f.paused())
            f.assertRetainedExactlyOnce(original)
            f.assertFreshInputCanDrain()
            f.assertRetainedExactlyOnce(original)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun bargeInWhileFirstHistorySaveIsSuspendedDoesNotPauseTheQueue() = runBlocking {
        fixture { f ->
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance("synthetic first interrupted utterance")
            f.beforeHistorySave.await()
            assertEquals("The preflight boundary must precede any HTTP", 0, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply)
            job.join()
            assertFalse("A safely settled pre-model barge-in must not pause the real queue", f.paused())
            assertNull(reply.await())
            val record = checkNotNull(f.service.voiceCalls.get(f.callId))
            assertEquals(1, record.transcript.count { it.content == "synthetic first interrupted utterance" })
            f.assertFreshInputCanDrain()
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun bargeInAfterModelCommitButDuringFinalArchiveSnapshotDoesNotPauseTheQueue() = runBlocking {
        fixture { f ->
            f.installFinalSnapshotGate()
            val reply = f.utterance("synthetic completed model utterance")
            f.finalSnapshotEntered.await()
            // This gate admits only a real answer already committed to Room and with its
            // checkpoint cleared. Streaming archive projections cannot trigger it.
            val stored = checkNotNull(f.repository.getConversationById(f.conversationId))
            assertEquals(1, stored.currentMessages.count { it.parts.any { part ->
                part is UIMessagePart.Text && part.text == ANSWER
            } })
            assertFalse(f.journal.hasCheckpoint(f.conversationId))
            assertEquals(1, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply)
            f.releaseSnapshot.countDown()
            job.join()
            assertFalse("Final archive cancellation must not poison a durably completed model turn", f.paused())
            assertNull(reply.await())
            assertEquals(1, checkNotNull(f.repository.getConversationById(f.conversationId))
                .currentMessages.count { it.parts.any { part -> part is UIMessagePart.Text && part.text == ANSWER } })
            f.assertFreshInputCanDrain()
            assertEquals("No model request or old input may be replayed", 1, f.peer.requests.get())
        }
    }

    private suspend fun fixture(maxRequests: Int = 1, block: suspend (Fixture) -> Unit) {
        val f = Fixture(maxRequests)
        try { withTimeout(30_000) { f.prepare(); block(f); f.peer.assertHealthy() } }
        finally { withContext(NonCancellable) { f.close() } }
    }

    private class Fixture(maxRequests: Int) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(!LcExternalRecoveryGate.isAllowed())
            check(GlobalContext.getOrNull() == null) { "App Koin must not be initialized" }
            listOf(OrbisVoiceCallRuntime::class.java, OrbisVideoCallRuntime::class.java).forEach { type ->
                check(type.getDeclaredField("instance").apply { isAccessible = true }.get(null) == null) {
                    "Run this class alone; never replace another fixture's process runtime"
                }
            }
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(cache.toPath(), "service-voice-cancellation-").toFile()
        val context = StorageOnlyApplication(instrumentation.targetContext, directory)
        val peer = OneAnswerPeer(maxRequests)
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS)
            .dns { name -> check(name == "127.0.0.1"); listOf(LOOPBACK) }
            .addInterceptor { chain ->
                val url = chain.request().url
                check(url.scheme == "http" && url.host == "127.0.0.1" && url.port == peer.port &&
                    url.encodedPath == "/v1/chat/completions") { "Non-fixture request forbidden" }
                chain.proceed(chain.request())
            }.build()
        private val dormantScope = AppScope().also { it.cancel() }
        private val activeScope = AppScope()
        private val model = Model(modelId = "synthetic-service-voice", displayName = "Synthetic only",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE), abilities = emptyList())
        private val assistant = Assistant(name = "Synthetic owner", chatModelId = model.id,
            systemPrompt = "Synthetic local fixture", streamOutput = false, localTools = emptyList(),
            enableMemory = false, enableWebSearch = false, enableRecentChatsReference = false)
        private val settings = SettingsStore(context, dormantScope).also { store ->
            store.settingsFlow.value = Settings(assistantId = assistant.id, assistants = listOf(assistant),
                chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(name = "Loopback only",
                    baseUrl = "http://127.0.0.1:${peer.port}/v1", apiKey = "", models = listOf(model))),
                enableSuggestion = false, mcpServers = emptyList(), searchServices = emptyList(),
                modeInjections = emptyList(), lorebooks = emptyList(), ttsProviders = emptyList(),
                networkSetting = NetworkSetting(enableAutoRetry = false))
        }
        private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        val pauseNextHistorySave = AtomicBoolean(false)
        val historyChecksBeforeGate = AtomicInteger(0)
        val beforeHistorySave = CompletableDeferred<Unit>()
        private val allowHistorySave = CompletableDeferred<Unit>()
        private val actualDao = database.conversationDao()
        private val gatedDao = object : ConversationDAO by actualDao {
            override suspend fun existsById(id: String): Boolean {
                if (pauseNextHistorySave.get() && historyChecksBeforeGate.getAndDecrement() <= 0 &&
                    pauseNextHistorySave.compareAndSet(true, false)) {
                    beforeHistorySave.complete(Unit)
                    allowHistorySave.await() // Cancellable actual service pre-save suspension.
                }
                return actualDao.existsById(id)
            }
        }
        private val files = FilesManager(context, FilesRepository(database.managedFileDao()), dormantScope)
        // ChatToolFactory resolves FilesManager while registering the voice-note tool, even
        // though no tool is executed. Install exactly that isolated binding, not appModule.
        private val isolatedKoin = startKoin { modules(module {
            single<FilesManager> { files }
            single<SettingsStore> { settings }
        }) }
        private val fts = MessageFtsManager(database)
        val repository = ConversationRepository(gatedDao, database.messageNodeDao(), database.favoriteDao(), database, files, fts)
        private val memory = MemoryRepository(database.memoryDao())
        private val manager = ProviderManager(client, context)
        private val workspaceManager = WorkspaceManager(File(directory, "workspace"))
        private val workspace = WorkspaceRepository(database.workspaceDao(), workspaceManager, RootfsInstaller(workspaceManager), settings)
        private val mcp = McpManager(settings, dormantScope, files)
        private val bus = AppEventBus()
        private val tools = ChatToolFactory(JsonInstant, memory, repository,
            LocalTools(context, bus, TTSManager(context), settings), mcp, SkillManager(context, settings), workspace, context,
            settingsStore = settings)
        val service = ChatService(context, activeScope, bus, settings, repository, memory,
            GenerationLoop(context, manager, JsonInstant), TranslationHandler(manager),
            TemplateTransformer(PebbleEngine.Builder().loader(AssistantTemplateLoader(settings)).build(), settings),
            manager, tools, mcp, files, workspace, FolderRepository(database.folderDao(), gatedDao),
            OrbisCompactionRepository(database, fts), client)
        val journal = GenerationCheckpointJournal(AndroidGenerationCheckpointStore.create(context.noBackupFilesDir))
        val conversationId = Uuid.random()
        lateinit var callId: String
        val finalSnapshotEntered = CompletableDeferred<Unit>()
        val releaseSnapshot = CountDownLatch(1)

        suspend fun prepare() {
            repository.insertConversation(Conversation.ofId(conversationId, assistant.id).copy(title = "Synthetic fixed title"))
            withContext(Dispatchers.Main) {
                service.addConversationReference(conversationId)
                val call = service.prepareVoiceCall(conversationId, video = true)
                callId = call.id
                service.connectVoiceCall(callId, System.currentTimeMillis())
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
        }

        suspend fun utterance(text: String, targetCallId: String = callId): Deferred<String?> = withContext(Dispatchers.Main) {
            service.enqueueVoiceCallUtterance(conversationId, targetCallId, text)
        }

        suspend fun cancel(reply: Deferred<String?>, targetCallId: String = callId) = withContext(Dispatchers.Main) {
            service.cancelVoiceCallReply(conversationId, targetCallId, reply)
        }

        suspend fun currentJob(): Job = withContext(Dispatchers.Main) {
            checkNotNull(service.getGenerationJobStateFlow(conversationId).first())
        }

        fun paused(): Boolean = service.getMessageQueueFlow(conversationId).value.paused

        fun voiceIngressMutex(): Mutex = ChatService::class.java.getDeclaredField("voiceIngressMutex")
            .apply { isAccessible = true }.get(service) as Mutex

        private fun session(): ConversationSession {
            val field = ChatService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            return (field.get(service) as Map<*, *>)[conversationId] as ConversationSession
        }

        private fun readPauseBytes(name: String): String? {
            val file = AtomicFile(File(context.noBackupFilesDir, name))
            return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else null
        }

        private fun readOnlyPauseStore(name: String) = OrbisQueuePauseStore(
            read = { readPauseBytes(name) }, write = { error("Assertion must not modify durable restrictions") })

        suspend fun installLegacyGatewayHold(): String = withContext(Dispatchers.Main) {
            // Reproduce an unresolved hold from a previous process using the service's actual
            // AtomicFile writer. This is only a restriction, never a fake remote ACK/idle result.
            val field = ChatService::class.java.getDeclaredField("gatewayRecoveryHoldStore\$delegate")
                .apply { isAccessible = true }
            val store = (field.get(service) as Lazy<*>).value as OrbisQueuePauseStore
            store.pause(conversationId.toString(), "gateway_terminal_unconfirmed")
            session().gatewayRecoveryBlocked = true
            assertEquals(QueuePauseStatus.PAUSED, store.status(conversationId.toString()))
            checkNotNull(readPauseBytes(GATEWAY_HOLD_FILE))
        }

        fun persistedFreshRecoveryStatus(): FreshHumanRecoveryStatus = FreshHumanInputRecoveryStore(
            readOnlyPauseStore(FreshHumanInputRecoveryStore.FILE_NAME)
        ).status(conversationId.toString(), assistant.id.toString())

        fun assertLegacyGatewayHoldUnchanged(expectedBytes: String) {
            assertEquals(expectedBytes, readPauseBytes(GATEWAY_HOLD_FILE))
            assertEquals("gateway_terminal_unconfirmed", readOnlyPauseStore(GATEWAY_HOLD_FILE)
                .pauseReason(conversationId.toString()))
            assertTrue(session().gatewayRecoveryBlocked)
        }

        suspend fun dismissPausedNotice() {
            val reset = withContext(Dispatchers.Main) {
                // Reproduce a host error pause while input persistence is suspended. This does
                // not fake an ACK, checkpoint result, permit, or any part of the recovery action.
                session().messageQueue.pause()
                service.dismissPauseAndContinueFreshInput(conversationId)
            }
            reset.join()
            assertEquals(QueueRecoveryPhase.SUCCESS, service.messageQueueRecoveryState(conversationId).first().phase)
        }

        suspend fun assertRetainedExactlyOnce(text: String, substring: Boolean = false) {
            fun List<UIMessagePart>.matches() = filterIsInstance<UIMessagePart.Text>().any {
                if (substring) text in it.text else text == it.text
            }
            val stored = checkNotNull(repository.getConversationById(conversationId))
            val queued = service.getMessageQueueFlow(conversationId).value.messages.filter { it.parts.matches() }
            assertEquals(1, stored.currentMessages.count { it.parts.matches() } + queued.size)
            assertTrue(queued.all { it.recoveryHeldReason != null })
        }

        suspend fun assertAbsentFromChatAndQueue(text: String) {
            fun List<UIMessagePart>.containsText() = filterIsInstance<UIMessagePart.Text>().any { text in it.text }
            assertFalse(checkNotNull(repository.getConversationById(conversationId)).currentMessages.any { it.parts.containsText() })
            assertFalse(service.getMessageQueueFlow(conversationId).value.messages.any { it.parts.containsText() })
        }

        suspend fun assertNewReplyAndNewCallWork(): String {
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic genuinely new request"))))
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            assertEquals(1, peer.requests.get())
            assertFalse(paused())
            assertTrue(checkNotNull(repository.getConversationById(conversationId)).currentMessages.any {
                it.parts.any { part -> part is UIMessagePart.Text && part.text == ANSWER }
            })
            assertFalse(service.mayAcceptPeriodicVideoFrame(conversationId, callId))
            val newCallId = withContext(Dispatchers.Main) {
                val next = service.prepareVoiceCall(conversationId, video = true)
                service.connectVoiceCall(next.id, System.currentTimeMillis())
                service.getGenerationJobStateFlow(conversationId).first { it == null }
                assertTrue(service.mayAcceptPeriodicVideoFrame(conversationId, next.id))
                assertFalse(service.mayAcceptPeriodicVideoFrame(conversationId, callId))
                next.id
            }
            assertEquals("New call connection must not replay any previous model request", 1, peer.requests.get())
            return newCallId
        }

        fun installFinalSnapshotGate() {
            // Decorate only this fixture's repository storage, not service completion/ACK logic.
            // Preserve the real Android atomic-file writer and shared lock key. No production
            // injectable hooks are added merely to make this regression pass.
            val field = OrbisVoiceCallRepository::class.java.getDeclaredField("storage").apply { isAccessible = true }
            val actual = field.get(service.voiceCalls) as OrbisVoiceCallStorage
            val gated = object : OrbisVoiceCallStorage by actual {
                private val once = AtomicBoolean(false)
                override fun write(id: String, value: String) {
                    val record = JsonInstant.decodeFromString<OrbisVoiceCallRecord>(value)
                    if (record.sourceNodesJson?.contains(ANSWER) == true && !journal.hasCheckpoint(conversationId) &&
                        once.compareAndSet(false, true)) {
                        finalSnapshotEntered.complete(Unit)
                        check(releaseSnapshot.await(15, TimeUnit.SECONDS)) { "Synthetic final snapshot gate timed out" }
                    }
                    actual.write(id, value)
                }
            }
            field.set(service.voiceCalls, gated)
            check(field.get(service.voiceCalls) === gated)
        }

        suspend fun assertFreshInputCanDrain() {
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic fresh input")), answer = false))
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            val stored = checkNotNull(repository.getConversationById(conversationId))
            assertEquals(1, stored.currentMessages.count { it.parts.any { p -> p is UIMessagePart.Text && p.text == "synthetic fresh input" } })
            assertFalse(paused())
        }

        suspend fun close() {
            allowHistorySave.complete(Unit)
            releaseSnapshot.countDown()
            val job = withContext(Dispatchers.Main) {
                val current = service.getGenerationJobStateFlow(conversationId).first()
                service.cleanup()
                activeScope.cancel()
                current
            }
            withTimeout(15_000) { job?.join() }
            // Tool registration initializes idle runtime singletons. Stop only our exact
            // context-owned scopes so their maintenance jobs cannot outlive this cache tree.
            val runtimeJobs = withContext(Dispatchers.Main) {
                listOf(OrbisVideoCallRuntime::class.java, OrbisVoiceCallRuntime::class.java).mapNotNull { type ->
                    val instanceField = type.getDeclaredField("instance").apply { isAccessible = true }
                    val instance = instanceField.get(null) ?: return@mapNotNull null
                    val owner = type.getDeclaredField("context").apply { isAccessible = true }.get(instance)
                    check(owner === context) { "Refusing to change a runtime not owned by this fixture" }
                    if (instance is OrbisVoiceCallRuntime) check(!instance.callState.value.isActive)
                    val scope = type.getDeclaredField("scope").apply { isAccessible = true }.get(instance) as CoroutineScope
                    scope.cancel()
                    instanceField.set(null, null)
                    scope.coroutineContext[Job]
                }
            }
            withTimeout(15_000) { runtimeJobs.forEach { it.join() } }
            check(GlobalContext.getOrNull() === isolatedKoin.koin)
            stopKoin()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            peer.close()
            database.close()
            check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("service-voice-cancellation-"))
            directory.walkTopDown().forEach { check(!Files.isSymbolicLink(it.toPath())) }
            check(directory.deleteRecursively()) // Only this newly generated fixture, never app data.
        }
    }

    private class StorageOnlyApplication(base: Context, private val owned: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = owned
        override fun getFilesDir(): File = File(owned, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(owned, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(owned, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = error("Persistent database forbidden")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Real preferences forbidden")
        override fun startService(service: Intent): ComponentName? = error("Fixture does not start services")
        override fun startForegroundService(service: Intent): ComponentName? = error("Fixture does not start foreground services")
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = error("Fixture does not bind services")
        override fun startActivity(intent: Intent) { error("Fixture does not open UI") }
        override fun sendBroadcast(intent: Intent) { error("Fixture does not broadcast") }
        override fun sendBroadcast(intent: Intent, receiverPermission: String?) { error("Fixture does not broadcast") }
    }

    private class OneAnswerPeer(private val maxRequests: Int) : Closeable {
        private val socket = ServerSocket(0, 1, LOOPBACK)
        val port = socket.localPort
        val requests = AtomicInteger()
        private val stopped = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "synthetic-chat-service-peer", isDaemon = true) {
            try {
                while (!stopped.get()) socket.accept().use { connection ->
                    connection.soTimeout = 8_000
                    val input = connection.getInputStream().buffered()
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) {
                            val next = input.read()
                            check(next >= 0 && text.length < 8192)
                            if (next == 10) return text.toString().trimEnd('\r')
                            text.append(next.toChar())
                        }
                    }
                    check(line() == "POST /v1/chat/completions HTTP/1.1")
                    val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
                    val count = headers.single { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    check(count in 1..262_144)
                    var left = count
                    val scratch = ByteArray(4096)
                    while (left > 0) { val n = input.read(scratch, 0, minOf(left, scratch.size)); check(n > 0); left -= n }
                    check(requests.incrementAndGet() <= maxRequests) { "Unexpected replay/derived request" }
                    val body = """{"id":"synthetic","object":"chat.completion","created":1,"model":"synthetic-service-voice","choices":[{"index":0,"message":{"role":"assistant","content":"$ANSWER"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!(stopped.get() && error is SocketException)) failure.set(error)
            }
        }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic loopback failed", it) } }
        override fun close() { stopped.set(true); socket.close(); worker.join(9_000); check(!worker.isAlive); assertHealthy() }
    }

    private companion object {
        const val ANSWER = "synthetic fully committed reply"
        const val GATEWAY_HOLD_FILE = "orbis-gateway-recovery-holds-v1.json"
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
