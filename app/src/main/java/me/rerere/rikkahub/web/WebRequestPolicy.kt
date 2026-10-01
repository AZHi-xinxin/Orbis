package me.rerere.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.net.URI
import java.security.MessageDigest

internal const val WEB_ORIGIN_HEADER = "X-Orbis-Web-Origin"

internal fun sensitiveWebBearer(authorization: String?): String? = authorization
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

/** Authentication is optional only for the explicit archive-import route family. */
internal fun canAccessSensitiveWebRoute(jwtEnabled: Boolean, passwordConfigured: Boolean,
    authenticated: Boolean, bearer: String?, allowPasswordFreeImport: Boolean): Boolean =
    if (jwtEnabled) passwordConfigured && authenticated && !bearer.isNullOrBlank()
    else allowPasswordFreeImport

/** Browser sends its own origin explicitly even for same-origin GETs without an Origin header. */
internal fun isSameOriginWebRequest(host: String?, declaredOrigin: String?, origin: String?, fetchSite: String?): Boolean {
    if (host.isNullOrBlank() || declaredOrigin.isNullOrBlank()) return false
    if (fetchSite != null && fetchSite != "same-origin") return false
    return runCatching {
        val declared = URI(declaredOrigin)
        if (declared.scheme !in setOf("http", "https") || declared.host == null ||
            declared.rawUserInfo != null || declared.rawQuery != null || declared.rawFragment != null ||
            !declared.rawPath.isNullOrEmpty()) return false
        if (!declared.rawAuthority.equals(host, ignoreCase = true)) return false
        origin == null || origin == declaredOrigin
    }.getOrDefault(false)
}

/** Password protection is optional. When enabled, imports still require the normal bearer login. */
internal fun ApplicationCall.requireSensitiveWebAccess(settingsStore: SettingsStore, allowPasswordFreeImport: Boolean = false): String {
    val settings = settingsStore.settingsFlow.value
    val bearer = sensitiveWebBearer(request.headers[HttpHeaders.Authorization])
    if (!canAccessSensitiveWebRoute(settings.webServerJwtEnabled,
        settings.webServerAccessPassword.isNotBlank(),
        !settings.webServerJwtEnabled || principal<JWTPrincipal>() != null,
        bearer, allowPasswordFreeImport)) {
        throw UnauthorizedException("web_auth_required")
    }
    if (!isSameOriginWebRequest(request.headers[HttpHeaders.Host], request.headers[WEB_ORIGIN_HEADER],
            request.headers[HttpHeaders.Origin], request.headers["Sec-Fetch-Site"])) {
        throw ForbiddenException("same_origin_required")
    }
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    // Password-free mode intentionally shares this local Web workspace. Enabling password
    // protection later changes the owner key, so unauthenticated jobs do not cross modes.
    val owner = if (settings.webServerJwtEnabled) "authenticated:$bearer" else "local-password-free"
    return MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
