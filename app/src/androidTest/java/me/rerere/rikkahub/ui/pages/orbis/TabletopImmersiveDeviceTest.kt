package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real dialog/IME windows, synthetic state only. Never connects to a table or model. */
class TabletopImmersiveDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun gameShowsTransparentBarsKeyboardRemainsUsableAndClosingRestoresChatWindow() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.orbis.agent.bodylab")
        var open by mutableStateOf(false)
        var text by mutableStateOf("")
        var dialogView: View? = null
        compose.setContent { MaterialTheme {
            Button(onClick = { open = true }, Modifier.testTag("open-game")) { Text("进入游戏") }
            if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(
                usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                TabletopEdgeToEdgeBars()
                val view = LocalView.current
                SideEffect { dialogView = view }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().displayCutoutPadding().imePadding()) {
                        Text("圆桌测试")
                        Spacer(Modifier.weight(1f))
                        TextField(text, { text = it }, Modifier.testTag("chat-input"))
                        Button(onClick = { open = false }, Modifier.testTag("close-game")) { Text("返回聊天") }
                    }
                }
            }
        } }
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        compose.onNodeWithTag("open-game").performClick()
        fun waitForInsets(predicate: (WindowInsetsCompat) -> Boolean) {
            compose.waitUntil(8_000) {
                var accepted = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    accepted = dialogView?.let { ViewCompat.getRootWindowInsets(it) }?.let(predicate) == true
                }
                accepted
            }
        }
        waitForInsets { it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars()) }
        compose.onNodeWithTag("chat-input").performClick().performTextInput("键盘可用")
        waitForInsets { it.isVisible(WindowInsetsCompat.Type.ime()) }
        compose.onNodeWithTag("close-game").assertIsDisplayed()
        compose.runOnIdle {
            val v = dialogView!!
            val w = (v.parent as androidx.compose.ui.window.DialogWindowProvider).window
            assertEquals(android.graphics.Color.TRANSPARENT, w.statusBarColor)
            assertEquals(android.graphics.Color.TRANSPARENT, w.navigationBarColor)
            WindowCompat.getInsetsController(w, v).hide(WindowInsetsCompat.Type.ime())
        }
        waitForInsets { !it.isVisible(WindowInsetsCompat.Type.ime()) && it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars()) }
        val ins = InstrumentationRegistry.getInstrumentation()
        val shot = ins.uiAutomation.takeScreenshot()
        File(ins.targetContext.filesDir, "tabletop-immersive.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
        compose.onNodeWithTag("close-game").performClick()
        compose.onNodeWithTag("open-game").assertIsDisplayed()
        compose.waitUntil(8_000) {
            var visible = false
            ins.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.statusBars()) == true
            }
            visible
        }
        assertEquals("键盘可用", text)
    }
}
