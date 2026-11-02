package me.rerere.rikkahub.ui.pages.backup.tabs

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.sync.importer.UniversalChatGuide
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UniversalImportGuideDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeClipboardGetsCompletePromptAndValidExampleThenDialogCloses() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrument.targetContext.applicationContext.javaClass)
        val showing = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (showing.value) UniversalImportGuideDialog { showing.value = false } } }
        compose.onNodeWithTag("universal-copy-prompt").assertIsDisplayed().performClick()
        compose.onNodeWithTag("universal-copy-result").assertIsDisplayed()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val output = File(instrument.targetContext.cacheDir, "universal-guide-preview.png")
        try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        compose.runOnIdle {
            val text = instrument.targetContext.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertEquals(UniversalChatGuide.conversionPrompt, text)
            assertTrue(text.contains(UniversalChatGuide.example))
        }
        compose.onNodeWithTag("universal-copy-example").performClick()
        compose.runOnIdle {
            assertEquals(UniversalChatGuide.example, instrument.targetContext.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("通用聊天记录 · 格式说明").assertDoesNotExist()
    }
}
