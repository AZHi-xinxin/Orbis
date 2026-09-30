package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.integration.createHubPreviewFile
import me.rerere.rikkahub.data.orbis.integration.OrbisTechHubException
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels and one random cache directory only; no credentials, provider, DB or HTTP. */
@RunWith(AndroidJUnit4::class)
class OrbisTechHubPreviewDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val parent = instrumentation.targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(parent.toPath(), "orbis-hub-preview-test-").toFile()
        fun jpeg(): File = createHubPreviewFile(root).also { file ->
            val bitmap = Bitmap.createBitmap(32, 20, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
        }
        override fun close() {
            check(root.parentFile == parent && root.canonicalFile == root && root.name.startsWith("orbis-hub-preview-test-"))
            check(!Files.isSymbolicLink(root.toPath()))
            // Exactly these children belong to this fixture; never walk a link or a broad tree.
            val preview = File(root, "orbis-techhub-preview")
            if (Files.isSymbolicLink(preview.toPath())) check(preview.delete())
            else if (preview.exists()) {
                check(preview.canonicalFile == preview && preview.isDirectory)
                preview.listFiles().orEmpty().forEach { file ->
                    check(file.parentFile == preview && file.canonicalFile == file && file.isFile)
                    check(!Files.isSymbolicLink(file.toPath()) && file.name.startsWith("hub-") && file.name.endsWith(".bin"))
                    check(file.delete())
                }
                check(preview.delete())
            }
            val alias = File(root, "trusted-alias")
            if (Files.isSymbolicLink(alias.toPath())) check(alias.delete())
            val outside = File(root, "untouched-child")
            if (outside.exists()) { check(outside.isDirectory && outside.canonicalFile == outside); check(outside.delete()) }
            check(root.listFiles().orEmpty().isEmpty()); check(root.delete())
        }
    }

    @Test fun trustedAndroidStyleParentSymlinkIsResolvedBeforeCreatingPreview() {
        Fixture().use { fixture ->
            val alias = File(fixture.root, "trusted-alias")
            Files.createSymbolicLink(alias.toPath(), fixture.root.toPath())
            assertNotEquals(alias.absoluteFile, alias.canonicalFile)
            val file = createHubPreviewFile(alias)
            assertEquals(File(fixture.root, "orbis-techhub-preview"), file.parentFile)
            assertEquals(file.canonicalFile, file.absoluteFile)
        }
    }
    @Test fun ownedPreviewDirectorySymlinkIsStillRejectedWithoutFollowingOrDeletingTarget() {
        Fixture().use { fixture ->
            val outside = File(fixture.root, "untouched-child").also { check(it.mkdir()) }
            val link = File(fixture.root, "orbis-techhub-preview")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(OrbisTechHubException::class.java) { createHubPreviewFile(fixture.root) }
            assertTrue(outside.isDirectory); assertTrue(outside.listFiles().orEmpty().isEmpty())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }
    }
    @Test fun validJpegDecodesWithoutChangingOriginalBytes() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val file = fixture.jpeg(); val original = file.readBytes()
            val bitmap = requireNotNull(hubPreviewBitmap(file, "image/jpeg"))
            try { assertEquals(32, bitmap.width); assertEquals(20, bitmap.height); assertArrayEquals(original, file.readBytes()) }
            finally { bitmap.recycle() }
        }
    }
    @Test fun executableFormatAndInvalidPixelsAreNotRenderedAsAnImage() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val file = fixture.jpeg()
            assertNull(hubPreviewBitmap(file, "image/svg+xml"))
            file.writeText("<svg><script>synthetic only</script></svg>")
            assertNull(hubPreviewBitmap(file, "image/jpeg"))
        }
    }
    @Test fun alreadyDecodedLocalImageIsDisplayedWithoutAnyConnectionOrDownload() {
        Fixture().use { fixture ->
            val bitmap = runBlocking { requireNotNull(hubPreviewBitmap(fixture.jpeg(), "image/jpeg")) }
            // The composable accepts pixels, not a URL/store/client; it cannot perform network I/O.
            compose.setContent { MaterialTheme { HubAttachmentImagePreview(bitmap, "synthetic.jpg") } }
            compose.onNodeWithContentDescription("附件图片：synthetic.jpg").assertIsDisplayed()
        }
    }
}
