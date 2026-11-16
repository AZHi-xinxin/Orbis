package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.webshortcuts.*
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.security.MessageDigest

private const val SHORTCUT_READER_PAGE = "https://orbis-reader.invalid/index.html"

/** Owned by the chat entry, not by the temporary dialog. Detaching preserves the live DOM. */
internal class ShortcutWebSession {
    var view: WebView? = null
    var binding: Pair<String, Boolean>? = null
    var html by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var onReadLink: (String) -> Unit = {}
    fun bind(url: String, interactive: Boolean) {
        if (binding != (url to interactive)) { destroy(); binding = url to interactive }
    }
    fun destroy() {
        view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.webChromeClient = null; it.destroy() }
        view = null; html = null; error = null; binding = null
    }
}

/** Remote content gets no JavascriptInterface, app headers, file picker or native bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OrbisShortcutWebView(assistant: String, url: String, interactive: Boolean,
    retained: ShortcutWebSession? = null, onReadLink: (String)->Unit) {
    val session = retained ?: remember(assistant, url, interactive) { ShortcutWebSession() }
    remember(session, url, interactive) { session.bind(url, interactive); true }
    SideEffect { session.onReadLink = onReadLink }
    val profiles = remember { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    LaunchedEffect(url,interactive) {
        if (!interactive && session.html == null) try { session.html=withContext(Dispatchers.IO) { ReadOnlyWebPage.load(url) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { session.error="无法以只读方式加载这个网页。它可能需要登录或脚本，也可能暂时无法连接。" }
    }
    if (session.error != null) { Text(session.error!!); return }
    if (interactive && !profiles) { Text("此设备的 Android System WebView 暂不支持隔离的游戏登录。请更新系统 WebView 后再开启交互；仍可使用只读浏览。"); return }
    if (!interactive && session.html == null) { Text("正在加载只读网页…"); return }
    val origin=shortcutOrigin(url)
    key(url,interactive) {
        AndroidView(modifier=Modifier.fillMaxSize(),factory={ context ->
            session.view?.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } ?: WebView(context).apply {
                session.view = this
                if (interactive) {
                    val profile=MessageDigest.getInstance("SHA-256").digest("$assistant/$origin".toByteArray()).joinToString("") { "%02x".format(it) }
                    WebViewCompat.setProfile(this,"orbis-mcp-web-$profile")
                }
                settings.javaScriptEnabled=interactive
                settings.domStorageEnabled=interactive
                settings.allowFileAccess=false;settings.allowContentAccess=false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs=false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs=false
                settings.javaScriptCanOpenWindowsAutomatically=false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture=true
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode=if(interactive) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                setDownloadListener { _,_,_,_,_ -> }
                webChromeClient=object:WebChromeClient() {
                    override fun onPermissionRequest(request:PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin:String?,callback:GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
                    override fun onShowFileChooser(view:WebView?,callback:ValueCallback<Array<Uri>>?,params:FileChooserParams?):Boolean { callback?.onReceiveValue(null);return true }
                }
                webViewClient=object:WebViewClient() {
                    private fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Read only",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                        if (!interactive && request.isForMainFrame && request.method == "GET" &&
                            request.url.toString() == SHORTCUT_READER_PAGE) return false
                        val next=runCatching { shortcutUrl(request.url.toString()) }.getOrNull() ?: return true
                        if(interactive && shortcutOrigin(next)==origin) return false
                        if(request.isForMainFrame && request.hasGesture() && request.method=="GET") session.onReadLink(next)
                        return true
                    }
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                        // Serve the already sanitized document locally. loadDataWithBaseURL may
                        // expose a data: main request, which the network policy correctly rejects.
                        if (!interactive && request.isForMainFrame && request.method == "GET" &&
                            request.url.toString() == SHORTCUT_READER_PAGE) {
                            return WebResourceResponse("text/html", "UTF-8",
                                ByteArrayInputStream(session.html.orEmpty().toByteArray(Charsets.UTF_8)))
                        }
                        val next=runCatching { shortcutUrl(request.url.toString()) }.getOrNull() ?: return denied()
                        if(interactive) {
                            // Cross-origin top-level navigations always leave the interactive view.
                            return if(request.isForMainFrame && shortcutOrigin(next)!=origin) denied() else null
                        }
                        if(request.method!="GET" || request.isForMainFrame) return denied()
                        return runCatching {
                            ReadOnlyWebPage.client.newCall(Request.Builder().url(next).get().build()).execute().use { response ->
                                val body=checkNotNull(response.body);val type=body.contentType()
                                if(!response.isSuccessful || type?.type!="image" || type.subtype=="svg+xml") denied()
                                else WebResourceResponse(type.toString(),null,ByteArrayInputStream(body.byteStream().use { it.readBytesBounded(16*1024*1024) }))
                            }
                        }.getOrElse { denied() }
                    }
                    override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError) { handler.cancel() }
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError) {
                        if(request.isForMainFrame) session.error="网页连接失败，请检查网络和该网页服务是否运行。"
                    }
                    override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                        if(request.isForMainFrame) session.error="网页返回 HTTP ${response.statusCode}，请检查网址或稍后重新打开。"
                    }
                    override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                        session.error="网页进程已退出，请关闭后重新打开。";session.view=null;view.destroy();return true
                    }
                }
                loadUrl(if (interactive) url else SHORTCUT_READER_PAGE)
            }
        },onRelease={ if (retained == null) session.destroy() })
    }
}
