package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Targeted focus-order regression for the garden-return crash. Passing a synthetic
 * fixture does not establish that every OEM keyboard/lifecycle crash is fixed.
 * The old layer tests kept the same WebView mounted. Here the production navigation
 * state removes a focused AndroidView while a focusable lazy list remains composed
 * inside lookahead layout, matching the relevant structure of the reported stack.
 *
 * Uses only synthetic in-memory rows and a network-blocked document. No production
 * Application, Koin, conversations, imports, stores, credentials or recovery state.
 */
@RunWith(AndroidJUnit4::class)
class OrbisGardenExitFocusDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val isolated = object : ExternalResource() {
        override fun before() {
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    private class Fixture {
        val navigation = OrbisHomeNavigationState()
        val compact = mutableStateOf(false)
        val created = AtomicInteger()
        val loaded = AtomicInteger()
        val released = AtomicInteger()
        val draft = mutableStateOf("")
        var focusAfterNavigation: Boolean? = null
        lateinit var view: WebView
        fun returnToChat() {
            navigation.returnToChat()
            // Capture synchronously, before Compose applies the visibility write;
            // a later assertion on an already-destroyed WebView proves nothing.
            focusAfterNavigation = view.hasFocus()
        }
    }

    private fun show(f: Fixture, bindFocus: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                if (bindFocus) BindOrbisHomeNavigationFocus(f.navigation)
                BackHandler(enabled = f.navigation.visible) { f.returnToChat() }
                LookaheadScope {
                    Box(Modifier.size(320.dp, if (f.compact.value) 280.dp else 480.dp)) {
                        // Keep the underlying list mounted, as RouteActivity does for chat.
                        Column(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("exit-fixture-list")) {
                                items(300, key = { "synthetic-$it" }) { index ->
                                    TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()
                                        .height(64.dp).animateItem()) {
                                        Text("Synthetic conversation $index")
                                    }
                                }
                            }
                            BasicTextField(f.draft.value, { f.draft.value = it },
                                Modifier.fillMaxWidth().height(48.dp).testTag("exit-fixture-editor"))
                        }
                        OrbisGardenLayers(f.navigation.visible, content = {
                            if (f.navigation.visible) {
                                AndroidView(modifier = Modifier.fillMaxSize()
                                    .background(Color.White).testTag("exit-fixture-web"), factory = { context ->
                                    WebView(context).apply {
                                        f.view = this
                                        f.created.incrementAndGet()
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        settings.javaScriptEnabled = true
                                        settings.blockNetworkLoads = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.domStorageEnabled = false
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldInterceptRequest(view: WebView,
                                                request: WebResourceRequest): WebResourceResponse? {
                                                // loadDataWithBaseURL(null, ...) is a local main
                                                // document. Rejecting that too yields chrome-error,
                                                // which can still receive outer Android focus.
                                                if (request.isForMainFrame && request.url.scheme in setOf("data", "about")) {
                                                    return null
                                                }
                                                return WebResourceResponse("text/plain", "UTF-8", 403, "Fixture only",
                                                    emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                            }
                                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                            override fun onPageFinished(view: WebView, url: String?) {
                                                // A finished error page is not the HTML fixture.
                                                view.evaluateJavascript("document.querySelector('input') !== null") { result ->
                                                    if (result == "true") f.loaded.incrementAndGet()
                                                }
                                            }
                                        }
                                        loadDataWithBaseURL(null, """
                                            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                                            <p>Synthetic garden. No personal content.</p><input value="Synthetic draft">
                                        """.trimIndent(), "text/html", "UTF-8", null)
                                    }
                                }, onRelease = { view ->
                                    f.released.incrementAndGet()
                                    // Match the real garden teardown; do not swallow layout exceptions.
                                    view.stopLoading()
                                    view.destroy()
                                })
                            }
                        }, overlay = {
                            if (f.navigation.visible) Column {
                                TextButton(onClick = f::returnToChat,
                                    modifier = Modifier.testTag("exit-fixture-top-return")) { Text("返回聊天") }
                                TextButton(onClick = f::returnToChat,
                                    modifier = Modifier.testTag("exit-fixture-star-return")) { Text("星星") }
                            }
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("exit-fixture-list").assertIsDisplayed()
    }

    private fun roundTrip(f: Fixture, index: Int, resizeOnReturn: Boolean) {
        // An imported/long history must exercise a real, scrolled lazy list rather
        // than a single static Box or only currently visible focus candidates.
        compose.onNodeWithTag("exit-fixture-list").performScrollToIndex(180 + index)
        val previousLoads = f.loaded.get()
        compose.runOnIdle { f.navigation.openHome() }
        compose.waitUntil(15_000) { f.loaded.get() > previousLoads }
        compose.onNodeWithTag("exit-fixture-web").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Fixture must acquire Android focus before testing detach", f.view.requestFocus())
            assertTrue("A non-focused WebView does not exercise the reported focus path", f.view.hasFocus())
        }
        compose.runOnIdle {
            if (resizeOnReturn) f.compact.value = !f.compact.value
        }
        when (index % 3) {
            0 -> compose.onNodeWithTag("exit-fixture-top-return").performClick()
            1 -> compose.onNodeWithTag("exit-fixture-star-return").performClick()
            else -> compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
        compose.waitForIdle()
        assertEquals("Focused AndroidView must be released BEFORE unmount", false, f.focusAfterNavigation)
        compose.onNodeWithTag("exit-fixture-web").assertDoesNotExist()
        compose.onNodeWithTag("exit-fixture-list").assertIsDisplayed().performScrollToIndex(index)
        compose.runOnIdle { assertEquals(index + 1, f.released.get()) }
    }

    @Test fun focusedGardenReturnKeepsScrolledChatListUsableAcrossRepeatedMounts() {
        val f = Fixture()
        show(f)
        repeat(8) { roundTrip(f, it, resizeOnReturn = false) }
        assertEquals(8, f.created.get())
        assertEquals(8, f.released.get())
    }

    @Test fun focusedGardenReturnDuringKeyboardLikeResizeKeepsChatListUsable() {
        val f = Fixture()
        show(f)
        repeat(8) { roundTrip(f, it, resizeOnReturn = true) }
        assertEquals(8, f.created.get())
        assertEquals(8, f.released.get())
    }

    @Test fun legacyTransitionLeavesAndroidFocusPresentBeforeUnmount() {
        val f = Fixture()
        show(f, bindFocus = false)
        compose.runOnIdle { f.navigation.openHome() }
        compose.waitUntil(15_000) { f.loaded.get() > 0 }
        compose.runOnIdle {
            assertTrue(f.view.requestFocus())
            f.returnToChat()
            assertEquals(true, f.focusAfterNavigation)
            // Evidence of old ordering only. Avoid intentionally crashing the test
            // process during teardown of this unguarded control fixture.
            f.view.clearFocus()
        }
        compose.waitForIdle()
    }

    @Test fun enteringGardenReleasesChatEditorWithoutLosingDraft() {
        val f = Fixture()
        show(f)
        compose.onNodeWithTag("exit-fixture-editor").performClick().performTextInput("synthetic retained draft")
        compose.runOnIdle { f.navigation.openHome() }
        compose.waitUntil(15_000) { f.loaded.get() > 0 }
        compose.onNodeWithTag("exit-fixture-editor").assertIsNotFocused()
        compose.runOnIdle { f.returnToChat() }
        compose.waitForIdle()
        assertEquals("synthetic retained draft", f.draft.value)
        compose.onNodeWithTag("exit-fixture-editor").assertIsDisplayed().performClick().performTextInput("!")
        assertEquals("synthetic retained draft!", f.draft.value)
    }

    @Test fun domInputFocusAndQueuedImeRequestAreReleasedBeforeReturning() {
        val f = Fixture()
        val domFocused = AtomicBoolean(false)
        val domProbe = AtomicReference("callback not received")
        show(f)
        compose.onNodeWithTag("exit-fixture-list").performScrollToIndex(190)
        compose.runOnIdle { f.navigation.openHome() }
        compose.waitUntil(15_000) { f.loaded.get() > 0 }
        compose.runOnIdle {
            assertTrue(f.view.requestFocus())
            f.view.evaluateJavascript("""
                (function() {
                    var input = document.querySelector('input');
                    if (input) {
                        input.focus();
                        input.value = 'synthetic HTML draft';
                    }
                    return {hasInput: !!input, focused: !!input && document.activeElement === input,
                        activeTag: document.activeElement && document.activeElement.tagName,
                        readyState: document.readyState, documentFocus: document.hasFocus(), url: location.href};
                })();
            """.trimIndent()) { result ->
                domProbe.set(result ?: "null callback")
                domFocused.set(runCatching { JSONObject(result ?: "{}").optBoolean("focused") }.getOrDefault(false))
                val ime = f.view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                // Exercise pending IME work, not only an outer WebView focus flag.
                // This requests the keyboard; visibility remains platform-dependent.
                ime.showSoftInput(f.view, InputMethodManager.SHOW_IMPLICIT)
                f.view.post {
                    f.compact.value = true
                    f.returnToChat()
                }
            }
        }
        compose.waitUntil(15_000) { f.focusAfterNavigation != null }
        compose.waitForIdle()
        assertTrue("The HTML input must really own DOM focus; probe=${domProbe.get()}", domFocused.get())
        assertEquals("Release Android focus before applying return and resize", false, f.focusAfterNavigation)
        compose.onNodeWithTag("exit-fixture-web").assertDoesNotExist()
        compose.onNodeWithTag("exit-fixture-list").assertIsDisplayed().performScrollToIndex(20)
        assertEquals(1, f.released.get())
    }
}
