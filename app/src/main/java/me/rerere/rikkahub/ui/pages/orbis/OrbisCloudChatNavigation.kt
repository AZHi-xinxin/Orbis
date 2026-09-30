package me.rerere.rikkahub.ui.pages.orbis

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** Adapt only the cloud home's large star. No JavaScript interface or native data is exposed. */
internal class OrbisCloudChatNavigation(private val policy: OrbisCloudWebPolicy) {
    // This never loads or enters history. An unrelated iframe cannot guess a top-level action.
    val target: String = "${OrbisLocalPolicy.OPEN_CHAT}?navigation=${UUID.randomUUID()}"

    fun install(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(policy.origin))
        }
    }

    val script: String get() = """
    (() => {
      if (window !== window.top || location.origin !== '${policy.origin}' ||
          window.__orbisNativeChatNavigation) return;
      window.__orbisNativeChatNavigation = true;
      const labelStar = () => {
        const star = document.getElementById('starmapEntryBtn');
        if (star) {
          star.setAttribute('aria-label', '返回当前聊天');
          star.setAttribute('title', '返回当前聊天');
        }
      };
      document.addEventListener('DOMContentLoaded', labelStar, { once: true });
      labelStar();
      document.addEventListener('click', event => {
        const target = event.target;
        if (!event.isTrusted || !(target instanceof Element) ||
            !target.closest('#starmapEntryBtn')) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        location.href = '$target';
      }, true);
    })();
""".trimIndent()
}
