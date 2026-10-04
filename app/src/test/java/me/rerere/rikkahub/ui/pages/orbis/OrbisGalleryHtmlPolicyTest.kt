package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisGalleryHtmlPolicyTest {
    @Test fun ownedDocumentOnlyHasAnIndependentOrigin() {
        val policy = OrbisGalleryHtmlPolicy("fixture")
        assertTrue(policy.isDocument(policy.pageUrl, true, "GET"))
        assertFalse(policy.isDocument(policy.pageUrl, false, "GET"))
        assertFalse(policy.isDocument(policy.pageUrl, true, "POST"))
        listOf("file:///private", "content://private", "https://example.invalid/", "http://127.0.0.1/",
            policy.pageUrl + "?file=private", policy.pageUrl + "#fragment").forEach {
            assertFalse(policy.isDocument(it, true, "GET"))
        }
        assertNotEquals(OrbisGalleryHtmlPolicy().pageUrl, OrbisGalleryHtmlPolicy().pageUrl)
    }

    @Test fun wrapperAddsMobileViewportAndDefiniteRootHeightWithoutRewritingArtwork() {
        val source = "<!doctype html><html><head><style>body{height:auto;margin:12px}</style></head>" +
            "<body><div style='height:10vh;aspect-ratio:2/1'>🌙</div><script>window.art=1</script></body></html>"
        val hosted = OrbisGalleryHtmlPolicy.hostedHtml(source)
        assertTrue(hosted.endsWith(source))
        assertTrue(hosted.contains("name=\"viewport\" content=\"width=device-width, initial-scale=1\""))
        assertTrue(hosted.contains("html{height:100%;}body{margin:0;height:100%;min-height:100%;}"))
        assertTrue(hosted.indexOf("html{height:100%") < hosted.indexOf("body{height:auto"))
        assertTrue(hosted.indexOf("RTCPeerConnection") < hosted.indexOf("window.art"))
    }

    @Test fun authorViewportAndCssAreNotReplacedByForcedStyleOrInjectedViewportScript() {
        val source = "<meta name='viewport' content='width=640'><style>html,body{height:360px}</style>"
        val hosted = OrbisGalleryHtmlPolicy.hostedHtml(source)
        assertTrue(hosted.endsWith(source))
        assertFalse(hosted.contains("!important"))
        assertFalse(hosted.contains("innerHeight"))
        assertFalse(hosted.contains("localStorage"))
        assertFalse(hosted.contains("JavascriptInterface"))
    }

    @Test fun galleryDefaultsDoNotChangeTheMiniGameWrapper() {
        val source = "<p>synthetic</p>"
        assertFalse(OrbisMiniGamePolicy.hostedHtml(source).contains("name=\"viewport\""))
        assertFalse(OrbisMiniGamePolicy.hostedHtml(source).contains("html{height"))
    }

    @Test fun isolationStillBlocksNetworkStorageOriginsAndNativeCapabilities() {
        val policy = OrbisGalleryHtmlPolicy.CSP
        listOf("connect-src 'none'", "frame-src 'none'", "form-action 'none'", "base-uri 'none'",
            "worker-src 'none'", "webrtc 'block'", "sandbox allow-scripts;").forEach { assertTrue(policy.contains(it)) }
        assertFalse(policy.contains("allow-same-origin"))
        assertFalse(policy.contains("https:"))
        assertFalse(policy.contains("file:"))
        assertTrue(OrbisGalleryHtmlPolicy.BOOTSTRAP.contains("configurable:false"))
    }

    @Test fun noticeReflectsActualScriptModeRatherThanAssumingAnOldWebView() {
        val interactive = gallerySandboxNotice(true)
        val static = gallerySandboxNotice(false)
        assertTrue(interactive.contains("当前已启用 JavaScript"))
        assertFalse(interactive.contains("仅显示静态"))
        assertTrue(static.contains("已关闭 JavaScript"))
        assertTrue(static.contains("仅显示静态 HTML/CSS"))
        listOf(interactive, static).forEach { assertTrue(it.contains("无网络 / 持久存储 / 文件访问 / 宿主工具权限")) }
    }

    @Test fun firstLoadRequiresTwoPositiveMeasuredDimensions() {
        assertFalse(galleryViewportReady(0, 0))
        assertFalse(galleryViewportReady(320, 0))
        assertFalse(galleryViewportReady(0, 640))
        assertFalse(galleryViewportReady(-1, 640))
        assertTrue(galleryViewportReady(1, 1))
        assertTrue(galleryViewportReady(320, 640))
    }
}
