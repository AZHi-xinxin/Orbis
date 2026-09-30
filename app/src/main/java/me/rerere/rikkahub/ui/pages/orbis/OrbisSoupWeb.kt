package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.orbis.soup.SoupWebPolicy

/** The original ts-* layout, backed only by the native public projection and explicit native actions. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OrbisSoupWeb(snapshot: JsonObject, onCommand: (SoupWebPolicy.Command) -> Unit, onClose: () -> Unit) {
    val currentCommand by rememberUpdatedState(onCommand)
    val currentSnapshot by rememberUpdatedState(snapshot)
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun publish(target: WebView, value: JsonObject) {
        if (!SoupWebPolicy.mainFrame(target.url)) return
        val quoted = Json.encodeToString(value.toString())
        target.evaluateJavascript("window.soupReceive(JSON.parse($quoted));", null)
    }
    BackHandler { view?.takeIf { ready }?.evaluateJavascript("window.soupBack();", null) ?: onClose() }
    if (failed || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        Column {
            Text("本机 WebView 暂时无法打开海龟汤。对局和记录仍保留，没有重置。")
            TextButton(onClick = onClose) { Text("返回花园") }
        }
    } else AndroidView(modifier = Modifier.fillMaxSize().testTag("orbis-local-soup-web"), factory = { context ->
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/") { path ->
            if (!SoupWebPolicy.asset("${SoupWebPolicy.ORIGIN}/assets/$path")) null
            else runCatching { WebResourceResponse(when { path.endsWith(".css") -> "text/css"
                path.endsWith(".js") -> "text/javascript"; else -> "text/html" }, "UTF-8", context.assets.open(path)) }.getOrNull()
        }.build()
        WebView(context).apply {
            view = this
            setBackgroundColor(android.graphics.Color.rgb(250, 244, 236))
            settings.javaScriptEnabled = true; settings.domStorageEnabled = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false); settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, false, false)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.method == "GET" && SoupWebPolicy.asset(request.url.toString()))
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    ready = false; failed = true; return true
                }
            }
            WebViewCompat.addWebMessageListener(this, "OrbisSoupBridge", setOf(SoupWebPolicy.ORIGIN)) { source, message, origin, main, _ ->
                if (!main || origin.toString() != SoupWebPolicy.ORIGIN || !SoupWebPolicy.mainFrame(source.url)) return@addWebMessageListener
                val command = runCatching { SoupWebPolicy.command(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                if (command.action == "ready") { ready = true; publish(source, currentSnapshot) }
                else currentCommand(command)
            }
            loadUrl(SoupWebPolicy.PAGE)
        }
    }, update = {}, onRelease = { released ->
        if (view === released) { view = null; ready = false }
        // A renderer that has already died may reject one cleanup call. Still attempt the rest.
        runCatching { released.stopLoading() }
        runCatching { WebViewCompat.removeWebMessageListener(released, "OrbisSoupBridge") }
        runCatching { released.destroy() }
    })
    // Updates replace only visible text/data in this one document, never reload or recreate the WebView.
    LaunchedEffect(snapshot, ready) { if (ready) view?.let { publish(it, snapshot) } }
}
