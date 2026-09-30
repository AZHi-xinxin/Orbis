package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Canvas
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner

/** Only a fresh in-memory document; no stores, Koin, models, credentials or real URLs. */
@RunWith(AndroidJUnit4::class)
class OrbisGardenLayerDeviceTest {
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
        val open = mutableStateOf(false)
        val compact = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val created = AtomicInteger()
        val released = AtomicInteger()
        val loaded = AtomicInteger()
        val drawCount = AtomicInteger()
        lateinit var view: WebView
    }

    private fun show(f: Fixture) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, if (f.compact.value) 280.dp else 480.dp)
                    .testTag("garden-layer-fixture")) {
                    OrbisGardenLayers(visible = true, content = {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                            object : WebView(context) {
                                override fun onDraw(canvas: Canvas) {
                                    f.drawCount.incrementAndGet()
                                    super.onDraw(canvas)
                                }
                            }.apply {
                                f.view = this
                                f.created.incrementAndGet()
                                settings.javaScriptEnabled = true
                                settings.blockNetworkLoads = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                                        WebResourceResponse("text/plain", "UTF-8", 403, "Fixture only", emptyMap(),
                                            ByteArrayInputStream(ByteArray(0)))
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                    override fun onPageFinished(view: WebView, url: String?) { f.loaded.incrementAndGet() }
                                }
                                loadDataWithBaseURL(null, """
                                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                                    <style>html,body{margin:0;background:#b5d7c2}#book{height:3000px;background:linear-gradient(#b5d7c2,#977aa9)}</style>
                                    <div id="book">Synthetic book page</div>
                                    <script>window.fixture={chapter:7,draft:'synthetic unsent text',turn:3};</script>
                                """.trimIndent(), "text/html", "UTF-8", null)
                            }
                        }, onRelease = { view ->
                            f.released.incrementAndGet()
                            view.stopLoading(); view.destroy()
                        })
                    }, overlay = {
                        if (f.open.value) Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.9f)
                                .background(if (f.dark.value) Color(0xFF202431) else Color(0xFFF8F2E9))
                                .testTag("garden-fixture-drawer"))
                        }
                    })
                }
            }
        }
        compose.waitUntil(15_000) { f.loaded.get() == 1 }
    }

    private fun js(f: Fixture, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { f.view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("Synthetic JavaScript timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun repeatedDrawerOpenCloseDoesNotReloadOrLoseBookAndDraftState() {
        val f = Fixture(); show(f)
        js(f, "scrollTo(0,240); document.querySelector('#book').dataset.marker='kept'; void 0")
        val before = js(f, "JSON.stringify({fixture:fixture,scroll:scrollY,marker:document.querySelector('#book').dataset.marker})")
        repeat(8) { index ->
            compose.runOnIdle { f.dark.value = index % 2 == 0; f.open.value = true }
            compose.onNodeWithTag("garden-fixture-drawer").assertIsDisplayed()
            compose.runOnIdle { f.open.value = false }
            compose.onNodeWithTag("garden-fixture-drawer").assertDoesNotExist()
        }
        assertEquals(before, js(f, "JSON.stringify({fixture:fixture,scroll:scrollY,marker:document.querySelector('#book').dataset.marker})"))
        assertEquals(1, f.created.get()); assertEquals(1, f.loaded.get()); assertEquals(0, f.released.get())
    }

    @Test fun keyboardLikeResizeKeepsTheSameDocumentWhileDrawerIsOpen() {
        val f = Fixture(); show(f)
        val before = js(f, "JSON.stringify(fixture)")
        repeat(4) {
            compose.runOnIdle { f.open.value = true; f.compact.value = true }
            compose.onNodeWithTag("garden-fixture-drawer").assertIsDisplayed()
            compose.runOnIdle { f.compact.value = false; f.open.value = false }
        }
        assertEquals(before, js(f, "JSON.stringify(fixture)"))
        assertEquals(1, f.created.get()); assertEquals(1, f.loaded.get()); assertEquals(0, f.released.get())
    }

    @Test fun closingDrawerDoesNotKeepAStaticWebPageInADrawLoop() {
        val f = Fixture(); show(f)
        compose.runOnIdle { f.open.value = true }
        compose.onNodeWithTag("garden-fixture-drawer").assertIsDisplayed()
        compose.runOnIdle { f.open.value = false }
        compose.waitForIdle()
        // Let initial WebView uploads settle. This is a generous loop guard, not an FPS benchmark.
        Thread.sleep(500)
        val before = f.drawCount.get()
        assertTrue("The fixture must actually draw before measuring idle redraws", before > 0)
        Thread.sleep(750)
        val extraDraws = f.drawCount.get() - before
        assertTrue("Static page kept drawing after closing: $extraDraws", extraDraws <= 12)
        assertEquals(1, f.loaded.get()); assertEquals(0, f.released.get())
        assertEquals("true", js(f, "document.visibilityState==='visible' && fixture.chapter===7"))
    }
}
