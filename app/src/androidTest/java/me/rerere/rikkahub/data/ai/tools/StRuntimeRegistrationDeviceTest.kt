package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.st.StRuntimeHost
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSManager
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.uuid.Uuid

class StRuntimeRegistrationDeviceTest {
    @Test fun actualFactoryRebuildsOptInCatalogueAndRejectsRevokedOrDifferentWorkspace() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.packageName.endsWith(".bodylab") && context.applicationContext.javaClass == Application::class.java)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dormant = AppScope().also { it.cancel() }
        val workspaceId = Uuid.random()
        val root = "st-catalogue-fixture-$workspaceId"
        val otherRoot = "st-other-fixture-$workspaceId"
        val assistant = Assistant(workspaceId = workspaceId, localTools = emptyList(), enableMemory = false,
            enableWebSearch = false, enableRecentChatsReference = false)
        val store = SettingsStore(context, dormant).also { it.settingsFlow.value = Settings(
            assistants = listOf(assistant), assistantId = assistant.id, mcpServers = emptyList()) }
        val host = StRuntimeHost.get(context)
        try {
            val files = FilesManager(context, FilesRepository(db.managedFileDao()), dormant)
            startKoin { modules(module { single<FilesManager> { files }; single<SettingsStore> { store } }) }
            val manager = WorkspaceManager(File(context.cacheDir, root))
            File(manager.linuxDir(root), "bin").mkdirs()
            File(manager.linuxDir(root), "bin/sh").writeText("synthetic not executable")
            val repository = WorkspaceRepository(db.workspaceDao(), manager, RootfsInstaller(manager), store)
            db.workspaceDao().upsert(WorkspaceEntity(workspaceId.toString(), "Synthetic ST", root, "READY", 0L, 0L))
            val factory = ChatToolFactory(JsonInstant, MemoryRepository(db.memoryDao()),
                ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db, files, MessageFtsManager(db)),
                LocalTools(context, AppEventBus(), TTSManager(context), store), McpManager(store, dormant, files),
                SkillManager(context, store), repository, context, settingsStore = store)
            suspend fun catalogue() = factory.createTools(store.settingsFlow.value, assistant, Model())
            host.setEnabled(root, false)
            host.setEnabled(otherRoot, true)
            val before = catalogue()
            assertFalse(before.any { it.name == "orbis_st_runtime" })
            suspend fun diagnosis(tools: List<me.rerere.ai.core.Tool>) = tools.single { it.name == "orbis_configure" }
                .execute(buildJsonObject { put("action", "workspace_status") }).toString()
            assertTrue(diagnosis(before).contains("hosting_disabled"))
            host.setEnabled(root, true)
            // The previous generation is frozen; the next human send obtains the new catalogue.
            assertFalse(before.any { it.name == "orbis_st_runtime" })
            assertTrue(diagnosis(before).contains("new_turn_required"))
            val after = catalogue()
            val runtime = after.single { it.name == "orbis_st_runtime" }
            val help = after.single { it.name == "orbis_help" }.execute(buildJsonObject { put("topic", "tools") }).toString()
            assertTrue(help.contains("orbis_st_runtime"))
            assertTrue(runtime.execute(buildJsonObject { put("action", "status") }).toString().contains("stopped"))
            assertNull(host.saved(root).spec)
            assertTrue(diagnosis(after).contains("first_start_required"))
            host.setEnabled(root, false)
            assertFalse(catalogue().any { it.name == "orbis_st_runtime" })
            assertTrue(runtime.execute(buildJsonObject { put("action", "status") }).toString().contains("st_host_request_rejected"))
            host.setEnabled(root, true)
            store.settingsFlow.value = store.settingsFlow.value.copy(assistants = listOf(assistant.copy(workspaceId = Uuid.random())))
            assertFalse(catalogue().any { it.name == "orbis_st_runtime" })
            assertTrue(runtime.execute(buildJsonObject { put("action", "status") }).toString().contains("st_host_request_rejected"))
            store.settingsFlow.value = store.settingsFlow.value.copy(assistants = listOf(assistant))
            assertFalse(factory.createTools(store.settingsFlow.value, assistant, Model(), consultationReferenceOnly = true)
                .any { it.name == "orbis_st_runtime" })
        } finally {
            host.setEnabled(root, false); host.setEnabled(otherRoot, false)
            stopKoin(); db.close()
            File(context.cacheDir, root).deleteRecursively()
            File(context.noBackupFilesDir, "st-host/$root.json").delete()
            File(context.noBackupFilesDir, "st-host/$otherRoot.json").delete()
        }
    }
}
