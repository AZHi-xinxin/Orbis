package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OrbisCommunityDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun groupNumberCopiesAndOriginalQrCanBeViewedAndSaved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("community-preview")) { OrbisCommunityCard() }
        } } }
        compose.onNodeWithText("1129700652").assertIsDisplayed()
        compose.onNodeWithText("复制群号").performClick()
        compose.runOnIdle { assertEquals("1129700652", (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()) }
        val folder = File(context.getExternalFilesDir(null), "dev123-preview").apply { mkdirs() }
        compose.onNodeWithTag("community-preview").captureToImage().asAndroidBitmap().let { image ->
            File(folder, "community-card.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("查看二维码").performClick()
        compose.onNodeWithContentDescription("QQ 群 1129700652 入群二维码").assertExists()
        compose.onNodeWithText("保存二维码").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("已保存到相册，请用 QQ 扫一扫识别").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithText("查看二维码").assertIsDisplayed()
    }
}
