package me.rerere.rikkahub.data.orbis.memory

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real Room + request runtime; synthetic settings only, with no application jobs or network. */
@RunWith(AndroidJUnit4::class)
class OrbisMemoryRuntimeDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val directory = Files.createTempDirectory(cache.toPath(), "memory-runtime-").toFile()
        private val context = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("Real preferences forbidden in memory runtime fixture")
            override fun getDatabasePath(name: String): File = error("Persistent database forbidden")
        }
        private val dormantScope = AppScope().also { it.cancel() }
        val initialAssistant = Assistant(name = "Synthetic memory owner", orbisMemoryMode = OrbisMemoryMode.STANDARD)
        val otherAssistant = Assistant(name = "Synthetic other owner", orbisMemoryMode = OrbisMemoryMode.STANDARD)
        val owner = initialAssistant.id.toString()
        val other = otherAssistant.id.toString()
        val settings = SettingsStore(context, dormantScope).also {
            it.settingsFlow.value = Settings(assistantId = initialAssistant.id,
                assistants = listOf(initialAssistant, otherAssistant), providers = emptyList(),
                mcpServers = emptyList(), searchServices = emptyList(), modeInjections = emptyList(),
                lorebooks = emptyList(), ttsProviders = emptyList())
        }
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val repo = OrbisMemoryRepository(db)
        val conversation = Uuid.random()
        val assistant: Assistant get() = settings.settingsFlow.value.assistants.first { it.id == initialAssistant.id }
        fun runtime() = OrbisMemoryRuntime(db, repo, settings)
        fun changeAssistant(change: (Assistant) -> Assistant) {
            val old = settings.settingsFlow.value
            settings.settingsFlow.value = old.copy(assistants = old.assistants.map {
                if (it.id == initialAssistant.id) change(it) else it
            })
        }
        suspend fun execute(action: String, ownerId: String = owner,
            fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = repo.execute(ownerId,
            buildJsonObject { put("action", action); fields() }, Uuid.random().toString()).also {
                assertEquals(it.toString(), JsonPrimitive(true), it["ok"])
            }
        suspend fun store(body: String, summary: String = "synthetic tea summary", interval: Int = 0,
            ownerId: String = owner): String = execute("store", ownerId) {
            put("body", body); put("summary", summary); put("state", "conditional")
            put("tags", buildJsonArray { add("tea") }); put("minIntervalTurns", interval)
        }.getValue("id").jsonPrimitive.content
        suspend fun begin(human: UIMessage, conversationId: Uuid = conversation): OrbisMemoryTurn =
            checkNotNull(runtime().begin(assistant, conversationId, listOf(human)))
        override fun close() {
            db.close()
            dormantScope.cancel()
            check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("memory-runtime-"))
            directory.walkTopDown().forEach { check(!Files.isSymbolicLink(it.toPath())) }
            check(directory.deleteRecursively()) // Only this fixture's newly allocated directory.
        }
    }

    @Test(timeout = 30_000) fun sameHumanSnapshotSurvivesRuntimeRecreationAndContentChanges() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic full original", "synthetic frozen summary")
            val human = UIMessage.user("tea")
            val original = listOf(UIMessage.system("synthetic system"), human)
            val first = f.begin(human)
            assertEquals(listOf(id), first.selection.entries.map { it.id })
            f.execute("update") { put("id", id); put("summary", "synthetic edited summary") }
            f.store("synthetic newly created note", "synthetic new summary")
            val recreated = f.begin(human)
            assertEquals(first.selection, recreated.selection)
            assertEquals(1L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
            assertEquals(1L, f.db.orbisMemoryTurnDao().lastSurfaced(f.owner, listOf(id)).single().lastOrdinal)
            val request = recreated.project(original)
            assertTrue(request.text().contains("synthetic frozen summary"))
            assertFalse(request.text().contains("synthetic edited summary"))
            assertFalse(request.text().contains("synthetic new summary"))
            assertEquals(2, original.size)
            assertEquals("synthetic system", original.first().toText())
            assertEquals(human, request.last())
            val next = f.begin(UIMessage.user("tea again"))
            assertTrue(next.selection.entries.any { it.text == "synthetic edited summary" })
            assertTrue(next.selection.entries.any { it.text == "synthetic new summary" })
        }
    }

    @Test(timeout = 30_000) fun frequencyUsesNewRealHumanAnchorsAcrossWindowsNotRetriesOrSyntheticNotices() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic interval original", interval = 3)
            val human = UIMessage.user("tea")
            assertEquals(listOf(id), f.begin(human).selection.entries.map { it.id })
            val synthetic = UIMessage.user("tea synthetic wake").copy(isSynthetic = true)
            val retry = checkNotNull(f.runtime().begin(f.assistant, f.conversation, listOf(human, synthetic)))
            assertEquals(listOf(id), retry.selection.entries.map { it.id })
            assertEquals(1L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
            assertNull(f.runtime().begin(f.assistant, Uuid.random(), listOf(synthetic)))
            assertEquals(1L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
            assertTrue(f.begin(UIMessage.user("tea second"), Uuid.random()).selection.entries.isEmpty())
            assertTrue(f.begin(UIMessage.user("tea third")).selection.entries.isEmpty())
            assertEquals(listOf(id), f.begin(UIMessage.user("tea fourth")).selection.entries.map { it.id })
            assertEquals(4L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
            assertEquals(4L, f.db.orbisMemoryTurnDao().lastSurfaced(f.owner, listOf(id)).single().lastOrdinal)
        }
    }

    @Test(timeout = 30_000) fun masterStopLightPauseStaticAndDeleteRemoveFrozenProjectionImmediately() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic original", "synthetic removable summary")
            val human = UIMessage.user("tea")
            val original = listOf(UIMessage.system("base system"), human)
            val turn = f.begin(human)
            val projected = turn.project(original)
            assertTrue(projected.text().contains("synthetic removable summary"))
            f.changeAssistant { it.copy(orbisMemoryAutoInject = false) }
            assertEquals(original, turn.project(projected))
            f.changeAssistant { it.copy(orbisMemoryAutoInject = true, orbisMemoryMode = OrbisMemoryMode.LIGHT) }
            assertEquals(original, turn.project(projected))
            f.changeAssistant { it.copy(orbisMemoryMode = OrbisMemoryMode.STANDARD) }
            assertTrue(turn.project(original).text().contains("synthetic removable summary"))
            f.execute("set_state") { put("id", id); put("state", "paused") }
            assertEquals(original, turn.project(projected))
            f.execute("set_state") { put("id", id); put("state", "conditional") }
            assertTrue(turn.project(original).text().contains("synthetic removable summary"))
            f.execute("set_state") { put("id", id); put("state", "static") }
            assertEquals(original, turn.project(projected))
            f.execute("set_state") { put("id", id); put("state", "conditional") }
            f.execute("delete") { put("id", id) }
            assertEquals(original, turn.project(projected))
            assertEquals(1L, f.db.orbisMemoryTurnDao().latestOrdinal(f.owner))
        }
    }

    @Test(timeout = 30_000) fun enablingAfterAnEmptySnapshotWaitsForTheNextHumanAndNeverBackfillsThisTurn() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic original")
            val human = UIMessage.user("tea")
            f.changeAssistant { it.copy(orbisMemoryMode = OrbisMemoryMode.LIGHT) }
            assertTrue(f.begin(human).selection.entries.isEmpty())
            f.changeAssistant { it.copy(orbisMemoryMode = OrbisMemoryMode.STANDARD) }
            val same = f.begin(human)
            assertTrue(same.selection.entries.isEmpty())
            assertEquals(listOf(human), same.project(listOf(human)))
            assertEquals(listOf(id), f.begin(UIMessage.user("tea new turn")).selection.entries.map { it.id })
        }
    }

    @Test(timeout = 30_000) fun selectionAndProjectionStayWithHostAssistantEvenWhenUiSelectionChanges() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic owning original", "synthetic owning summary")
            f.store("synthetic foreign original", "synthetic foreign summary", ownerId = f.other)
            val human = UIMessage.user("tea")
            val original = listOf(UIMessage.system("base"), human)
            val turn = f.begin(human)
            f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistantId = f.otherAssistant.id)
            assertEquals(listOf(id), f.begin(human).selection.entries.map { it.id })
            val request = turn.project(original)
            assertTrue(request.text().contains("synthetic owning summary"))
            assertFalse(request.text().contains("synthetic foreign"))
            assertEquals(0L, f.db.orbisMemoryTurnDao().latestOrdinal(f.other))
            f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistants = listOf(f.otherAssistant))
            assertEquals(original, turn.project(request))
        }
    }

    @Test(timeout = 30_000) fun longOriginalAndOver300CharacterSummaryKeepFullStorageAndUseOnlyCompleteSummary() = runBlocking {
        Fixture().use { f ->
            val body = "synthetic complete original ".repeat(100)
            val summary = "a ".repeat(220)
            assertTrue(OrbisMemoryBudget.codepoints(summary) > 300)
            val id = f.store(body, summary)
            val candidate = f.repo.injectionCandidates(f.owner).single()
            assertNull(candidate.body)
            assertEquals(summary, candidate.summary)
            val human = UIMessage.user("tea")
            val turn = f.begin(human)
            assertEquals(listOf(OrbisMemorySelected(id, summary, 1, "summary")), turn.selection.entries)
            val request = turn.project(listOf(human))
            assertTrue(request.text().contains(summary))
            assertFalse(request.text().contains(body))
            val stored = f.execute("read") { put("id", id) }.getValue("note").jsonObject
            assertEquals(body, stored.getValue("body").jsonPrimitive.content)
            assertEquals(summary, stored.getValue("summary").jsonPrimitive.content)
        }
    }

    private fun List<UIMessage>.text(): String = joinToString("\n") { it.toText() }
}
