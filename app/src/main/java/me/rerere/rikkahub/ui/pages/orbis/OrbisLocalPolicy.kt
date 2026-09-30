package me.rerere.rikkahub.ui.pages.orbis

import java.net.URI

/** Navigation only. No tool execution, model call, credential or arbitrary URL bridge. */
object OrbisLocalPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val HOME = "$ORIGIN/assets/orbis/index.html"
    const val OPEN_CHAT = "$ORIGIN/orbis/open-chat"

    fun isHome(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net" &&
            uri.rawPath == "/assets/orbis/index.html" && uri.rawQuery == null
    }.getOrDefault(false)

    fun mayOpenChat(target: String, source: String?, mainFrame: Boolean, userGesture: Boolean): Boolean =
        target == OPEN_CHAT && isHome(source) && mainFrame && userGesture
}
