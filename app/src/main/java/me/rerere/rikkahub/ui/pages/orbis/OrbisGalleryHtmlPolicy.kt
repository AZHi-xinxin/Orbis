package me.rerere.rikkahub.ui.pages.orbis

import java.util.UUID

/** Gallery-only presentation defaults. The stored artwork and mini-game renderer are unchanged. */
internal class OrbisGalleryHtmlPolicy(nonce: String = UUID.randomUUID().toString()) {
    init { require(nonce.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    val pageUrl = "https://gallery-$nonce.orbis-gallery.invalid/artwork.html"

    fun isDocument(url: String?, mainFrame: Boolean, method: String): Boolean =
        mainFrame && method == "GET" && url == pageUrl

    companion object {
        // Reuse the restrictive policy, not the mini-game page wrapper or its native bridge.
        const val CSP = OrbisMiniGamePolicy.CSP
        val BOOTSTRAP get() = OrbisMiniGamePolicy.BOOTSTRAP

        /** Prefix only: author CSS/viewport later in the document can override these defaults. */
        fun hostedHtml(source: String): String =
            "<!doctype html><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<meta http-equiv=\"x-dns-prefetch-control\" content=\"off\">" +
                "<style>html{height:100%;}body{margin:0;height:100%;min-height:100%;}</style>" +
                "<script>$BOOTSTRAP</script>" + source
    }
}

internal fun gallerySandboxNotice(scriptsEnabled: Boolean): String =
    "离线沙箱 · 无网络 / 持久存储 / 文件访问 / 宿主工具权限。" +
        if (scriptsEnabled) "当前已启用 JavaScript；Canvas 等能力由设备内核决定，请保留兼容兜底。弹层请使用页内元素。"
        else "当前 WebView 缺少安全脚本隔离能力，已关闭 JavaScript，仅显示静态 HTML/CSS；可更新系统 WebView 后再试。"

/** Do not load a page into a zero-sized native viewport and freeze its startup measurements. */
internal fun galleryViewportReady(width: Int, height: Int): Boolean = width > 0 && height > 0
