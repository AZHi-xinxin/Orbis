package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real WebView detach/remount with a synthetic page. No remote requests or user storage. */
@RunWith(AndroidJUnit4::class)
class ShortcutWebRetentionDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val visible = mutableStateOf(true)
    private val page = mutableStateOf(0)
    private val sessions = listOf(ShortcutWebSession(), ShortcutWebSession())
    private val urls = listOf("https://fixture.invalid/one", "https://fixture.invalid/two")
    @Before fun setup() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
        sessions.forEachIndexed { i, s -> s.bind(urls[i], false); s.html = "<html><body><p>Fixture $i</p><input value='synthetic'><div style='height:3000px'></div></body></html>" }
        compose.setContent { MaterialTheme { if (visible.value) Dialog(onDismissRequest = { visible.value = false }) {
            OrbisShortcutWebView("fixture-267", urls[page.value], false, sessions[page.value]) {}
        } } }
        compose.waitUntil(15_000) { sessions[0].view != null }
        // A retained WebView object alone does not prove its initial document was displayed.
        // Read-only deliberately disables JavaScript, including evaluateJavascript probes.
        // WebView's native find API verifies rendered document text without enabling scripts.
        val matches = java.util.concurrent.atomic.AtomicInteger(0)
        compose.runOnIdle { sessions[0].view!!.setFindListener { _, count, done -> if(done) matches.set(count) } }
        compose.waitUntil(15_000) {
            compose.runOnIdle { sessions[0].view!!.findAllAsync("Fixture 0") }
            matches.get() > 0
        }
        assertNull(sessions[0].error)
    }
    @After fun cleanup() {
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        compose.runOnIdle { sessions.forEach { it.destroy() } }
    }
    @Test fun minimizingAndSwitchingPagesRetainTheSameViewsAndClosingAllowsNewView() {
        lateinit var first: WebView
        compose.runOnIdle { first = sessions[0].view!!; first.scrollTo(0, 180); visible.value = false }
        compose.waitForIdle()
        assertSame(first, sessions[0].view)
        compose.runOnIdle { visible.value = true }; compose.waitForIdle()
        assertSame(first, sessions[0].view)
        compose.runOnIdle { page.value = 1 }
        compose.waitUntil(15_000) { sessions[1].view != null }
        val second = sessions[1].view
        assertNotSame(first, second)
        compose.runOnIdle { page.value = 0 }; compose.waitForIdle()
        assertSame(first, sessions[0].view)
        assertSame(second, sessions[1].view)
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        compose.runOnIdle { sessions[0].destroy(); sessions[0].bind(urls[0], false); sessions[0].html = "<p>Fresh synthetic document</p>"; visible.value = true }
        compose.waitUntil(15_000) { sessions[0].view != null }
        assertNotSame(first, sessions[0].view)
    }
}
