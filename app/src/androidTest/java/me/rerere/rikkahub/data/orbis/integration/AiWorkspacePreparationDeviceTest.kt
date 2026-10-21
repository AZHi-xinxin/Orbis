package me.rerere.rikkahub.data.orbis.integration

import android.app.Application
import android.system.Os
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.createOrbisBackupTool
import me.rerere.rikkahub.data.ai.tools.createOrbisSelfConfigurationTool
import me.rerere.rikkahub.data.ai.tools.createPreparedWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.AiBackupBinding
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiWorkspacePreparationDeviceTest {
    @Test fun fixedToolSetCanPrepareWriteAndBindBackupInOneGenerationWithoutShellOrCredentialEcho() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.applicationContext.javaClass == Application::class.java)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "native-prepare-").toFile()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val owner = Assistant()
            val store = SettingsStore(context, AppScope().also { it.cancel() }).also {
                it.settingsFlow.value = Settings(assistants = listOf(owner))
            }
            val manager = WorkspaceManager(File(root, "workspaces"))
            val repository = WorkspaceRepository(database.workspaceDao(), manager, RootfsInstaller(manager), store)
            val id = AiWorkspacePreparation.defaultId(owner.id.toString())
            fun bound() = store.settingsFlow.value.assistants.single().workspaceId?.toString() == id
            suspend fun update(transform: (Settings) -> Settings): Boolean {
                store.settingsFlow.value = transform(store.settingsFlow.value); return true
            }
            // Freeze all definitions before workspace creation, like the production generation loop.
            val writer = createPreparedWorkspaceTools(id, repository, ::bound).single()
            val configure = createOrbisSelfConfigurationTool(owner.id.toString(), { store.settingsFlow.value }, ::update,
                { _, _, _ -> error("no_network") }, prepareWorkspace = {
                    AiWorkspacePreparation.prepare(owner.id.toString(), { store.settingsFlow.value }, repository::getById,
                        repository::createNative, repository::prepareNative, ::update)
                })
            val backup = createOrbisBackupTool(id, ::bound, readFile = {
                repository.readNativeChunk(id, it, 0, 65536, null).text.toByteArray()
            }, bind = { connection, automatic, secrets ->
                update { AiBackupBinding.bind(it, owner.id.toString(), id, connection, automatic, secrets) }
            }, status = { buildJsonObject {} }, request = {})
            val writeInput = buildJsonObject {
                put("path", "/workspace/config/backup.json")
                put("text", """{"target":"webdav","webdav":{"url":"https://example.invalid/dav","username":"fixture","password":"fixture-secret","path":"orbis"}}""")
                put("overwrite", false)
            }
            assertTrue(writer.execute(writeInput).toString().contains("workspace_not_bound"))
            assertTrue(configure.execute(buildJsonObject { put("action", "workspace_prepare") }).toString().contains("workspace_ready"))
            assertTrue(bound())
            assertTrue(manager.filesDir(id).isDirectory)
            assertFalse(manager.linuxDir(id).exists())
            assertFalse(manager.hasRootfs(id))
            assertTrue(writer.isApprovalCurrent())
            val writeReceipt = writer.execute(writeInput).toString()
            assertTrue(writeReceipt.contains("sizeBytes"))
            assertFalse(writeReceipt.contains("fixture-secret"))
            val inspection = Json.parseToJsonElement(backup.execute(buildJsonObject {
                put("action", "inspect"); put("connection_file", "/workspace/config/backup.json")
            }).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            assertEquals("inspected_not_bound", inspection["status"]!!.jsonPrimitive.content)
            assertFalse(inspection.toString().contains("fixture-secret"))
            val receipt = backup.execute(buildJsonObject {
                put("action", "bind"); put("connection_file", "/workspace/config/backup.json")
                put("expected_sha256", inspection["expected_sha256"]!!.jsonPrimitive.content)
                put("auto_upload", false)
            }).toString()
            assertTrue(receipt.contains("bound"))
            assertTrue(AiBackupBinding.owns(store.settingsFlow.value, owner.id.toString(), id))
            assertFalse(receipt.contains("fixture-secret"))
            store.settingsFlow.value = store.settingsFlow.value.copy(assistants = listOf(owner))
            assertFalse(writer.isApprovalCurrent())
            assertTrue(writer.execute(writeInput).toString().contains("workspace_not_bound"))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test fun nativePreparationRejectsSymlinkInsteadOfCreatingFilesOutsideWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "native-prepare-link-").toFile()
        try {
            val manager = WorkspaceManager(File(root, "workspaces"))
            val outside = File(root, "outside").also { check(it.mkdir()) }
            val id = "synthetic-owner"
            Os.symlink(outside.path, manager.workspaceDir(id).path)
            assertThrows(Exception::class.java) { manager.prepareNativeWorkspace(id) }
            assertFalse(File(outside, "files").exists())
            check(manager.workspaceDir(id).delete()) // Remove the link before scoped fixture cleanup.
        } finally { root.deleteRecursively() }
    }
}
