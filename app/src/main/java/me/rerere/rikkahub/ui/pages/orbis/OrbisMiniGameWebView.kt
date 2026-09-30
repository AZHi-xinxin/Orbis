package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

internal class OrbisMiniGameJavascriptBridge(private val gate: OrbisMiniGameResultGate) {
    @JavascriptInterface fun finish(payload: String): String = gate.finish(payload)
}

/** This WebView deliberately does not share the general rich-text/local-assets WebView client. */
internal class OrbisMiniGameWebViewClient(
    private val policy: OrbisMiniGamePolicy,
    private val html: String,
    private val onRendererFailure: () -> Unit,
    private val onLoaded: () -> Unit = {},
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        if (policy.isDocument(request.url.toString(), request.isForMainFrame, request.method)) {
            return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to OrbisMiniGamePolicy.CSP,
                    "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer",
                    "X-DNS-Prefetch-Control" to "off"),
                ByteArrayInputStream(OrbisMiniGamePolicy.hostedHtml(html).toByteArray(Charsets.UTF_8)))
        }
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    // loadUrl by this host initializes the document. Imported JS cannot navigate anywhere.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun onPageFinished(view: WebView, url: String?) {
        if (url == policy.pageUrl) onLoaded()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererFailure()
        return true
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun createOrbisMiniGameWebView(
    context: Context,
    policy: OrbisMiniGamePolicy,
    html: String,
    gate: OrbisMiniGameResultGate,
    onRendererFailure: () -> Unit,
    onLoaded: () -> Unit = {},
): WebView = WebView(context).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.blockNetworkLoads = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setGeolocationEnabled(false)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
        override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
            callback?.onReceiveValue(null)
            return true
        }
    }
    setDownloadListener { _, _, _, _, _ -> /* Private self-contained games cannot download files. */ }
    // An inline prefix alone is insufficient for newly-created about:blank realms. Refuse to
    // execute imported JS on old engines that cannot protect every document before its scripts.
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        gate.close()
        settings.javaScriptEnabled = false
        loadData("<p>请更新 Android System WebView 后再打开小游戏。原生五子棋仍可使用。</p>", "text/html", "UTF-8")
        onLoaded()
        return@apply
    }
    WebViewCompat.addDocumentStartJavaScript(this, OrbisMiniGamePolicy.BOOTSTRAP, setOf("*"))
    webViewClient = OrbisMiniGameWebViewClient(policy, html, onRendererFailure, onLoaded)
    addJavascriptInterface(OrbisMiniGameJavascriptBridge(gate), "OrbisGame")
    loadUrl(policy.pageUrl)
}

@Composable
internal fun OrbisMiniGameWebView(
    sessionId: String,
    html: String,
    onFinish: (OrbisMiniGameResult) -> Unit,
    onRendererFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Never update the writer of an already mounted frame to a different session's callback.
    val boundFinish = remember(sessionId) { onFinish }
    val latestFailure = rememberUpdatedState(onRendererFailure)
    val policy = remember(sessionId) { OrbisMiniGamePolicy() }
    val gate = remember(sessionId) { OrbisMiniGameResultGate(boundFinish) }
    val holder = remember { arrayOfNulls<WebView>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(sessionId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); gate.close() }
    }
    key(sessionId) { AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
        createOrbisMiniGameWebView(context, policy, html, gate,
            onRendererFailure = { latestFailure.value() }).also { holder[0] = it }
    }, onRelease = { view ->
        gate.close()
        if (holder[0] === view) holder[0] = null
        view.removeJavascriptInterface("OrbisGame")
        view.stopLoading()
        view.destroy()
    }) }
}
