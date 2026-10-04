package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic, offline pages only. Pixel checks include the native area outside the HTML viewport. */
@RunWith(AndroidJUnit4::class)
class OrbisGalleryViewportClipTest {
    @get:Rule val compose = createShellComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun requireIsolatedApplication() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @Test fun unlaidOutNativeViewCannotPaintTheSurroundingCanvas() {
        instrumentation.runOnMainSync {
            val view = nativeView()
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(GUARD)
                view.draw(Canvas(bitmap))
                assertEquals(GUARD, bitmap.getPixel(24, 24))
            } finally { bitmap.recycle(); view.releaseArtwork() }
        }
    }

    @Test fun firstFrameCoverIsClippedAndRestoresTheCallersCanvasClip() {
        instrumentation.runOnMainSync {
            val view = nativeView()
            val bitmap = Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)
            try {
                view.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 40, 30)
                assertTrue(view.isArtworkCovered)
                assertTrue(view.clipToOutline)
                assertSame(ViewOutlineProvider.BOUNDS, view.outlineProvider)
                bitmap.eraseColor(GUARD)
                val canvas = Canvas(bitmap)
                canvas.translate(20f, 20f)
                val clipBefore = canvas.clipBounds
                view.draw(canvas)
                assertEquals(clipBefore, canvas.clipBounds)
                assertEquals(HOST, bitmap.getPixel(21, 21))
                assertEquals(HOST, bitmap.getPixel(59, 49))
                listOf(19 to 21, 60 to 21, 21 to 19, 21 to 50).forEach { (x, y) ->
                    assertEquals("Native cover escaped its viewport", GUARD, bitmap.getPixel(x, y))
                }
            } finally { bitmap.recycle(); view.releaseArtwork() }
        }
    }

    @Test fun scrolledFirstFrameCoverFillsOnlyTheVisibleViewportAndRestoresCanvas() {
        instrumentation.runOnMainSync {
            val view = nativeView()
            val bitmap = Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)
            try {
                view.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 40, 30)
                view.scrollTo(17, 47)
                assertEquals(17, view.scrollX)
                assertEquals(47, view.scrollY)
                assertTrue(view.isArtworkCovered)
                bitmap.eraseColor(GUARD)
                val canvas = Canvas(bitmap)
                // View's parent translates to content coordinates before calling draw().
                canvas.translate(20f - view.scrollX, 20f - view.scrollY)
                val clipBefore = canvas.clipBounds
                view.draw(canvas)
                assertEquals(clipBefore, canvas.clipBounds)
                assertEquals("Scrolled cover lost its top left", HOST, bitmap.getPixel(21, 21))
                assertEquals("Scrolled cover lost its bottom right", HOST, bitmap.getPixel(59, 49))
                listOf(19 to 21, 60 to 21, 21 to 19, 21 to 50).forEach { (x, y) ->
                    assertEquals("Scrolled cover escaped its viewport", GUARD, bitmap.getPixel(x, y))
                }
            } finally { bitmap.recycle(); view.releaseArtwork() }
        }
    }

    @Test fun longHtmlRemainsVisibleThroughSeveralScreensAndAtTheEndAcrossFullscreenResize() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val fullscreen = mutableStateOf(false)
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            // API 35 enforces edge-to-edge for this test activity. Keep sampled guards
            // inside app content, rather than underneath the system navigation-bar scrim.
            Column(Modifier.fillMaxSize().background(Color(GUARD)).safeDrawingPadding()) {
                Spacer(Modifier.fillMaxWidth().height(if (fullscreen.value) 8.dp else 72.dp))
                val viewport = if (fullscreen.value) Modifier.weight(1f) else Modifier.height(220.dp)
                Box(Modifier.fillMaxWidth().then(viewport)) {
                    OrbisGalleryWebView("synthetic-long-scrolling-page", LONG_HTML,
                        backgroundColor = Color(HOST), scriptsEnabled = false,
                        onFailure = { error("Synthetic renderer failed") },
                        onViewCreated = { holder.set(it) }, onLoaded = { loads.incrementAndGet() })
                }
                Spacer(Modifier.fillMaxWidth().height(8.dp))
            }
        }
        compose.waitUntil(15_000) { loads.get() == 1 }
        val original = holder.get()
        listOf(false, true, false).forEach { full ->
            compose.runOnIdle { fullscreen.value = full }
            compose.waitForIdle()
            assertSame("Resizing must not reload a long artwork", original, holder.get())
            // Inspect the retained scroll position before resetting it. Resizing near the
            // footer may reveal either main content or footer, but never the blank host.
            awaitWebFrame(original)
            assertWindowGuards(original, setOf(PAGE, END))
            instrumentation.runOnMainSync { original.scrollTo(0, 0) }
            awaitWebFrame(original)
            assertWindowGuards(original)
            repeat(3) {
                instrumentation.runOnMainSync { original.scrollBy(0, original.height * 3 / 4) }
                awaitWebFrame(original)
                assertWindowGuards(original)
            }
            instrumentation.runOnMainSync {
                assertTrue("Test must scroll beyond the first viewport", original.scrollY > original.height)
                assertTrue("Long page must have an actual end", original.pageDown(true))
            }
            // pageDown animates; a visual-state callback alone is not an end-of-scroll signal.
            compose.waitUntil(10_000) {
                var atEnd = false
                instrumentation.runOnMainSync { atEnd = !original.canScrollVertically(1) }
                atEnd
            }
            awaitWebFrame(original)
            assertWindowGuards(original, setOf(END))
            assertEquals("Ordinary/fullscreen changes must keep one document", 1, loads.get())
        }
    }

    @Test fun strongHtmlBackgroundStaysBelowNativeHeaderAcrossResizeAndReopen() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val opened = mutableStateOf(true)
        val large = mutableStateOf(false)
        val holder = AtomicReference<WebView>()
        val loads = AtomicInteger()
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color(GUARD))) {
                Spacer(Modifier.fillMaxWidth().height(72.dp))
                if (opened.value) Box(Modifier.fillMaxWidth().height(if (large.value) 340.dp else 220.dp)) {
                    OrbisGalleryWebView("synthetic-clipped-background", BACKGROUND_HTML,
                        backgroundColor = Color(HOST), scriptsEnabled = false,
                        onFailure = { error("Synthetic renderer failed") },
                        onViewCreated = { holder.set(it) }, onLoaded = { loads.incrementAndGet() })
                }
            }
        }
        compose.waitUntil(15_000) { loads.get() == 1 }
        val original = holder.get()
        assertWindowGuards(holder.get())
        compose.runOnIdle { large.value = true }
        compose.waitForIdle()
        assertSame(original, holder.get())
        assertWindowGuards(holder.get())
        assertEquals(1, loads.get())
        compose.runOnIdle { opened.value = false }
        compose.waitForIdle()
        compose.runOnIdle { opened.value = true }
        compose.waitUntil(15_000) { loads.get() == 2 }
        assertNotSame(original, holder.get())
        assertWindowGuards(holder.get())
    }

    private fun nativeView() = OrbisGalleryNativeWebView(instrumentation.targetContext,
        OrbisGalleryHtmlPolicy(), BACKGROUND_HTML, false, HOST,
        onFailure = { error("Synthetic renderer failed") }, onLoaded = {})

    private fun awaitWebFrame(view: WebView) {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.postVisualStateCallback(100, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.countDown() }
            })
        }
        assertTrue("Scrolled Chromium frame was not ready", ready.await(5, TimeUnit.SECONDS))
    }

    private fun assertWindowGuards(view: WebView, allowedPageColors: Set<Int> = setOf(PAGE)) {
        // Wait for an actual GPU submission, not a fixed delay or only onPageFinished. The
        // visual-state callback can run just before the invalidated native view is drawn.
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue(view.isHardwareAccelerated)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("Native frame was not committed", committed.await(5, TimeUnit.SECONDS))
        val completed = CountDownLatch(1)
        val result = AtomicInteger(-1)
        lateinit var bitmap: Bitmap
        val position = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            assertTrue(view.clipToOutline)
            assertSame(ViewOutlineProvider.BOUNDS, view.outlineProvider)
            view.getLocationInWindow(position)
            width = view.width
            height = view.height
            val window = compose.activity.window
            bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result.set(it); completed.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue("Window copy did not complete", completed.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result.get())
            val x = position[0] + width / 2
            assertTrue(position[1] > 4 && position[1] + height + 4 < bitmap.height)
            assertEquals("HTML painted over the native header", GUARD, bitmap.getPixel(x, position[1] - 4))
            assertEquals("HTML painted below its native viewport", GUARD, bitmap.getPixel(x, position[1] + height + 4))
            listOf(0.1, 0.5, 0.9).forEach { fraction ->
                val pixel = bitmap.getPixel(x, position[1] + (height * fraction).toInt())
                assertTrue("HTML lost visible content at viewport fraction $fraction: $pixel", pixel in allowedPageColors)
            }
        } finally { bitmap.recycle() }
    }

    companion object {
        private val GUARD = AndroidColor.rgb(19, 76, 54)
        private val HOST = AndroidColor.rgb(30, 31, 48)
        private val PAGE = AndroidColor.rgb(233, 225, 205)
        private val END = AndroidColor.rgb(44, 115, 104)
        private const val LONG_HTML = """<!doctype html><html><head><style>
            html,body{margin:0;background:rgb(233,225,205)}
            main{height:6000px;background:rgb(233,225,205)}
            footer{height:100vh;background:rgb(44,115,104)}
            </style></head><body><main>Long offline fixture</main><footer>END OF ARTWORK</footer></body></html>"""
        private const val BACKGROUND_HTML = """<!doctype html><html><head><style>
            html,body{margin:0;background:rgb(233,225,205)}
            .oversized{position:fixed;left:-1000px;top:-1000px;width:10000px;height:10000px;background:rgb(233,225,205)}
            </style></head><body><div class="oversized"></div></body></html>"""
    }
}
