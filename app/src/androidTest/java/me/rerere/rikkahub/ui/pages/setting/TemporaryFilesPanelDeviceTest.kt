package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.files.TemporaryFileCleanup
import me.rerere.rikkahub.data.orbis.theme.ThemeColors
import me.rerere.rikkahub.data.orbis.theme.ThemePackage
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Synthetic private cache only; no settings, real DB, network or user's recovery points. */
class TemporaryFilesPanelDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @get:Rule val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private val now = System.currentTimeMillis()
    private fun old(root: File, name: String, body: String = "synthetic backup") = File(root, name).apply {
        requireNotNull(parentFile).mkdirs(); writeText(body); check(setLastModified(now - TimeUnit.DAYS.toMillis(2)))
    }
    private fun show(cleaner: TemporaryFileCleanup, dark: () -> Boolean = { true }) {
        compose.setContent {
            CompositionLocalProvider(LocalDarkMode provides dark()) { MaterialTheme { OrbisVisualTheme {
                val chat = ChatTheme(ThemePackage(name = "Synthetic pale chat", colors = ThemeColors(
                    background = "#EAF2FF", surface = "#F4F7FF", text = "#F4EBDD")), null)
                CompositionLocalProvider(LocalChatTheme provides chat) { OrbisInterfaceTheme {
                    Surface(Modifier.fillMaxSize().testTag("temporary-test-surface")) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { TemporaryFilesPanel(cleaner) }
                    }
                } }
            } } }
        }
    }
    private fun awaitScan() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("temporary-files-scanning").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("temporary-files-clean").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun previewCancelAndConfirmPreserveChatsNewFilesAndOneRepairCopy() {
        val root = temporary.newFolder("cache")
        val transfer = old(root, "portable-backup-1.zip")
        val copies = (1..4).map { old(root, "orbis-stage22-backup-$it.tar") }
        val recent = old(root, "portable-backup-2.zip").apply { setLastModified(now) }
        val protected = old(temporary.root, "no_backup/automatic-versions/keep.payload")
        val chat = old(temporary.root, "files/upload/keep.png")
        show(TemporaryFileCleanup(root, { now })); awaitScan()
        assertTrue(transfer.exists()); assertEquals(4, copies.count { it.exists() })
        compose.onNodeWithTag("temporary-files-clean").performScrollTo().performClick()
        compose.onNodeWithTag("temporary-files-cancel").performClick()
        assertTrue(transfer.exists()); assertEquals(4, copies.count { it.exists() })
        compose.onNodeWithTag("temporary-files-clean").performScrollTo().performClick()
        compose.onNodeWithTag("temporary-files-confirm").performClick()
        compose.waitUntil(10_000) { !transfer.exists() && copies.count { it.exists() } == 1 }
        awaitScan()
        compose.onNodeWithTag("temporary-files-notice").assertTextContains("已清理 4 个", substring = true)
        compose.onNodeWithTag("temporary-files-clean").assertIsNotEnabled()
        assertTrue(recent.exists()); assertTrue(protected.exists()); assertTrue(chat.exists())
    }

    @Test fun dayNightToggleKeepsSelectionAndDoesNotInheritPaleChatSurface() {
        val root = temporary.newFolder("cache"); old(root, "portable-backup-1.zip")
        var dark by mutableStateOf(true)
        show(TemporaryFileCleanup(root, { now }), { dark }); awaitScan()
        compose.onNodeWithTag("temporary-files-BACKUP_TRANSFER").performScrollTo().performClick()
        for (night in listOf(true, false, true)) {
            compose.runOnIdle { dark = night }
            val image = compose.onNodeWithTag("temporary-test-surface").captureToImage()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "storage-cleanup-${if (night) "night" else "day"}.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val surface = image.toPixelMap()[2, 2]
            assertEquals(night, surface.luminance() < .15f)
            compose.onNodeWithTag("temporary-files-BACKUP_TRANSFER").assertIsOff()
            compose.onNodeWithTag("temporary-files-clean").assertIsNotEnabled()
        }
    }

    @Test fun activeBackupPreventsConfirmedDeletionAndExplainsWhy() {
        val root = temporary.newFolder("cache"); val transfer = old(root, "portable-backup-1.zip")
        show(TemporaryFileCleanup(root, { now }, { false })); awaitScan()
        compose.onNodeWithTag("temporary-files-clean").performScrollTo().performClick()
        compose.onNodeWithTag("temporary-files-confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("temporary-files-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("temporary-files-notice").assertTextContains("正在备份", substring = true)
        assertTrue(transfer.exists())
    }
}
