package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrbisThemePackageDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun previewCancelConfirmAndRevertUseThePublishedVersion() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, inst.targetContext.applicationContext.javaClass)
        val root = File(inst.targetContext.cacheDir, "theme-fixture-${UUID.randomUUID()}").canonicalFile
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/owner"))
            val d = store.write(themeJson.encodeToString(ThemePackage(name = "海边的信")))
            val e = store.publish(d.id, d.revision); val ref = ThemeRef(e.id, e.revision)
            assertFalse(store.requestApply(ref))
            compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) { ThemePackageManagerContent(store) } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("预览主题").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("预览主题").performClick()
            compose.onNodeWithText("风和星光都在这里。").assertIsDisplayed()
            compose.onNodeWithText("先不更换").performClick()
            assertNull(store.snapshot().active)
            compose.onNodeWithText("预览主题").performClick()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(inst.targetContext.getExternalFilesDir(null), "theme-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("应用这个主题").performClick()
            compose.waitUntil(5000) { store.snapshot().active == ref }
            compose.onNodeWithText("当前：海边的信").assertExists()
            compose.onNodeWithText("回到上个外观").performScrollTo().performClick()
            compose.waitUntil(5000) { store.snapshot().active == null }
            compose.onNodeWithText("当前：原来的手工外观").assertExists()
        } finally { root.deleteRecursively() }
    }
}
