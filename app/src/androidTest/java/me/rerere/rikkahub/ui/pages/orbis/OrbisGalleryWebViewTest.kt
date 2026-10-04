package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.gallery.GalleryRepository
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real renderer, only synthetic HTML and a fresh test-owned gallery. No app database/model/network. */
@RunWith(AndroidJUnit4::class)
class OrbisGalleryWebViewTest {
    @get:Rule val compose = createShellComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ownedRoots = mutableListOf<File>()

    @Before fun requireIsolatedApplication() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @After fun removeOnlySyntheticFiles() {
        val cache = instrumentation.targetContext.cacheDir.canonicalFile
        ownedRoots.forEach {
            check(it.canonicalFile.parentFile == cache && it.name.startsWith("gallery-viewport-fixture-"))
            it.deleteRecursively()
        }
    }

    @Test fun firstScriptReceivesPositiveViewportAndVhPercentAndAspectRatioRender() {
        assumeTrue(gallerySupportsSafeScripts())
        val view = showArtwork(PROBE)
        val initial = JSONObject(evaluate(view, "window.initial"))
        assertTrue(initial.getDouble("height") > 0)
        assertDimensions(initial)
        assertDimensions(JSONObject(evaluate(view, "window.measure()")))
    }

    @Test fun resizingTheSameArtworkKeepsScriptStateAndRecomputesViewportWithoutReload() {
        assumeTrue(gallerySupportsSafeScripts())
        val tall = mutableStateOf(false)
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(300.dp, if (tall.value) 430.dp else 240.dp)) {
                OrbisGalleryWebView("resize-fixture", PROBE, onFailure = { error("Synthetic renderer failed") },
                    onViewCreated = { holder.set(it) }, onLoaded = { loads.incrementAndGet() })
            }
        }
        compose.waitUntil(15_000) { loads.get() > 0 }
        val view = holder.get()
        val initialHeight = JSONObject(evaluate(view, "window.measure()")).getDouble("height")
        evaluate(view, "window.unsavedAnswer='keep-me'; void 0")
        compose.runOnIdle { tall.value = true }
        compose.waitForIdle()
        awaitScript(view, "window.innerHeight > ${initialHeight + 20}")
        assertSame(view, holder.get())
        assertEquals(1, loads.get())
        assertEquals("\"keep-me\"", evaluate(view, "window.unsavedAnswer"))
        assertDimensions(JSONObject(evaluate(view, "window.measure()")))
    }

    @Test fun detailFullscreenHidesChromeAndExitPreservesTheSameLiveArtworkAndStoredSource() {
        assertFullscreenPreservesArtwork(dark = false)
    }

    @Test fun darkDetailFullscreenKeepsTheSameUncoveredViewAndInMemoryAnswers() {
        assertFullscreenPreservesArtwork(dark = true)
    }

    private fun assertFullscreenPreservesArtwork(dark: Boolean) {
        assumeTrue(gallerySupportsSafeScripts())
        val root = File(instrumentation.targetContext.cacheDir.canonicalFile,
            "gallery-viewport-fixture-${UUID.randomUUID()}").also { ownedRoots += it }
        val repo = GalleryRepository(File(root, "orbis-gallery/${UUID.randomUUID()}"))
        val item = repo.save("合成全屏作品", "html", PROBE)
        val fullscreen = mutableStateOf(false)
        compose.setContent {
            OrbisVisualTheme(darkTheme = dark, colors = if (dark) OrbisPalette.Dark else OrbisPalette.Light) {
                Box(Modifier.fillMaxSize()) {
                    GalleryDetail(repo, item, emptyList(), false, onTask = {}, onReturn = {},
                        onDeleted = {}, onReload = {}, fullScreen = fullscreen.value,
                        onFullScreen = { fullscreen.value = it })
                }
            }
        }
        compose.waitUntil(15_000) { currentView() != null }
        val view = requireNotNull(currentView())
        awaitScript(view, "typeof window.measure === 'function'")
        awaitUncovered(view)
        compose.runOnIdle {
            val expected = (if (dark) OrbisPalette.Dark else OrbisPalette.Light).page.toArgb()
            assertEquals(expected, (view.background as ColorDrawable).color)
        }
        val originalHeight = JSONObject(evaluate(view, "window.measure()")).getDouble("height")
        evaluate(view, "window.unsavedAnswer='still-here'; void 0")
        compose.onNodeWithText("全屏阅读").performClick()
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.onNodeWithText("合成全屏作品").assertDoesNotExist()
        compose.onNodeWithText("收藏").assertDoesNotExist()
        awaitScript(view, "window.innerHeight > ${originalHeight + 20}")
        assertSame(view, currentView())
        compose.runOnIdle { assertFalse((view as OrbisGalleryNativeWebView).isArtworkCovered) }
        assertDimensions(JSONObject(evaluate(view, "window.measure()")))
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithText("合成全屏作品").assertIsDisplayed()
        assertSame(view, currentView())
        compose.runOnIdle { assertFalse((view as OrbisGalleryNativeWebView).isArtworkCovered) }
        assertEquals("\"still-here\"", evaluate(view, "window.unsavedAnswer"))
        assertEquals(PROBE, repo.body(item.id, item.current.revision))
    }

    @Test fun lightColdAndRepeatedOpenDrawHostColourBeforeTheFirstVisualAcknowledgement() {
        assertColdAndRepeatedOpen(dark = false)
    }

    @Test fun darkColdAndRepeatedOpenDrawHostColourBeforeTheFirstVisualAcknowledgement() {
        assertColdAndRepeatedOpen(dark = true)
    }

    private fun assertColdAndRepeatedOpen(dark: Boolean) {
        val opened = mutableStateOf(true)
        val hostColour = if (dark) Color(0xff171827) else Color(0xfffbf6ed)
        val created = mutableListOf<WebView>()
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme(surface = hostColour) else lightColorScheme(surface = hostColour)) {
                if (opened.value) Box(Modifier.size(300.dp, 400.dp)) {
                    OrbisGalleryWebView("repeated-static-fixture", "<title>Colour fixture</title><style>html,body{background:#32536c}</style><p>Offline artwork</p>",
                        scriptsEnabled = false, onFailure = { error("Synthetic renderer failed") },
                        onViewCreated = { view ->
                            holder.set(view)
                            created += view
                            // Before attaching/loading there is no viewport: drawing must not
                            // fill a caller's canvas (which can also contain native page chrome).
                            assertTrue((view as OrbisGalleryNativeWebView).isArtworkCovered)
                            assertEquals(hostColour.toArgb(), (view.background as ColorDrawable).color)
                            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                view.draw(Canvas(bitmap))
                                assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(4, 4))
                            } finally { bitmap.recycle() }
                        }, onLoaded = {
                            assertFalse((holder.get() as OrbisGalleryNativeWebView).isArtworkCovered)
                            loads.incrementAndGet()
                        })
                }
            }
        }
        compose.waitUntil(15_000) { loads.get() == 1 }
        val first = holder.get()
        compose.runOnIdle { opened.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue((first as OrbisGalleryNativeWebView).isArtworkCovered)
            opened.value = true
        }
        compose.waitUntil(15_000) { loads.get() == 2 }
        compose.runOnIdle {
            assertEquals(2, created.size)
            assertNotSame(first, holder.get())
            assertEquals("Colour fixture", holder.get().title)
            assertFalse((holder.get() as OrbisGalleryNativeWebView).isArtworkCovered)
        }
    }

    @Test fun changingHostThemeDoesNotReloadTheVisibleArtworkOrDiscardItsScriptState() {
        assumeTrue(gallerySupportsSafeScripts())
        val hostColour = mutableStateOf(Color(0xfffbf6ed))
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                OrbisGalleryWebView("theme-fixture", PROBE, backgroundColor = hostColour.value,
                    onFailure = { error("Synthetic renderer failed") }, onViewCreated = { holder.set(it) },
                    onLoaded = { loads.incrementAndGet() })
            }
        }
        compose.waitUntil(15_000) { loads.get() == 1 }
        val view = holder.get()
        evaluate(view, "window.unsavedAnswer='theme-kept'; void 0")
        compose.runOnIdle { hostColour.value = Color(0xff171827) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(view, holder.get())
            assertEquals(hostColour.value.toArgb(), (view.background as ColorDrawable).color)
            assertFalse((view as OrbisGalleryNativeWebView).isArtworkCovered)
            assertEquals(1, loads.get())
        }
        assertEquals("\"theme-kept\"", evaluate(view, "window.unsavedAnswer"))
    }

    @Test fun safeScriptsRetainNetworkStorageFileAndHostBridgeIsolation() {
        assumeTrue(gallerySupportsSafeScripts())
        val view = showArtwork(PROBE)
        compose.runOnIdle {
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.domStorageEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
        }
        assertEquals("true", evaluate(view,
            "(function(){try{localStorage.setItem('synthetic','1');return false;}catch(e){return true;}})()"))
        listOf("Android", "OrbisGame", "RTCPeerConnection", "WebSocket", "Worker").forEach {
            assertEquals("\"undefined\"", evaluate(view, "typeof window.$it"))
        }
        // The blocked fetch uses .invalid and is denied by both CSP and WebSettings before networking.
        evaluate(view, "window.blockedFetch=false;fetch('https://example.invalid/gallery-test').catch(()=>window.blockedFetch=true);void 0")
        awaitScript(view, "window.blockedFetch")
    }

    @Test fun staticFallbackStillLoadsHtmlWithScriptsDisabled() {
        val view = showArtwork("<title>Static fixture</title><p>Static artwork</p><script>document.title='unexpected'</script>",
            scriptsEnabled = false)
        compose.runOnIdle {
            assertFalse(view.settings.javaScriptEnabled)
            assertEquals("Static fixture", view.title)
            assertTrue(view.width > 0 && view.height > 0)
            assertFalse((view as OrbisGalleryNativeWebView).isArtworkCovered)
        }
    }

    private fun showArtwork(html: String, scriptsEnabled: Boolean = true): WebView {
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                OrbisGalleryWebView("synthetic-artwork", html, onFailure = { error("Synthetic renderer failed") },
                    scriptsEnabled = scriptsEnabled, onViewCreated = { holder.set(it) },
                    onLoaded = { loads.incrementAndGet() })
            }
        }
        compose.waitUntil(15_000) { loads.get() > 0 }
        return holder.get()
    }

    private fun currentView(): WebView? {
        var result: WebView? = null
        instrumentation.runOnMainSync { result = findView(compose.activity.window.decorView) }
        return result
    }

    private fun awaitUncovered(view: WebView) {
        compose.waitUntil(15_000) {
            var uncovered = false
            instrumentation.runOnMainSync { uncovered = !(view as OrbisGalleryNativeWebView).isArtworkCovered }
            uncovered
        }
    }

    private fun findView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun evaluate(view: WebView, script: String): String {
        val ready = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; ready.countDown() } }
        assertTrue("Synthetic JavaScript timed out", ready.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun awaitScript(view: WebView, script: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (evaluate(view, script) == "true") return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("Synthetic viewport/state did not become ready")
    }

    private fun assertDimensions(dimensions: JSONObject) {
        val height = dimensions.getDouble("height")
        assertTrue(height > 0)
        assertEquals(height * .1, dimensions.getDouble("vh"), 1.5)
        assertEquals(height, dimensions.getDouble("percent"), 1.5)
        assertEquals(90.0, dimensions.getDouble("ratio"), 1.5)
        assertTrue(dimensions.getDouble("width") in 1.0..600.0)
    }

    companion object {
        private val PROBE = """
            <!doctype html><html><head><style>
            #vh{height:10vh;background:green}#percent{height:100%}
            #ratio{width:180px;aspect-ratio:2/1;background:blue}
            </style></head><body><div id="vh"></div><div id="percent"></div><div id="ratio"></div>
            <script>window.measure=()=>({height:innerHeight,width:innerWidth,
              vh:document.getElementById('vh').getBoundingClientRect().height,
              percent:document.getElementById('percent').getBoundingClientRect().height,
              ratio:document.getElementById('ratio').getBoundingClientRect().height});
            window.initial=window.measure();</script></body></html>
        """.trimIndent()
    }
}
