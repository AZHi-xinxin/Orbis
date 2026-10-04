package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.CancellationException

private const val MCP_TOKEN_REFRESH_LEEWAY_MS = 60_000L

/** Even a blank/manual non-Bearer header is explicit: never silently replace it with OAuth. */
internal fun McpServerConfig.hasManualAuthorization(): Boolean =
    commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }

internal fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    return if (!token.isNullOrBlank() && !hasManualAuthorization()) {
        base + ("Authorization" to "Bearer $token")
    } else base
}

internal fun McpServerConfig.shouldRefreshOAuth(nowMillis: Long): Boolean {
    if (hasManualAuthorization()) return false
    val oauth = commonOptions.oauth ?: return false
    if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return false
    val expired = oauth.expiresAt > 0 && nowMillis >= oauth.expiresAt - MCP_TOKEN_REFRESH_LEEWAY_MS
    return oauth.accessToken.isNullOrBlank() || expired
}

internal fun mcpLooksUnauthorized(error: Throwable): Boolean {
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message }.joinToString(" ").lowercase()
    return message.contains("401") || message.contains("unauthorized") ||
        message.contains("invalid_token") || message.contains("invalid access token") ||
        message.contains("missing or invalid")
}

/** The probe is not invoked for manual auth, including a failed or empty credential. */
internal suspend fun needsMcpOAuthAuthorization(
    config: McpServerConfig,
    error: Throwable,
    discover: suspend () -> Unit,
): Boolean {
    if (config.hasManualAuthorization()) return false
    if (mcpLooksUnauthorized(error) && config.commonOptions.oauth?.enabled == true) return true
    return try {
        discover()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** Fixed summary only; no URL, header value or server-controlled text is interpolated. */
internal fun mcpConnectionError(config: McpServerConfig, error: Throwable): McpStatus.Error {
    val original = McpStatus.Error.from(error)
    return if (config.hasManualAuthorization() && mcpLooksUnauthorized(error)) {
        original.copy(message = "MCP 手动认证未通过。请核对令牌、服务地址及请求头转发；这不代表必须改用 OAuth。")
    } else original
}
