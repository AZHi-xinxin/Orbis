package me.rerere.rikkahub.ui.pages.orbis

import android.webkit.WebView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The original site's dawn-to-night palette, now drawn across the system-bar area too. */
internal val orbisCloudHomeGradient = Brush.verticalGradient(
    0f to Color(0xFFFDF8F3), .15f to Color(0xFFF0C896), .35f to Color(0xFFC98A76),
    .50f to Color(0xFF7A5470), .64f to Color(0xFF453764), .78f to Color(0xFF262149),
    .90f to Color(0xFF171A3A), 1f to Color(0xFF10132A),
)

/** Presentation only. Keep the measured WebView and its buttons inside native safe drawing insets. */
internal class OrbisCloudChrome(private val policy: OrbisCloudWebPolicy) {
    fun install(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(policy.origin))
        }
    }

    val script: String get() = """
        (() => {
          if (window !== window.top || location.origin !== '${policy.origin}') return;
          const apply = () => {
            if (!document.body || document.getElementById('orbis-native-home-chrome')) return;
            const bg = getComputedStyle(document.body, '::before').backgroundImage;
            // Only replace the original known calendar gradient, never an arbitrary page background.
            if (!bg.includes('linear-gradient') || !bg.includes('rgb(201, 138, 118)') ||
                !bg.includes('rgb(16, 19, 42)')) return;
            const style = document.createElement('style');
            style.id = 'orbis-native-home-chrome';
            style.textContent = 'body::before { background-image: none !important; }';
            document.head.appendChild(style);
          };
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply, { once: true });
          else apply();
        })();
    """.trimIndent()
}
