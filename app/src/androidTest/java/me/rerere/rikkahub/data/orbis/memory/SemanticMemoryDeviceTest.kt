package me.rerere.rikkahub.data.orbis.memory

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionEventEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.uuid.Uuid

/** Synthetic isolated app only; no production preferences, accounts, provider or phone database. */
@RunWith(AndroidJUnit4::class)
class SemanticMemoryDeviceTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
        }
        private val parent = instrumentation.targetContext.cacheDir.canonicalFile
        private val directory = Files.createTempDirectory(parent.toPath(), "semantic-test-").toFile()
        val context = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Real preferences forbidden")
            override fun getDatabasePath(name: String): File = error("Persistent database forbidden")
        }
        val dormant = AppScope().also { it.cancel() }
        val initial = Assistant(name = "Synthetic", orbisMemoryMode = OrbisMemoryMode.STANDARD)
        val other = Assistant(name = "Other", orbisMemoryMode = OrbisMemoryMode.STANDARD)
        val owner = initial.id.toString()
        val settings = SettingsStore(context, dormant).also {
            it.settingsFlow.value = Settings(assistantId = initial.id, assistants = listOf(initial, other),
                providers = emptyList(), mcpServers = emptyList(), searchServices = emptyList(),
                modeInjections = emptyList(), lorebooks = emptyList(), ttsProviders = emptyList())
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = OrbisMemoryRepository(db)
        val services = mutableListOf<SemanticMemoryRecall>()
        val assistant get() = settings.settingsFlow.value.assistants.first { it.id == initial.id }
        fun change(update: (Assistant) -> Assistant) {
            settings.settingsFlow.value = settings.settingsFlow.value.let { state ->
                state.copy(assistants = state.assistants.map { if (it.id == initial.id) update(it) else it })
            }
        }
        suspend fun source(text: String = "小时候和家人在海边散步，一起看日落。", who: String = owner): Pair<String, String> {
            val conversation = Uuid.random().toString()
            db.conversationDao().insert(ConversationEntity(conversation, who, "synthetic", "[]", 1, 1, "[]", false))
            val event = Uuid.random().toString()
            db.orbisCompactionDao().insertEvent(OrbisCompactionEventEntity(event, conversation, who, "synthetic", 1,
                100, 20, "estimate", "estimate", memoryDigest(text), Uuid.random().toString(), 0, "committed"))
            val result = repository.storeCompaction(who, event, text)
            return event to result.getValue("id").jsonPrimitive.content
        }
        suspend fun state(id: String, state: String) {
            assertTrue(repository.execute(owner, buildJsonObject {
                put("action", "set_state"); put("id", id); put("state", state)
            }, Uuid.random().toString()).getValue("ok").jsonPrimitive.boolean)
        }
        fun service(factory: () -> MemoryEmbedding = { FakeEmbedding() }, deadline: Long = 150) =
            SemanticMemoryRecall(context, db, settings, factory, deadline).also { services += it; it.start() }
        suspend fun ready(service: SemanticMemoryRecall) = withTimeout(15_000) {
            while (service.indexedCount(owner) == 0) delay(20)
        }
        override fun close() {
            services.forEach { it.close() }
            runBlocking { delay(1200) } // Allow the deliberately slow native stand-in to exit.
            db.close()
            check(directory.canonicalFile.parentFile == parent && directory.name.startsWith("semantic-test-"))
            check(directory.walkTopDown().none { Files.isSymbolicLink(it.toPath()) })
            check(directory.deleteRecursively())
        }
    }
    private class FakeEmbedding(private val delayMs: Long = 0) : MemoryEmbedding {
        val queries = AtomicInteger()
        override fun encode(text: String, query: Boolean): FloatArray {
            if (query) { queries.incrementAndGet(); if (delayMs > 0) Thread.sleep(delayMs) }
            return FloatArray(512) { if (it == 0) 1f else 0f }
        }
        override fun close() {}
    }

    @Test fun provenanceAndExplicitArchiveOnlyAreRespected() = runBlocking {
        Fixture().use { f ->
            val (event, id) = f.source()
            val sources = SemanticMemorySources(f.db)
            assertNotNull(sources.read(f.owner, event))
            assertNull(sources.read(f.other.id.toString(), event))
            assertNull(sources.read(f.owner, event, "not-the-receipt-note"))
            f.state(id, "paused")
            assertNull(sources.read(f.owner, event))
            f.state(id, "resume")
            assertNotNull(sources.read(f.owner, event))
            f.state(id, "static")
            assertNull(sources.read(f.owner, event))
            f.state(id, "conditional")
            assertNotNull(sources.read(f.owner, event))
            f.state(id, "paused")
            assertNull(sources.read(f.owner, event))
            f.state(id, "conditional")
            f.db.orbisCompactionDao().markRolledBack(event)
            assertNull(sources.read(f.owner, event))
        }
    }

    @Test fun automaticRecallFrozenAcrossRetriesAndRemovedOnPause() = runBlocking {
        Fixture().use { f ->
            val (_, id) = f.source()
            val model = FakeEmbedding()
            val service = f.service({ model }); f.ready(service)
            val runtime = OrbisMemoryRuntime(f.db, f.repository, f.settings, service)
            val human = UIMessage.user("上次一起出去散心那件事")
            val room = Uuid.random()
            val turn = runtime.begin(f.assistant, room, listOf(human))!!
            assertEquals(listOf(id), turn.selection.entries.map { it.id })
            assertEquals("compaction_excerpt", turn.selection.entries.single().kind)
            val again = runtime.begin(f.assistant, room, listOf(human))!!
            assertEquals(turn.selection, again.selection); assertEquals(1, model.queries.get())
            assertTrue(again.project(listOf(human)).first().toText().contains("海边"))
            f.state(id, "paused")
            assertEquals(listOf(human), again.project(listOf(human)))
            assertEquals(1L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
        }
    }

    @Test fun timeoutReturnsWithoutWaitingForUncooperativeInferenceAndLateResultDoesNotReplay() = runBlocking {
        Fixture().use { f ->
            f.source()
            val model = FakeEmbedding(1000)
            val service = f.service({ model }, 60); f.ready(service)
            val runtime = OrbisMemoryRuntime(f.db, f.repository, f.settings, service)
            val human = UIMessage.user("那次我们出去玩的事情")
            val room = Uuid.random()
            val elapsed = measureTimeMillis {
                assertTrue(runtime.begin(f.assistant, room, listOf(human))!!.selection.entries.isEmpty())
            }
            assertTrue("optional recall blocked for $elapsed ms", elapsed < 500)
            delay(1100)
            assertTrue(runtime.begin(f.assistant, room, listOf(human))!!.selection.entries.isEmpty())
            assertEquals(1, model.queries.get())
        }
    }

    @Test fun coldIndexAndDisabledModesDoNotBlockOrCrossOwners() = runBlocking {
        Fixture().use { f ->
            f.source()
            val service = f.service({ Thread.sleep(700); FakeEmbedding() })
            val messages = listOf(UIMessage.user("以前去海边的事"))
            assertTrue(service.recall(f.owner, messages).isEmpty())
            f.ready(service)
            assertTrue(service.recall(f.other.id.toString(), messages).isEmpty())
            f.change { it.copy(orbisMemoryAutoInject = false) }
            assertTrue(service.recall(f.owner, messages).isEmpty())
            f.change { it.copy(orbisMemoryAutoInject = true, orbisMemoryMode = OrbisMemoryMode.LIGHT) }
            assertTrue(service.recall(f.owner, messages).isEmpty())
            f.change { it.copy(orbisMemoryMode = OrbisMemoryMode.STANDARD, orbisSemanticRecall = false) }
            assertTrue(service.recall(f.owner, messages).isEmpty())
        }
    }

    @Test fun editDeleteAndConversationOwnershipInvalidateFrozenSemanticSelection() = runBlocking {
        Fixture().use { f ->
            val (event, id) = f.source()
            val service = f.service(); f.ready(service)
            val selected = service.recall(f.owner, listOf(UIMessage.user("以前一起出游的事")))
            assertEquals(1, selected.size)
            f.repository.execute(f.owner, buildJsonObject {
                put("action", "update"); put("id", id); put("body", "updated")
            }, "edit")
            assertTrue(service.validate(f.owner, selected).isEmpty())
            val eventRow = f.db.orbisCompactionDao().getEvent(event, f.owner)!!
            f.db.conversationDao().deleteById(eventRow.conversationId)
            assertTrue(service.validate(f.owner, selected).isEmpty())
        }
    }

    @Test fun bundledModelRunsOnAndroidAndRecognizesParaphrase() {
        Fixture().use { f ->
            LocalMemoryEmbedding(f.context).use { model ->
                val source = model.encode("她对花生过敏，点餐时需要提前告诉服务员不要放花生。", false)
                val relevant = model.encode("她吃饭有什么需要忌口的？", true)
                val unrelated = model.encode("怎样修理自行车的刹车？", true)
                val hit = semanticSimilarity(source, relevant)
                val miss = semanticSimilarity(source, unrelated)
                assertTrue("semantic order hit=$hit miss=$miss", hit > miss + 0.1f)
                assertTrue("relevant score=$hit", hit >= 0.50f)
                assertTrue("unrelated score=$miss", miss < 0.50f)
                assertEquals(0.547077f, hit, 0.02f) // Parity with the pinned reference tokenizer/runtime.
                val timings = List(7) {
                    measureTimeMillis { model.encode("她吃饭有什么需要忌口的？", true) }
                }
                android.util.Log.i("OrbisSemanticTest", "synthetic query ms=$timings hit=$hit miss=$miss")
            }
        }
    }

    @Test fun realOfflineModelFlowsAutomaticallyIntoRequestWithoutAnyToolCall() = runBlocking {
        Fixture().use { f ->
            val (_, id) = f.source("她对花生过敏，点餐时需要提前告诉服务员不要放花生。")
            val service = f.service({ LocalMemoryEmbedding(f.context) }); f.ready(service)
            val human = UIMessage.user("她吃饭有什么需要忌口的？")
            val runtime = OrbisMemoryRuntime(f.db, f.repository, f.settings, service)
            val turn = runtime.begin(f.assistant, Uuid.random(), listOf(human))!!
            assertEquals(listOf(id), turn.selection.entries.map { it.id })
            val projected = turn.project(listOf(human))
            assertEquals(human, projected.last())
            assertTrue(projected.first().toText().contains("花生"))
            assertTrue(projected.first().toText().contains("不是当年逐字原话"))
            assertTrue(projected.all { it.getTools().isEmpty() })
        }
    }

    @Test fun unavailableModelLeavesChatAndCanonicalMemoryIntact() = runBlocking {
        Fixture().use { f ->
            val (_, id) = f.source()
            val service = f.service({ throw IllegalStateException("synthetic unavailable model") })
            delay(500)
            val human = UIMessage.user("之前那次海边出行")
            val runtime = OrbisMemoryRuntime(f.db, f.repository, f.settings, service)
            val turn = runtime.begin(f.assistant, Uuid.random(), listOf(human))!!
            assertTrue(turn.selection.entries.isEmpty())
            assertEquals(listOf(human), turn.project(listOf(human)))
            assertNotNull(f.db.orbisMemoryDao().get(f.owner, id))
        }
    }
}
