package me.rerere.rikkahub.data.orbis.privateroom

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Synthetic cache-only context; no real vaults, application startup, network or model calls. */
class PrivateRoomPreflightDeviceTest {
    private fun isolated(test: (Context, File) -> Unit) {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is IsolatedGenerationLoopRunner)
        check(runner.targetContext.applicationContext.javaClass == Application::class.java)
        val parent = runner.targetContext.cacheDir.canonicalFile
        check(parent.isDirectory || parent.mkdirs())
        val root = Files.createTempDirectory(parent.toPath(), "private-preflight-").toFile().canonicalFile
        val context = object : ContextWrapper(runner.targetContext) {
            override fun getApplicationContext(): Context = this
            // This fixture supplies a canonical cache path; use its matching trusted app root.
            override fun getDataDir(): File = runner.targetContext.dataDir.canonicalFile
            override fun getNoBackupFilesDir(): File = root
        }
        try { test(context, root) } finally {
            check(root.canonicalFile.parentFile == parent && root.name.startsWith("private-preflight-"))
            check(root.deleteRecursively())
        }
    }

    @Test fun checkingMissingRoomCreatesNeitherVaultNorRouteFiles() = isolated { context, root -> runBlocking {
        val model = Model(modelId = "synthetic-only")
        val owner = Assistant(chatModelId = model.id)
        val settings = Settings(assistants = listOf(owner), providers = listOf(ProviderSetting.OpenAI(
            baseUrl = "https://synthetic.invalid/v1", models = listOf(model))))
        repeat(3) {
            val check = PrivateRoomRuntime.preflight(context, owner.id.toString()) { settings }
            assertEquals(PrivateRoomReason.ROOM_NOT_CREATED, check.reason)
            assertFalse(check.canVisit)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    } }

    @Test fun unreadableChoiceHasFixedDiagnosisAndCannotFallBackToModel() = isolated { context, root -> runBlocking {
        val model = Model(modelId = "synthetic-only")
        val owner = Assistant(chatModelId = model.id)
        val settings = Settings(assistants = listOf(owner), providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val routes = root.resolve("orbis-private-room-routes").also { check(it.mkdir()) }
        val source = routes.resolve("${owner.id}.json").also { it.writeText("SYNTHETIC-PRIVATE-ERROR-MATERIAL") }
        val before = source.readBytes()
        val preflight = PrivateRoomRuntime.preflight(context, owner.id.toString()) { settings }
        assertEquals(PrivateRoomReason.CHOICE_UNREADABLE, preflight.reason)
        // A client with no usable DNS also ensures an unexpected network path cannot contact a provider.
        var lookups = 0
        val client = OkHttpClient.Builder().dns { lookups++; error("forbidden test network") }.build()
        val result = PrivateRoomRuntime.visitDetailed(context, owner.id.toString(), { settings }, client)
        assertEquals(PrivateRoomOutcome.UNAVAILABLE, result.outcome)
        assertEquals(PrivateRoomReason.CHOICE_UNREADABLE, result.reason)
        assertFalse(result.mayHaveSavedChanges)
        assertFalse(result.toString().contains("SYNTHETIC-PRIVATE-ERROR-MATERIAL"))
        assertEquals(0, lookups)
        assertArrayEquals(before, source.readBytes())
        assertFalse(root.resolve("orbis-private-vaults").exists())
    } }
}
