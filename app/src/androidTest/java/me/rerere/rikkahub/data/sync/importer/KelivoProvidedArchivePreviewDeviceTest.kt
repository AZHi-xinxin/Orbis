package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Opt-in emulator-only preview. No import, Room, settings, live app, AI, network or content logging. */
@RunWith(AndroidJUnit4::class)
class KelivoProvidedArchivePreviewDeviceTest {
    @Test fun providedArchivePassesActualPreviewWithoutWritingApplicationData() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val name = arguments.getString("kelivoProvidedArchiveName")
        assumeTrue("provided archive preview is opt-in", name != null)
        check(name != null && Regex("kelivo-provided-preview-[0-9a-f]{16}\\.zip").matches(name)) {
            "kelivo_preview_invalid_basename"
        }
        val expected = arguments.getString("kelivoProvidedArchiveSha256")
        check(expected != null && Regex("[0-9a-f]{64}").matches(expected)) { "kelivo_preview_invalid_checksum" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner) { "kelivo_preview_requires_isolated_runner" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.MODEL.startsWith("sdk_gphone") || Build.MODEL.startsWith("Android SDK built for"))) {
            "kelivo_preview_requires_emulator"
        }
        val base = instrumentation.targetContext
        check(base.applicationContext.javaClass == Application::class.java) { "kelivo_preview_application_not_isolated" }
        val cache = base.cacheDir.canonicalFile
        val archive = File(cache, name).canonicalFile
        check(archive.parentFile == cache && archive.isFile) { "kelivo_preview_missing_private_copy" }
        val temporary = Files.createTempDirectory(cache.toPath(), "kelivo-provided-staging-").toFile().canonicalFile
        check(temporary.parentFile == cache) { "kelivo_preview_invalid_staging" }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = temporary
        }
        try {
            assertEquals("kelivo_preview_checksum_mismatch", expected, KelivoChatArchive.fingerprint(archive))
            val sink = object : DeepSeekImportSink {
                override suspend fun exists(id: Uuid): Boolean = error("kelivo_preview_must_not_query_repository")
                override suspend fun insert(conversation: Conversation): Boolean = error("kelivo_preview_must_not_write_repository")
            }
            val preview = KelivoChatImporter(context, sink).inspect(archive)
            assertEquals(expected, preview.fingerprint)
            assertEquals(expected, KelivoChatArchive.fingerprint(archive))
            assertTrue("kelivo_preview_snapshot_not_removed",
                File(temporary, ChatImportStaging.ROOT).listFiles()?.isEmpty() == true)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("kelivo_preview", "success")
                putString("sha256", expected)
                putInt("conversations", preview.conversations.size)
                putInt("physical_messages", preview.conversations.sumOf { it.totalNodes })
                putInt("selected_messages", preview.conversations.sumOf { it.messageCount })
                putInt("branch_points", preview.conversations.sumOf { it.branchPointCount })
                putInt("warnings", preview.warnings.size)
                putInt("omitted_attachment_references", preview.omittedAttachmentReferences)
                putInt("repository_calls", 0)
            })
        } catch (_: Throwable) {
            // The supplied archive is private. Do not attach an exception cause, message, title or source row.
            throw AssertionError("kelivo_provided_preview_failed")
        } finally {
            val archiveRemoved = archive.delete() && !archive.exists()
            val stagingRemoved = temporary.deleteRecursively() && !temporary.exists()
            check(archiveRemoved && stagingRemoved) { "kelivo_preview_private_copy_or_staging_cleanup_failed" }
        }
    }
}
