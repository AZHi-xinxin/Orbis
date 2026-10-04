package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "McpOAuthCoordinator"
internal const val MCP_OAUTH_CALLBACK_PORT = 52_134
internal const val MCP_OAUTH_CALLBACK_PATH = "/oauth/callback"
internal const val MCP_OAUTH_REDIRECT_URI =
    "http://127.0.0.1:$MCP_OAUTH_CALLBACK_PORT$MCP_OAUTH_CALLBACK_PATH"
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

/**
 * 负责 MCP OAuth 的授权、令牌刷新与持久化。
 *
 * 连接生命周期由配置流的消费者管理；令牌持久化后，配置变化会自然触发连接替换。
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val oauthClient: OAuthHttpClient,
    private val discoveryClient: McpOAuthDiscoveryClient,
    private val callbackServer: OAuthLoopbackCallbackServer,
    private val authorizationLauncher: OAuthAuthorizationLauncher,
    private val updateStatus: (Uuid, McpStatus) -> Unit,
) {
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth authorization failed for ${config.commonOptions.name}", e)
                updateStatus(config.id, McpStatus.Error.from(e, fallbackMessage = "OAuth authorization failed"))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization)
    }

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        persistOAuthState(config.id, null)
        return settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
            ?: config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * 按 serverId 串行刷新。获得锁后重新读取配置，避免并发工具调用重复使用同一个 refresh token。
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val latest = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id } ?: configInput
        // Do not wait behind an already running OAuth refresh after a human switches to manual auth.
        if (latest.hasManualAuthorization()) return latest
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
                ?: configInput
            // Use the same precedence as the transport. Old OAuth state must not cause
            // a network refresh (or an unrelated wait) for an explicitly supplied header.
            if (!config.shouldRefreshOAuth(System.currentTimeMillis())) return@withLock config
            val oauth = config.commonOptions.oauth ?: return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                val token = oauthClient.refreshToken(
                    OAuthHttpClient.RefreshTokenRequest(
                        tokenEndpoint = tokenEndpoint,
                        clientId = clientId,
                        clientSecret = oauth.clientSecret,
                        refreshToken = checkNotNull(oauth.refreshToken),
                        resources = listOf(McpOAuthDiscoveryClient.canonicalResource(config.serverUrl)),
                        scope = oauth.scope,
                    )
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                persistOAuthState(config.id, updated)
                // A human may have saved a manual header while the refresh was in flight.
                settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                    ?: config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "Token refresh failed for ${config.commonOptions.name}: ${it.message}")
                settingsStore.settingsFlow.value.mcpServers.find { current -> current.id == config.id } ?: config
            }
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        // An old connection can fail after a manual header was saved. Do not let its late
        // OAuth error initiate discovery or relabel the newly configured manual connection.
        val latest = settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
        if (latest?.hasManualAuthorization() == true) return false
        return needsMcpOAuthAuthorization(config, error) {
            discoveryClient.discoverProtectedResource(config.serverUrl)
        }
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

        val protectedResource = discoveryClient.discoverProtectedResource(serverUrl)
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: error("受保护资源未声明授权服务器")
        val metadata = discoveryClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("授权服务器缺少 authorization_endpoint")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("授权服务器缺少 token_endpoint")
        val scope = config.commonOptions.oauth?.scope
            ?: protectedResource.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val resource = McpOAuthDiscoveryClient.canonicalResource(serverUrl)
        val callbackSession = callbackServer.openSession(context, state)
        try {
            val redirectUri = callbackSession.redirectUri
            check(redirectUri == MCP_OAUTH_REDIRECT_URI) {
                "OAuth 回调服务器地址不一致: $redirectUri"
            }
            val existing = config.commonOptions.oauth
            val canReuseClient = existing?.redirectUri == redirectUri && !existing.clientId.isNullOrBlank()
            var clientId = existing?.clientId.takeIf { canReuseClient }
            var clientSecret = existing?.clientSecret.takeIf { canReuseClient }
            if (clientId.isNullOrBlank()) {
                val registrationEndpoint = metadata.registrationEndpoint
                    ?: error("授权服务器不支持动态注册，且未预配置 client_id")
                val registration = oauthClient.registerClient(
                    registrationEndpoint = registrationEndpoint,
                    request = OAuthHttpClient.ClientRegistrationRequest(
                        clientName = config.commonOptions.name.ifBlank { "RikkaHub" },
                        redirectUris = listOf(redirectUri),
                        scope = scope,
                    ),
                )
                clientId = registration.clientId
                clientSecret = registration.clientSecret
            }

            persistOAuthState(
                config.id,
                (existing ?: McpOAuthState()).copy(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = scope,
                )
            )

            val authorizationUrl = oauthClient.buildAuthorizationUrl(
                OAuthHttpClient.AuthorizationRequest(
                    authorizationEndpoint = authorizationEndpoint,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    pkce = pkce,
                    state = state,
                    scope = scope,
                    resources = listOf(resource),
                )
            )
            withContext(Dispatchers.Main) {
                authorizationLauncher.launch(context, authorizationUrl)
            }

            val callback = callbackSession.awaitCallback(OAUTH_CALLBACK_TIMEOUT)
                ?: error("OAuth 授权超时")
            callback.error?.let { error(buildAuthorizationError(it, callback.errorDescription)) }
            val code = callback.code ?: error("授权失败: 未返回授权码")

            val token = oauthClient.exchangeAuthorizationCode(
                OAuthHttpClient.AuthorizationCodeTokenRequest(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = redirectUri,
                    resources = listOf(resource),
                )
            )
            persistOAuthState(
                config.id,
                McpOAuthState(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = token.scope ?: scope,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                )
            )
        } finally {
            withContext(NonCancellable) {
                callbackSession.close()
            }
        }
    }

    private fun buildAuthorizationError(error: String, description: String?): String =
        if (description.isNullOrBlank()) "授权失败: $error" else "授权失败: $error ($description)"

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

}
