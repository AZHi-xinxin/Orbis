package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = DEFAULT_WEB_SERVER_PORT,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)
    private val operations = SerialWebOperations(appScope)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = DEFAULT_WEB_SERVER_PORT,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ) = operations.submit { startLocked(port, serviceName, localhostOnly) }

    private suspend fun startLocked(port: Int, serviceName: String, localhostOnly: Boolean) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

            // 仅本机模式绑定回环地址
            val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = WebServerState(
                port = port,
                serviceName = serviceName,
                localhostOnly = localhostOnly
            )
            try {
                _state.value = baseState.copy(isLoading = true)
                require(port in 1024..65535) { "端口需要在 1024–65535 之间" }
                Log.i(TAG, "Starting web server on $host:$port")
                if (!isPortAvailable(port)) {
                    Log.w(TAG, "Port $port is already in use")
                    _state.value = baseState.copy(error = "端口 $port 已被占用。请保留其他应用的服务，为 Orbis 选择独立端口。")
                    return
                }
                val candidate = startWebServer(port = port, host = host) {
                    configureWebApi(context, chatService, conversationRepo, folderRepo, settingsStore, filesManager)
                }
                server = candidate
                candidate.start(wait = false)

                _state.value = baseState.copy(isRunning = true)
                // 仅局域网模式注册 mDNS
                if (!localhostOnly) {
                    runCatching {
                        nsdRegistrar.register(
                            port = port,
                            serviceName = serviceName,
                            onRegistered = { info ->
                                _state.value = _state.value.copy(
                                    serviceName = info.serviceName,
                                    hostname = info.hostname,
                                    address = info.address.hostAddress
                                )
                            }
                        )
                    }.onFailure {
                        Log.w(TAG, "NSD register failed", it)
                    }
                }
                Log.i(TAG, "Web server started successfully on $host:$port")
            } catch (e: Exception) {
                runCatching { server?.stop(0, 1000) }
                server = null
                runCatching { nsdRegistrar.unregister() }
                Log.e(TAG, "Failed to start web server", e)
                _state.value = baseState.copy(error = "Web 服务启动失败，请检查端口与网络权限后重试。")
                if (e is CancellationException) throw e
            }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    fun stop() = operations.submit { stopLocked() }

    private suspend fun stopLocked() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                _state.value = _state.value.copy(
                    isRunning = server != null,
                    isLoading = false,
                    error = "停止未完成，请重试；服务可能仍在运行。",
                )
                if (e is CancellationException) throw e
            }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) = operations.submit {
        stopLocked()
        startLocked(port, serviceName, localhostOnly)
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
