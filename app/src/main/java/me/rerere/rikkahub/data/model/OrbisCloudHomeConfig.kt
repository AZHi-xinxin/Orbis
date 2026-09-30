package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/** This is an optional website entry, independent of native chat providers and their credentials. */
@Serializable
data class OrbisCloudHomeConfig(
    /** Kept for compatibility: true means the user's existing remote website, false means local. */
    val enabled: Boolean = false,
    val homeUrl: String = "",
    val gardenName: String = "我们的后花园",
    val humanName: String = "我",
    val companionName: String = "伙伴",
)

internal fun validOrbisGardenName(value: String): Boolean = value.isNotBlank() &&
    value.length <= 32 && value.toByteArray(Charsets.UTF_8).size <= 128 && value.none { it.isISOControl() } &&
    Charsets.UTF_8.newEncoder().canEncode(value)

/** Pure, DNS-free validation. A rejected address must never be passed to a WebView. */
fun normalizeOrbisCloudHomeUrl(value: String): String? {
    if (value.length > 2048 || value.any { it.isISOControl() || it == '\\' || it.code > 126 }) return null
    val input = value.trim()
    if (input.isEmpty()) return null
    val uri = runCatching { URI(input) }.getOrNull() ?: return null
    if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    // IPv6 literals, alternate numeric IPv4 spellings and percent-encoded authorities are rejected.
    if (host.length > 253 || host.endsWith('.') || ':' in host || '%' in uri.rawAuthority) return null
    if (uri.rawAuthority != uri.host && !uri.rawAuthority.equals("${uri.host}:443", ignoreCase = true)) return null
    if (host == "appassets.androidplatform.net" || host.endsWith(".appassets.androidplatform.net")) return null
    if (host == "localhost" || listOf(".localhost", ".local", ".internal", ".lan", ".home", ".test", ".invalid").any(host::endsWith)) return null
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return null
    if (labels.all { it.all(Char::isDigit) }) {
        if (labels.size != 4 || labels.any { it.length > 1 && it.startsWith('0') }) return null
        val octets = labels.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        val a = octets[0]
        val b = octets[1]
        if (a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 192 && b == 0) || (a == 198 && b in 18..19)) return null
    } else if (!labels.last().any { it in 'a'..'z' } || labels.last().startsWith("0x")) return null
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    if (!path.startsWith('/') || path.contains("//")) return null
    for (segment in path.split('/')) {
        val decoded = StringBuilder()
        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            if (char == '%') {
                if (index + 2 >= segment.length) return null
                val code = segment.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                // Reject nested escapes, encoded separators and invisible bytes before browser parsing.
                if (code <= 32 || code == 127 || code == 37 || code == 47 || code == 92) return null
                decoded.append(code.toChar())
                index += 3
            } else {
                decoded.append(char)
                index++
            }
        }
        if (decoded.toString() == "." || decoded.toString() == "..") return null
    }
    return "https://$host$path"
}
