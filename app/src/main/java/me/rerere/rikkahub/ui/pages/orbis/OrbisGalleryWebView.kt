package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

/** Pure offline artwork frame. Deliberately NO JavascriptInterface, app URL handler or form bridge. */
@Composable
internal fun OrbisGalleryWebView(identity: String, html: String, modifier: Modifier = Modifier, onFailure: () -> Unit,
    scriptsEnabled: Boolean = gallerySupportsSafeScripts(), onViewCreated: (WebView) -> Unit = {},
    onLoaded: () -> Unit = {},
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
) {
    val policy = remember(identity) { OrbisGalleryHtmlPolicy() }
    val currentFailure = rememberUpdatedState(onFailure)
    val currentLoaded = rememberUpdatedState(onLoaded)
    val holder = remember { arrayOfNulls<OrbisGalleryNativeWebView>(1) }
    // Match the surrounding native surface even before the first Chromium frame exists.
    val opaqueBackground = backgroundColor.copy(alpha = 1f)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(identity, lifecycle) {
        val listener = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause(); Lifecycle.Event.ON_RESUME -> holder[0]?.onResume(); else -> Unit }
        }
        lifecycle.addObserver(listener)
        onDispose { lifecycle.removeObserver(listener) }
    }
    // Exact positive bounds are independent of HTML content size. No wrap_content feedback
    // loop: a page whose only height is 100vh must still receive a real initial viewport.
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(opaqueBackground)) {
        if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && maxWidth > 0.dp && maxHeight > 0.dp) {
            key(identity) { AndroidView(modifier = Modifier.requiredSize(maxWidth, maxHeight).clipToBounds(), factory = { context ->
                OrbisGalleryNativeWebView(context, policy, html, scriptsEnabled, opaqueBackground.toArgb(),
                    onFailure = { currentFailure.value() }, onLoaded = { currentLoaded.value() }).also {
                    holder[0] = it
                    onViewCreated(it)
                }
            }, update = { view -> view.updateHostBackground(opaqueBackground.toArgb()) }, onRelease = { view ->
                if (holder[0] === view) holder[0] = null
                view.releaseArtwork()
            }) }
        }
    }
}

internal fun gallerySupportsSafeScripts(): Boolean =
    runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)

/** Created once per artwork/version. Resizing/full-screen never reloads it or discards page state. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal class OrbisGalleryNativeWebView(
    context: Context, private val policy: OrbisGalleryHtmlPolicy, html: String, scriptsEnabled: Boolean,
    backgroundColor: Int, private val onFailure: () -> Unit, private val onLoaded: () -> Unit,
) : WebView(context) {
    private var released = false
    private var loadScheduled = false
    private var pageStarted = false
    private var ownedDocumentFinished = false
    private val firstFrame = GalleryFirstFrame(policy.pageUrl)
    private var hostBackground = backgroundColor or (0xff shl 24)
    internal val isArtworkCovered: Boolean get() = firstFrame.covered

    init {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // AndroidView does not clip its child by default. Chromium's render node must be
            // bounded independently of page CSS, including its first frame and later resizes.
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            // WebView.setBackgroundColor delegates to Chromium; give the native View its own
            // matching drawable as well, including before Chromium has supplied a first frame.
            background = ColorDrawable(hostBackground)
            setBackgroundColor(hostBackground)
            settings.javaScriptEnabled = scriptsEnabled && gallerySupportsSafeScripts()
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true; settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false); settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (settings.javaScriptEnabled) WebViewCompat.addDocumentStartJavaScript(this, OrbisGalleryHtmlPolicy.BOOTSTRAP, setOf("*"))
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
            }
            setDownloadListener { _, _, _, _, _ -> }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (policy.isDocument(request.url.toString(), request.isForMainFrame, request.method)) WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Content-Security-Policy" to OrbisGalleryHtmlPolicy.CSP, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer", "X-DNS-Prefetch-Control" to "off"),
                        ByteArrayInputStream(OrbisGalleryHtmlPolicy.hostedHtml(html).toByteArray()))
                    else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Deprecated("Deprecated in Java") override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                override fun onPageFinished(view: WebView, url: String?) {
                    if (released || url != policy.pageUrl) return
                    ownedDocumentFinished = true
                    requestVisualFrame()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (policy.isDocument(request.url.toString(), request.isForMainFrame, request.method)) failArtwork()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failArtwork()
                    return true
                }
            }
    }

    /** Keep both Chromium and the cover inside this viewport, never the surrounding dialog. */
    override fun draw(canvas: Canvas) {
        if (!galleryViewportReady(width, height)) return
        val checkpoint = canvas.save()
        try {
            // View.draw receives content coordinates: the parent has already translated by
            // -scrollX/-scrollY. A fixed origin would progressively cut off a scrolled page.
            canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
            super.draw(canvas)
            if (firstFrame.covered) canvas.drawColor(hostBackground)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    fun updateHostBackground(color: Int) {
        val opaque = color or (0xff shl 24)
        if (hostBackground == opaque) return
        hostBackground = opaque
        background = ColorDrawable(opaque)
        setBackgroundColor(opaque)
        invalidate()
    }

    private fun failArtwork() {
        if (released || !firstFrame.stop()) return
        invalidate()
        onFailure()
    }

    private fun requestVisualFrame() {
        if (released || !ownedDocumentFinished || !isAttachedToWindow || !galleryViewportReady(width, height)) return
        val requestId = firstFrame.requestAfterPageFinished(policy.pageUrl) ?: return
        // API 23+, below our minimum SDK. Unlike onPageFinished, this guarantees the next draw
        // includes the submitted DOM. Stay attached and VISIBLE, drawing the cover last so
        // rendering can progress underneath it. Detachment never becomes a timed reveal.
        try {
            postVisualStateCallback(requestId, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (released) return
                    if (!isAttachedToWindow || !galleryViewportReady(width, height)) {
                        firstFrame.deferVisualState(requestId)
                        return
                    }
                    if (firstFrame.acknowledgeVisualState(requestId)) {
                        invalidate()
                        onLoaded()
                    }
                }
            })
        } catch (_: RuntimeException) { failArtwork() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleFirstLoad()
        requestVisualFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFirstLoad()
        requestVisualFrame()
    }

    private fun scheduleFirstLoad() {
        if (released || pageStarted || loadScheduled || !galleryViewportReady(width, height)) return
        loadScheduled = true
        post {
            loadScheduled = false
            if (!released && !pageStarted && isAttachedToWindow && galleryViewportReady(width, height)) {
                pageStarted = true
                loadUrl(policy.pageUrl)
            }
        }
    }

    fun releaseArtwork() {
        if (released) return
        released = true
        firstFrame.stop()
        stopLoading()
        destroy()
    }
}
