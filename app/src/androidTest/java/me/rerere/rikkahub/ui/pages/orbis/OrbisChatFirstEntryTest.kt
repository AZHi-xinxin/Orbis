package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import me.rerere.rikkahub.testutil.createShellComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real WebView star gestures against in-memory HTML; no user account, conversation or network. */
@RunWith(AndroidJUnit4::class)
class OrbisChatFirstEntryTest {
    private val compose = createShellComposeRule()
    private val isolatedApplication = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolatedApplication).around(compose)

    private val ready = AtomicBoolean(false)
    private val currentView = AtomicReference<WebView?>()
    private val blockedNavigation = AtomicInteger(0)
    private val conversationMarker = "synthetic-conversation-kept"

    private fun showFixture(foreignFrame: Boolean = false) {
        val policy = OrbisCloudWebPolicy.from("https://orbis-navigation.example.org/")!!
        val frame = if (foreignFrame) """
            <iframe src="https://foreign.example.org/frame" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>
        """.trimIndent() else ""
        compose.setContent {
            val navigation = remember { OrbisHomeNavigationState() }
            MaterialTheme {
                if (navigation.visible) {
                    BackHandler {
                        navigateBackFromOrbisCloudHome(currentView.get(), policy, navigation::returnToChat)
                    }
                    AndroidView(modifier = Modifier.fillMaxSize().testTag("synthetic-cloud-home"),
                        factory = { context ->
                            WebView(context).apply {
                                currentView.set(this)
                                ready.set(false)
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = object : OrbisCloudWebViewClient(policy,
                                    onLoading = { ready.set(!it) },
                                    onBlockedNavigation = { blockedNavigation.incrementAndGet() },
                                    onOpenChat = navigation::returnToChat) {
                                    override fun responseForAllowedRequest(request: WebResourceRequest) =
                                        if (request.url.host == "foreign.example.org")
                                            WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("""
                                                <!doctype html><html><body>
                                                <a href="${OrbisLocalPolicy.OPEN_CHAT}" target="_top"
                                                   style="position:fixed;inset:0;display:block">Foreign frame action</a>
                                                <script>parent.postMessage('fixture-frame-ready', '*');</script>
                                                </body></html>
                                            """.trimIndent().toByteArray()))
                                        else
                                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("""
                                            <!doctype html><html><head>
                                            <meta name="viewport" content="width=device-width,initial-scale=1">
                                            </head><body>
                                            <button id="starmapEntryBtn" style="position:fixed;left:25%;top:25%;width:50%;height:50%">
                                              <svg width="100%" height="100%"><rect width="100%" height="100%"/></svg>
                                            </button>
                                            <script>
                                              window.fixtureFrameReady = false;
                                              window.addEventListener('message', event => {
                                                if (event.origin === 'https://foreign.example.org' &&
                                                    event.data === 'fixture-frame-ready') window.fixtureFrameReady = true;
                                              });
                                              window.legacyStarCalls = 0;
                                              document.getElementById('starmapEntryBtn').addEventListener('click', () => {
                                                window.legacyStarCalls++; location.hash = '#starmap';
                                              });
                                            </script>$frame</body></html>
                                        """.trimIndent().toByteArray()))
                                }.also { it.installChatNavigation(this) }
                                loadUrl(policy.homeUrl)
                            }
                        }, onRelease = { view ->
                            currentView.set(null)
                            view.stopLoading()
                            view.destroy()
                        })
                } else Column {
                    Text(conversationMarker, Modifier.testTag("synthetic-native-chat"))
                    Button(onClick = navigation::openHome, modifier = Modifier.testTag("synthetic-open-orbis")) {
                        Text("打开原 Orbis")
                    }
                }
            }
        }
    }

    private fun openFixtureHome() {
        compose.onNodeWithTag("synthetic-open-orbis").performClick()
        compose.waitUntil(15_000) { ready.get() }
        compose.onNodeWithTag("synthetic-cloud-home").assertIsDisplayed()
        // onPageFinished starts the fallback script asynchronously. Loading complete alone
        // does not prove that the real star's capture listener and label have been installed.
        compose.waitUntil(5_000) {
            evaluate("""
                (() => {
                    const star = document.getElementById('starmapEntryBtn');
                    return window.__orbisNativeChatNavigation === true && !!star &&
                        star.getAttribute('aria-label') === '返回当前聊天';
                })()
            """.trimIndent()) == "true"
        }
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread {
            currentView.get()!!.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("Synthetic JS evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun launchStartsInChatAndCloudStarReturnsWithoutReplacingChatState() {
        showFixture()
        compose.onNodeWithTag("synthetic-native-chat").assertIsDisplayed()
        repeat(2) {
            openFixtureHome()
            assertEquals("\"返回当前聊天\"", evaluate("document.getElementById('starmapEntryBtn').getAttribute('aria-label')"))
            assertEquals("\"undefined\"", evaluate("typeof window.Android"))
            compose.onNodeWithTag("synthetic-cloud-home").performTouchInput { click(center) }
            compose.waitUntil(5_000) { currentView.get() == null }
            compose.onNodeWithText(conversationMarker).assertIsDisplayed()
        }
    }

    @Test fun programmaticStarClicksHaveNoNativeNavigationPrivilege() {
        showFixture()
        openFixtureHome()
        evaluate("document.getElementById('starmapEntryBtn').click(); void 0")
        assertEquals("1", evaluate("window.legacyStarCalls"))
        compose.onNodeWithTag("synthetic-cloud-home").assertIsDisplayed()
        compose.onNodeWithTag("synthetic-native-chat").assertDoesNotExist()
    }

    @Test fun backReturnsFromCloudSubpageToCalendarThenRetainedChat() {
        showFixture()
        openFixtureHome()
        evaluate("location.hash = '#reading'; void 0")
        assertEquals("\"#reading\"", evaluate("location.hash"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("\"#calendar\"", evaluate("location.hash"))
        compose.onNodeWithTag("synthetic-cloud-home").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { currentView.get() == null }
        compose.onNodeWithText(conversationMarker).assertIsDisplayed()
        openFixtureHome()
        compose.onNodeWithTag("synthetic-cloud-home").performTouchInput { click(center) }
        compose.waitUntil(5_000) { currentView.get() == null }
        compose.onNodeWithText(conversationMarker).assertIsDisplayed()
    }

    @Test fun foreignIframeGestureCannotInvokeTheTopLevelChatAction() {
        showFixture(foreignFrame = true)
        openFixtureHome()
        compose.waitUntil(5_000) { evaluate("window.fixtureFrameReady") == "true" }
        compose.onNodeWithTag("synthetic-cloud-home").performTouchInput { click(center) }
        compose.waitUntil(5_000) { blockedNavigation.get() > 0 }
        compose.onNodeWithTag("synthetic-cloud-home").assertIsDisplayed()
        compose.onNodeWithTag("synthetic-native-chat").assertDoesNotExist()
        assertEquals("\"https://orbis-navigation.example.org\"", evaluate("location.origin"))
    }
}
