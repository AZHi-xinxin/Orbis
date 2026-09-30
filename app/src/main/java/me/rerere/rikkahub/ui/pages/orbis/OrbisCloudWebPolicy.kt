package me.rerere.rikkahub.ui.pages.orbis

import java.net.URI
import me.rerere.rikkahub.data.model.normalizeOrbisCloudHomeUrl

/** An ordinary, user-approved HTTPS website, never a privileged native tool bridge. */
class OrbisCloudWebPolicy private constructor(val homeUrl: String) {
    val host: String = URI(homeUrl).host.lowercase()
    val origin: String = "https://$host"

    fun allowsNavigation(target: String?): Boolean = publicHttpsUri(target)?.host
        ?.equals(host, ignoreCase = true) == true

    /** The sole native action is returning to the retained chat after a foreground user click. */
    fun mayOpenChat(
        target: String, source: String?, mainFrame: Boolean, userGesture: Boolean,
        method: String, redirect: Boolean, navigationTarget: String,
    ): Boolean = target == navigationTarget && allowsNavigation(source) &&
        mainFrame && userGesture && method == "GET" && !redirect

    // The trusted site's APIs may be on another HTTPS host (for example Supabase).
    // This is WebView URL hardening, not a claim of a redirect-proof network firewall.
    fun allowsResource(target: String?): Boolean = publicHttpsUri(target) != null

    companion object {
        fun from(homeUrl: String): OrbisCloudWebPolicy? =
            normalizeOrbisCloudHomeUrl(homeUrl)?.let(::OrbisCloudWebPolicy)

        private fun publicHttpsUri(value: String?): URI? = runCatching {
            if (value.isNullOrBlank() || value.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
            val uri = URI(value)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
            // Reuse strict authority validation, while allowing legitimate API queries/fragments.
            val authorityOnly = "https://${uri.rawAuthority}/"
            if (normalizeOrbisCloudHomeUrl(authorityOnly) == null) return null
            uri
        }.getOrNull()
    }
}

internal fun shouldMountOrbisCloudHome(
    loaded: Boolean, enabled: Boolean, validAddress: Boolean,
    visible: Boolean, resumed: Boolean, editingConnection: Boolean,
): Boolean = loaded && enabled && validAddress && visible && resumed && !editingConnection
