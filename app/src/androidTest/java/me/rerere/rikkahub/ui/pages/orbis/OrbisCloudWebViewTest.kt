package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All HTTP(S) responses are synthetic and in-process. Never reaches the user's cloud or model. */
@RunWith(AndroidJUnit4::class)
class OrbisCloudWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val views = mutableListOf<WebView>()
    private val host = "https://orbis-fixture.example.org"
    private val policy = OrbisCloudWebPolicy.from("$host/")!!
    private val requests = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<String>()
    private val storageKey = "orbis-stage12-fixture-${UUID.randomUUID()}"

    @Before fun requireIsolatedApplication() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @After fun disposeSyntheticViews() {
        views.lastOrNull()?.let { view -> runCatching { evaluate(view, "localStorage.removeItem('$storageKey')") } }
        instrumentation.runOnMainSync {
            views.forEach { runCatching { it.stopLoading(); it.destroy() } }
            views.clear()
        }
    }

    private fun response(body: String, mime: String = "application/json", status: Int = 200) =
        WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Synthetic error",
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to host),
            ByteArrayInputStream(body.toByteArray()))

    private fun createView(mainStatus: Int = 200): WebView {
        val ready = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : OrbisCloudWebViewClient(policy,
                    onLoading = { if (!it) ready.countDown() },
                    onFailure = { failures.add(it); ready.countDown() }) {
                    override fun responseForAllowedRequest(request: WebResourceRequest): WebResourceResponse {
                        requests.add("${request.method} ${request.url.path}")
                        return when (request.url.path) {
                            "/" -> response("""
                                <!doctype html><html><body>
                                <div id="orbisLoginGate">Synthetic original login</div>
                                <button id="save">Save synthetic record</button>
                                <script>
                                  window.result='idle';
                                  document.getElementById('save').onclick=async()=>{
                                    const r=await fetch('/api/record',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"value":"synthetic"}'});
                                    window.result=r.ok?'saved':'failed';
                                  };
                                  window.readRecord=async()=>{
                                    const r=await fetch('/api/record'); window.result=await r.text();
                                  };
                                </script></body></html>
                            """.trimIndent(), "text/html", mainStatus)
                            "/api/record" -> response(if (request.method == "POST") "saved" else "synthetic-read")
                            else -> response("{}")
                        }
                    }
                }
                // Every allowed URL is handled by the above in-memory response provider.
                loadUrl("$host/")
            }
            views.add(view)
        }
        assertTrue("Synthetic page did not load", ready.await(15, TimeUnit.SECONDS))
        return view
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("Synthetic JS evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun awaitResult(view: WebView, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            if (evaluate(view, "window.result") == "\"$expected\"") return
            Thread.sleep(30)
        } while (System.nanoTime() < deadline)
        fail("Synthetic result was not $expected")
    }

    @Test fun originalLoginStaysAndReadsThenExplicitSyntheticSaveWork() {
        val view = createView()
        assertEquals("true", evaluate(view, "!!document.getElementById('orbisLoginGate')"))
        assertEquals("\"undefined\"", evaluate(view, "typeof window.Android"))
        assertFalse(requests.any { it.startsWith("POST") })
        evaluate(view, "window.readRecord(); void 0")
        awaitResult(view, "synthetic-read")
        assertTrue(requests.contains("GET /api/record"))
        assertFalse(requests.any { it.startsWith("POST") })
        evaluate(view, "document.getElementById('save').click(); void 0")
        awaitResult(view, "saved")
        assertEquals(1, requests.count { it == "POST /api/record" })
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test fun siteStorageSurvivesWebViewDisposalWithoutNativeDataAccess() {
        val first = createView()
        evaluate(first, "localStorage.setItem('$storageKey','synthetic-kept')")
        instrumentation.runOnMainSync { first.stopLoading(); first.destroy(); views.remove(first) }
        val second = createView()
        assertEquals("\"synthetic-kept\"", evaluate(second, "localStorage.getItem('$storageKey')"))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun mainHttpFailureProducesNativeErrorCallback() {
        createView(mainStatus = 503)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(30)
        assertTrue(failures.toString(), failures.any { it.contains("HTTP 503") })
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun clientBlocksForeignMainFramesAndNativeResourcesBeforeFixtureHandler() {
        val view = createView()
        val passed = CopyOnWriteArrayList<String>()
        val client = object : OrbisCloudWebViewClient(policy) {
            override fun responseForAllowedRequest(request: WebResourceRequest): WebResourceResponse {
                passed.add(request.url.toString())
                return response("{}")
            }
        }
        listOf("https://evil.example.org/", OrbisLocalPolicy.HOME, "file:///private", "https://127.0.0.1/")
            .forEach { target ->
                assertEquals(403, client.shouldInterceptRequest(view, Request(target, true))?.statusCode)
                instrumentation.runOnMainSync {
                    assertTrue(client.shouldOverrideUrlLoading(view, Request(target, true)))
                }
            }
        assertTrue(passed.isEmpty())
        assertEquals(200, client.shouldInterceptRequest(view, Request("https://api.example.org/record", false))?.statusCode)
        assertEquals(1, passed.size)
    }

    @Test fun nativeChatNavigationRejectsWrongOriginsFramesSyntheticRequestsAndRedirects() {
        val view = createView()
        var chatCalls = 0
        val navigation = OrbisCloudChatNavigation(policy)
        val client = OrbisCloudWebViewClient(policy, onOpenChat = { chatCalls++ }, chatNavigation = navigation)
        val otherOriginClient = OrbisCloudWebViewClient(
            OrbisCloudWebPolicy.from("https://another.example.org/")!!,
            onOpenChat = { chatCalls++ },
            chatNavigation = navigation,
        )
        val target = navigation.target
        instrumentation.runOnMainSync {
            listOf(Request(target, false), Request(target, true, gesture = false),
                Request(target, true, redirect = true), Request(target, true, method = "POST"),
                Request("$target&tool=run", true), Request(OrbisLocalPolicy.OPEN_CHAT, true),
                Request(OrbisCloudChatNavigation(policy).target, true)).forEach {
                assertTrue(client.shouldOverrideUrlLoading(view, it))
            }
            assertTrue(otherOriginClient.shouldOverrideUrlLoading(view, Request(target, true)))
            assertEquals(0, chatCalls)
            assertTrue(client.shouldOverrideUrlLoading(view, Request(target, true)))
            assertEquals(1, chatCalls)
        }
        assertEquals(403, client.shouldInterceptRequest(view, Request(target, false))?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(view, Request(target, true))?.statusCode)
    }

    @Test fun navigationScriptDoesNotInstallForADifferentMainOrigin() {
        val view = createView()
        evaluate(view, "delete window.__orbisNativeChatNavigation")
        val otherPolicy = OrbisCloudWebPolicy.from("https://another.example.org/")!!
        evaluate(view, OrbisCloudChatNavigation(otherPolicy).script)
        assertEquals("\"undefined\"", evaluate(view, "typeof window.__orbisNativeChatNavigation"))
        assertEquals("\"undefined\"", evaluate(view, "typeof window.Android"))
    }

    @Test fun calendarGradientMovesToNativeBackdropWithoutChangingViewportOrControls() {
        val view = createView()
        evaluate(view, """
            (() => {
              const style=document.createElement('style');
              style.textContent='body::before { content:""; position:fixed; inset:0; background:linear-gradient(to bottom,#FDF8F3 0%,#c98a76 35%,#10132a 100%); }';
              document.head.appendChild(style);
              window.beforeHeight=innerHeight;
              window.beforeButtonTop=document.getElementById('save').getBoundingClientRect().top;
            })();
        """.trimIndent())
        evaluate(view, OrbisCloudChrome(policy).script)
        evaluate(view, OrbisCloudChrome(policy).script)
        assertEquals("\"none\"", evaluate(view, "getComputedStyle(document.body,'::before').backgroundImage"))
        assertEquals("true", evaluate(view, "innerHeight===window.beforeHeight && document.getElementById('save').getBoundingClientRect().top===window.beforeButtonTop"))
        assertEquals("1", evaluate(view, "document.querySelectorAll('#orbis-native-home-chrome').length"))
        assertEquals("\"undefined\"", evaluate(view, "typeof window.Android"))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun calendarChromeDoesNotReplaceUnknownSiteBackgroundOrOtherOrigin() {
        val view = createView()
        evaluate(view, """
            (() => { const s=document.createElement('style');s.textContent='body::before { content:""; background:linear-gradient(red,blue); }';document.head.appendChild(s); })();
        """.trimIndent())
        evaluate(view, OrbisCloudChrome(policy).script)
        assertEquals("false", evaluate(view, "!!document.getElementById('orbis-native-home-chrome')"))
        evaluate(view, OrbisCloudChrome(OrbisCloudWebPolicy.from("https://another.example.org/")!!).script)
        assertEquals("false", evaluate(view, "!!document.getElementById('orbis-native-home-chrome')"))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    private class Request(
        private val target: String, private val main: Boolean,
        private val gesture: Boolean = true, private val redirect: Boolean = false,
        private val method: String = "GET",
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(target)
        override fun isForMainFrame(): Boolean = main
        override fun isRedirect(): Boolean = redirect
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
