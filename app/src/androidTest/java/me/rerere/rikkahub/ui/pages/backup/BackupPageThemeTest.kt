package me.rerere.rikkahub.ui.pages.backup

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.backup.tabs.AutomaticBackupTab
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackupPageThemeTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun automaticBackupBodyKeepsContrastAcrossNightAndDayWithoutChangingSettings() {
        val inst=InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        val store=SettingsStore(inst.targetContext, AppScope().also { it.cancel() })
        val original=Settings(init=false)
        store.settingsFlow.value=original
        var dark by mutableStateOf(true)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme=dark) {
            BackupPageLayout(0, {}, {}) { AutomaticBackupTab({}, {}, store) }
        } } }
        for (night in listOf(true,false,true)) {
            compose.runOnIdle { dark=night }
            compose.waitForIdle()
            compose.onNodeWithText("让记录有去处").assertIsDisplayed()
            val shot=compose.onRoot().captureToImage()
            val pixels=shot.toPixelMap();val bg=pixels[3,pixels.height/2].luminance()
            for (label in listOf("让记录有去处","本机自动存档","默认开启，保留最近 3 个完成版本")) {
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val ink=layouts.single().layoutInput.style.color.luminance()
                assertTrue("$label night=$night contrast", (maxOf(ink,bg)+.05f)/(minOf(ink,bg)+.05f)>4.5f)
            }
            val dir=java.io.File(inst.targetContext.filesDir,"dev273-review").apply {mkdirs()}
            java.io.File(dir,if(night) "backup-night.png" else "backup-day.png").outputStream().use {
                shot.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        assertEquals(original,store.settingsFlow.value)
    }
}
