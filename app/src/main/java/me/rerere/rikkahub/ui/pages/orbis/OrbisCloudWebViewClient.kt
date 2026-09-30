package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Shared by the UI and synthetic WebView instrumentation. No credentials or native API bridge. */
internal open class OrbisCloudWebViewClient(
    private val policy: OrbisCloudWebPolicy,
    private val onLoading: (Boolean) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onBlockedNavigation: () -> Unit = {},
    private val onOpenChat: () -> Unit = {},
    private val chatNavigation: OrbisCloudChatNavigation = OrbisCloudChatNavigation(policy),
    private val chrome: OrbisCloudChrome = OrbisCloudChrome(policy),
) : WebViewClient() {
    fun installChatNavigation(view: WebView) {
        chatNavigation.install(view)
        chrome.install(view)
    }

    final override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (policy.mayOpenChat(request.url.toString(), view.url, request.isForMainFrame,
                request.hasGesture(), request.method, request.isRedirect, chatNavigation.target)) {
            onOpenChat()
            return true
        }
        if (request.isForMainFrame && policy.allowsNavigation(request.url.toString())) return false
        if (!request.isForMainFrame && policy.allowsResource(request.url.toString())) return false
        onBlockedNavigation()
        return true
    }

    final override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val allowed = if (request.isForMainFrame) policy.allowsNavigation(request.url.toString())
            else policy.allowsResource(request.url.toString())
        return if (allowed) responseForAllowedRequest(request) else WebResourceResponse("text/plain", "UTF-8", 403,
            "Blocked", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("orbis_cloud_url_blocked".toByteArray()))
    }

    /** Ordinary production loading returns null. Tests provide responses without any live network. */
    protected open fun responseForAllowedRequest(request: WebResourceRequest): WebResourceResponse? = null

    final override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!policy.allowsNavigation(url)) {
            view.stopLoading()
            onFailure("原主页跳转到了其他地址，请在连接设置中核对。")
        } else onLoading(true)
    }

    final override fun onPageFinished(view: WebView, url: String?) {
        // Fallback for older WebViews without document-start scripts; the script is idempotent.
        if (policy.allowsNavigation(url) && policy.allowsNavigation(view.url)) {
            view.evaluateJavascript(chatNavigation.script, null)
            view.evaluateJavascript(chrome.script, null)
        }
        onLoading(false)
    }

    final override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
        if (request.isForMainFrame) {
            onLoading(false)
            onFailure("暂时无法打开原主页，请检查网络后重试。")
        }
    }

    final override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            onLoading(false)
            onFailure("原主页返回 HTTP ${response.statusCode}，请稍后重试。")
        }
    }

    final override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onLoading(false)
        onFailure("原主页的安全连接验证失败，请核对网站证书。")
    }

    final override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onLoading(false)
        onFailure("网页暂时中断，可以重新打开；当前聊天仍保留。")
        return true
    }
}
