package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.createOrbisBackupTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer
import me.rerere.workspace.WorkspaceNativeFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native workspace IO + HTTP parser; no shell, model, Koin or real account. */
@RunWith(AndroidJUnit4::class)
class AiBackupChannelDeviceTest {
    @Test fun assistantInspectsAndBindsNativeFileWithoutShellAndCanReconnectAfterFreshSettings() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        val root = Files.createTempDirectory(inst.targetContext.cacheDir.toPath(), "ai-backup-channel-").toFile()
        try {
            PrivateRoomLoopbackServer().use { server ->
                val workspace = Uuid.random()
                val owner = Assistant(name = "Synthetic backup owner", workspaceId = workspace)
                var settings = Settings(assistants = listOf(owner))
                val original = settings
                val connection = BackupRecoveryConnection(target = "webdav", webdav = WebDavConfig(
                    server.url(""), "synthetic-user", "not-a-real-password", "archive"))
                val native = WorkspaceNativeFiles(root)
                native.writeText(listOf("backup.json"), connection.encode().decodeToString(), false)
                val tool = createOrbisBackupTool(workspace.toString(), { true }, readFile = { path ->
                    check(path == "/workspace/backup.json")
                    val chunk = native.readChunk(listOf("backup.json"), maxBytes = BackupRecoveryConnection.MAX_BYTES)
                    check(chunk.eof)
                    chunk.text.toByteArray(Charsets.UTF_8)
                }, bind = { value, automatic, secrets ->
                    settings = AiBackupBinding.bind(settings, owner.id.toString(), workspace.toString(), value, automatic, secrets)
                }, status = { buildJsonObject {} }, request = {})
                suspend fun execute(args: JsonObject) =
                    Json.parseToJsonElement(tool.execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
                val inspection = execute(buildJsonObject { put("action", "inspect"); put("connection_file", "/workspace/backup.json") })
                assertEquals(original, settings)
                assertEquals(0, server.requestCount)
                assertFalse(inspection.toString().contains("not-a-real-password"))
                val hash = inspection.getValue("expected_sha256").jsonPrimitive.content
                val bindArgs = buildJsonObject {
                    put("action", "bind"); put("connection_file", "/workspace/backup.json"); put("expected_sha256", hash)
                }
                assertTrue(tool.needsApproval(bindArgs))
                assertEquals("bound", execute(bindArgs).getValue("status").jsonPrimitive.content)
                assertTrue(AiBackupBinding.owns(settings, owner.id.toString(), workspace.toString()))
                assertEquals(0, server.requestCount) // Saving settings is never a connectivity receipt.
                server.enqueue(PrivateRoomLoopbackResponse().setResponseCode(207)
                    .setBody("""<D:multistatus xmlns:D="DAV:"/>"""))
                RemoteBackupStore(settings).use { assertTrue(it.listNames().isEmpty()) }
                assertEquals("PROPFIND", server.takeRequest().method)
                assertEquals(1, server.requestCount)

                // Fresh-install settings can import the user-held connection but must not upload/prune.
                val recovered = BackupRecoveryConnection.decode(
                    BackupRecoveryConnection.from(settings).encode()).applyTo(Settings(assistants = listOf(owner)))
                assertEquals(connection.webdav, recovered.webDavConfig)
                assertFalse(recovered.autoBackup.enabled)
                assertNull(recovered.autoBackup.controllerAssistant)
                assertFalse(AiBackupBinding.owns(recovered, owner.id.toString(), workspace.toString()))

                val prior = settings
                native.writeText(listOf("backup.json"), connection.encode().decodeToString() + " ", true)
                assertEquals("not_confirmed", execute(bindArgs).getValue("status").jsonPrimitive.content)
                assertEquals(prior, settings) // An old inspection cannot authorize different bytes.
            }
        } finally { root.deleteRecursively() }
    }
}
