package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Isolated, attached WebView geometry only. Never loads the downloaded site, its scripts, login,
 * storage or APIs. The fixture contains only public modal CSS and synthetic text. The shell rule
 * requires the isolated Application/runner. Run explicitly, never while the user is using a phone.
 * Log values are numbers/booleans; profile 0 keeps viewport defaults, profile 1 enables both flags.
 */
@RunWith(AndroidJUnit4::class)
class OrbisCloudModalGeometryTest {
    @get:Rule val compose = createShellComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var mounted by mutableStateOf(true)
    private var variant by mutableIntStateOf(0)
    private val currentView = AtomicReference<MeasuredWebView?>()
    private val unexpectedRequests = AtomicInteger(0)

    @After fun releaseOnlyOwnedFixture() {
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
    }

    @Test fun compareDefaultFactoryAndMatchParentBeforeChangingViewportSettings() {
        compose.setContent {
            if (mounted) Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                key(variant) {
                    // This is the production Column + weighted AndroidView measurement contract.
                    AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                        createView(context, profile = variant / 2, matchParent = variant % 2 == 1)
                    }, onRelease = { view ->
                        if (currentView.get() === view) currentView.set(null)
                        view.stopLoading()
                        view.destroy()
                    })
                }
            }
        }

        val samples = mutableListOf<JSONObject>()
        // The pre-fix factory comes first. Collect every control before asserting geometry.
        for (nextVariant in 0..3) {
            if (nextVariant != 0) compose.runOnIdle {
                variant = nextVariant
            }
            compose.waitUntil(15_000) {
                currentView.get()?.let { it.variantId == nextVariant && it.pageLoaded.get() } == true
            }
            val view = checkNotNull(currentView.get())
            for (longContent in listOf(false, true)) for (scrolled in listOf(false, true)) {
                evaluate(view, "window.prepareFixture($longContent,$scrolled); void 0")
                waitForModal(view)
                val sample = measure(view, nextVariant / 2, nextVariant % 2 == 1, longContent, scrolled)
                samples += sample
                Log.i("OrbisModalGeometry", sample.toString())
            }
        }

        assertTrue("Unexpected synthetic subresource count=${unexpectedRequests.get()}", unexpectedRequests.get() == 0)
        assertTrue("Every sample must come from an attached, measured fixture",
            samples.all { it.getBoolean("attached") && it.getInt("visibleHeightPx") > 0 && it.getDouble("sheetHeight") > 0 })
        // The pre-fix device reduced both short and long sheets to their 56px padding despite
        // exact native dimensions/innerHeight. Keep those controls in the numeric evidence, but
        // do not fail a future WebView release merely because it fixes WRAP_CONTENT upstream.
        // Production factories and this control call the same narrow layout helper.
        val controls = samples.filter { it.getBoolean("matchParent") }
        assertTrue("MATCH_PARENT modal geometry failed; numeric samples=${controls}", controls.all {
            it.getBoolean("sheetInsideActualViewport") && it.getBoolean("overlayMatchesActualViewport") &&
                it.getBoolean("nativeViewFullyVisible") && it.getBoolean("innerHeightMatchesNative") &&
                it.getInt("layoutWidth") == ViewGroup.LayoutParams.MATCH_PARENT &&
                it.getInt("layoutHeight") == ViewGroup.LayoutParams.MATCH_PARENT &&
                abs(it.getDouble("bodyHeight") - it.getDouble("actualVisibleCssHeight")) <= 3 &&
                it.getBoolean("sheetComputedMaxHeightFinite") &&
                abs(it.getDouble("sheetComputedMaxHeight") - .85 * it.getDouble("actualVisibleCssHeight")) <= 3
        })
        assertTrue("Long sheets must recover their actual 85vh height, not merely remain inside the viewport",
            controls.filter { it.getBoolean("longContent") }.all {
                abs(it.getDouble("sheetHeight") - .85 * it.getDouble("actualVisibleCssHeight")) <= 3
            })
        assertTrue("Short sheets must expose content beyond their padding", controls.filter {
            !it.getBoolean("longContent")
        }.all {
            it.getDouble("sheetHeight") > it.getDouble("sheetPaddingHeight") + 32 && !it.getBoolean("sheetCanScroll")
        })
        assertTrue("Long MATCH_PARENT content must scroll inside the modal", controls.filter {
            it.getBoolean("longContent")
        }.all { it.getBoolean("sheetCanScroll") })
        assertTrue("Long-page MATCH_PARENT scroll scenario must actually move before opening", controls.filter {
            it.getBoolean("scrolled")
        }.all { it.getDouble("scrollBeforeOpen") > 100 })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(context: Context, profile: Int, matchParent: Boolean): MeasuredWebView =
        MeasuredWebView(context, profile * 2 + if (matchParent) 1 else 0).apply {
            if (matchParent) configureOrbisHomeLayout()
            // Otherwise leave layoutParams unset to reproduce the pre-fix factories.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true // Fixture-only extra guard; every URL is intercepted.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (profile == 1) {
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
            }
            webChromeClient = WebChromeClient()
            val policy = checkNotNull(OrbisCloudWebPolicy.from(FIXTURE_URL))
            webViewClient = object : OrbisCloudWebViewClient(policy, onLoading = { if (!it) pageLoaded.set(true) }) {
                override fun responseForAllowedRequest(request: WebResourceRequest): WebResourceResponse {
                    val isFixture = request.isForMainFrame && request.method == "GET" && request.url.toString() == FIXTURE_URL
                    if (!isFixture) unexpectedRequests.incrementAndGet()
                    return WebResourceResponse(if (isFixture) "text/html" else "text/plain", "UTF-8",
                        if (isFixture) 200 else 403, if (isFixture) "OK" else "Blocked",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream((if (isFixture) FIXTURE_HTML else "").toByteArray(Charsets.UTF_8)))
                }
            }
            currentView.set(this)
            loadUrl(FIXTURE_URL)
        }

    private fun evaluate(view: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference("")
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) { result.set(it); completed.countDown() }
        }
        assertTrue("Synthetic geometry script timed out", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun waitForModal(view: WebView) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evaluate(view, "window.fixtureReady === true && " +
                    "getComputedStyle(document.getElementById('modalOverlay')).opacity === '1' && " +
                    "Math.abs(new DOMMatrix(getComputedStyle(document.getElementById('modalSheet')).transform).m42) < 0.1") == "true") return
            Thread.sleep(25)
        }
        throw AssertionError("Synthetic modal did not settle within its transition deadline")
    }

    @Suppress("DEPRECATION")
    private fun measure(view: MeasuredWebView, profile: Int, matchParent: Boolean,
        longContent: Boolean, scrolled: Boolean): JSONObject {
        val json = JSONTokener(evaluate(view, "JSON.stringify(window.measureFixture())")).nextValue() as String
        val result = JSONObject(json)
        instrumentation.runOnMainSync {
            val visible = Rect()
            val visibleNow = view.getGlobalVisibleRect(visible)
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val scale = view.scale.toDouble().takeIf { it > 0 } ?: view.resources.displayMetrics.density.toDouble()
            val actualCssHeight = visible.height() / scale
            val actualCssTop = result.getDouble("visualOffsetTop")
            val sheetTop = result.getDouble("sheetTop")
            val sheetBottom = result.getDouble("sheetBottom")
            val visibleSheet = max(0.0, min(sheetBottom, actualCssTop + actualCssHeight) - max(sheetTop, actualCssTop))
            result.put("profile", profile).put("matchParent", matchParent)
                .put("longContent", longContent).put("scrolled", scrolled)
                .put("attached", view.isAttachedToWindow).put("visible", visibleNow)
                .put("nativeWidthPx", view.width).put("nativeHeightPx", view.height)
                .put("measuredHeightPx", view.measuredHeight).put("rootHeightPx", view.rootView.height)
                .put("windowX", location[0]).put("windowY", location[1])
                .put("visibleTopPx", visible.top).put("visibleBottomPx", visible.bottom)
                .put("visibleWidthPx", visible.width()).put("visibleHeightPx", visible.height())
                .put("layoutWidth", view.layoutParams?.width ?: 0).put("layoutHeight", view.layoutParams?.height ?: 0)
                .put("widthSpecMode", View.MeasureSpec.getMode(view.lastWidthSpec))
                .put("heightSpecMode", View.MeasureSpec.getMode(view.lastHeightSpec))
                .put("widthSpecSize", View.MeasureSpec.getSize(view.lastWidthSpec))
                .put("heightSpecSize", View.MeasureSpec.getSize(view.lastHeightSpec))
                .put("measureCount", view.measureCount)
                .put("wideViewport", view.settings.useWideViewPort).put("overviewMode", view.settings.loadWithOverviewMode)
                .put("nativeScale", scale).put("density", view.resources.displayMetrics.density.toDouble())
                .put("actualVisibleCssHeight", actualCssHeight).put("actualVisibleSheetHeight", visibleSheet)
                .put("nativeScrollRangePx", view.verticalScrollRange()).put("nativeScrollY", view.scrollY)
                .put("nativeViewFullyVisible", abs(view.height - visible.height()) <= 3)
                .put("innerHeightMatchesNative", abs(result.getDouble("innerHeight") - view.height / scale) <= 3)
                .put("overlayMatchesActualViewport", abs(result.getDouble("overlayHeight") - actualCssHeight) <= 3)
                .put("sheetInsideActualViewport", visibleSheet >= result.getDouble("sheetHeight") - 3)
        }
        return result
    }

    private class MeasuredWebView(context: Context, val variantId: Int) : WebView(context) {
        val pageLoaded = AtomicBoolean(false)
        var lastWidthSpec = 0
        var lastHeightSpec = 0
        var measureCount = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            lastWidthSpec = widthMeasureSpec
            lastHeightSpec = heightMeasureSpec
            measureCount++
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        fun verticalScrollRange(): Int = computeVerticalScrollRange()
    }

    private companion object {
        const val FIXTURE_URL = "https://orbis-geometry-fixture.example.org/"
        // Public page snapshot 2026-09-24: modal rules 1014-1052, root rules 107-120.
        // Geometry-affecting declarations are retained; no source HTML, data or credentials are copied.
        val FIXTURE_HTML = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'; font-src 'none'; frame-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'">
            <style>
              :root { --surface:rgba(255,255,255,.6); --font-body:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif; --font-heading:"Georgia","Noto Serif SC","Source Han Serif SC",serif; --text-primary:#1A1A1A; --text-secondary:#7A7A7A; --gray-100:#EDE8E3; --gray-200:#E0DDDA; --gray-800:#2C2C2C; --radius-xl:28px; --space-lg:24px; --space-xl:32px; --transition:.25s cubic-bezier(.4,0,.2,1); }
              *,*::before,*::after { box-sizing:border-box; margin:0; padding:0; }
              html, body { height:100%; }
              body { max-width:100vw; font-family:var(--font-body); font-size:16px; line-height:1.6; color:var(--text-primary); background:transparent; -webkit-font-smoothing:antialiased; }
              .modal-sheet { backdrop-filter:blur(16px); -webkit-backdrop-filter:blur(16px); }
              .modal-overlay { position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(44,44,44,.5); display:flex; align-items:flex-end; justify-content:center; z-index:1000; opacity:0; pointer-events:none; transition:opacity .3s ease; }
              .modal-overlay--open { opacity:1; pointer-events:auto; }
              .modal-sheet { background:var(--surface); width:100%; max-width:480px; max-height:85vh; border-radius:var(--radius-xl) var(--radius-xl) 0 0; padding:var(--space-lg) var(--space-xl) var(--space-xl); box-shadow:0 -4px 30px rgba(44,44,44,.15); transform:translateY(100%); transition:transform .35s cubic-bezier(.32,.72,0,1); overflow-y:auto; overflow-x:hidden; box-sizing:border-box; }
              .modal-overlay--open .modal-sheet { transform:translateY(0); }
              .modal-handle { width:36px; height:4px; border-radius:2px; background:var(--gray-200); margin:0 auto var(--space-lg); }
              .modal-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:var(--space-lg); }
              .modal-title { font-family:var(--font-heading); font-size:1.1rem; font-weight:700; color:var(--gray-800); }
              .modal-close { width:36px; height:36px; display:flex; align-items:center; justify-content:center; border-radius:50%; border:none; background:var(--gray-100); color:var(--text-secondary); cursor:pointer; font-size:1.1rem; transition:all var(--transition); }
            </style></head><body><div id="app">
              <div id="fixturePage" style="height:2600px;padding:32px">Synthetic geometry fixture</div>
              <div class="modal-overlay" id="modalOverlay"><div class="modal-sheet" id="modalSheet">
                <div class="modal-handle"></div><div class="modal-header"><span class="modal-title" id="modalTitle"></span><button class="modal-close" id="modalClose">X</button></div><div id="modalBody"></div>
              </div></div>
            </div><script>
              const overlay=document.getElementById('modalOverlay'), mt=document.getElementById('modalTitle'), mb=document.getElementById('modalBody');
              let onClose=null;
              function openModal(t,h,oc) { mt.textContent=t; mb.innerHTML=h; overlay.classList.add('modal-overlay--open'); document.body.style.overflow='hidden'; onClose=oc||null; }
              function closeModal() { overlay.classList.remove('modal-overlay--open'); document.body.style.overflow=''; if(onClose){onClose();onClose=null;} }
              window.prepareFixture=function(longContent,scrolled) {
                window.fixtureReady=false; closeModal(); document.getElementById('modalSheet').scrollTop=0;
                window.scrollTo(0,scrolled?100000:0);
                requestAnimationFrame(()=>requestAnimationFrame(()=>{
                  window.fixtureScrollBefore=window.scrollY; window.fixtureInnerHeightBefore=window.innerHeight;
                  openModal('Synthetic modal',Array(longContent?120:1).fill('<p>Synthetic row with no personal data.</p>').join(''));
                  window.fixtureReady=true;
                }));
              };
              window.measureFixture=function() {
                const s=document.getElementById('modalSheet'), sr=s.getBoundingClientRect(), o=overlay.getBoundingClientRect(), v=window.visualViewport;
                const computed=getComputedStyle(s), computedMax=parseFloat(computed.maxHeight), padding=parseFloat(computed.paddingTop)+parseFloat(computed.paddingBottom);
                const old=s.scrollTop; s.scrollTop=40; const canScroll=s.scrollTop>0; s.scrollTop=old;
                return { innerWidth:innerWidth,innerHeight:innerHeight,clientWidth:document.documentElement.clientWidth,clientHeight:document.documentElement.clientHeight,
                  documentScrollHeight:document.documentElement.scrollHeight,bodyHeight:document.body.getBoundingClientRect().height,appHeight:document.getElementById('app').getBoundingClientRect().height,
                  visualWidth:v?v.width:innerWidth,visualHeight:v?v.height:innerHeight,visualOffsetTop:v?v.offsetTop:0,visualScale:v?v.scale:1,
                  scrollY:scrollY,scrollBeforeOpen:window.fixtureScrollBefore,innerHeightBeforeOpen:window.fixtureInnerHeightBefore,
                  overlayTop:o.top,overlayBottom:o.bottom,overlayHeight:o.height,sheetTop:sr.top,sheetBottom:sr.bottom,sheetWidth:sr.width,sheetHeight:sr.height,
                  sheetClientHeight:s.clientHeight,sheetScrollHeight:s.scrollHeight,sheetCanScroll:canScroll,
                  sheetComputedMaxHeight:Number.isFinite(computedMax)?computedMax:0,sheetComputedMaxHeightFinite:Number.isFinite(computedMax),sheetPaddingHeight:padding,
                  translateY:new DOMMatrix(getComputedStyle(s).transform).m42,open:overlay.classList.contains('modal-overlay--open') };
              };
            </script></body></html>
        """.trimIndent()
    }
}
