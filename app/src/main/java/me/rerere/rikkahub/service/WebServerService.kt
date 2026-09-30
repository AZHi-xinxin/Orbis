package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.WEB_SERVER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.web.DEFAULT_WEB_SERVER_PORT
import me.rerere.rikkahub.web.shouldFinishWebService
import org.koin.android.ext.android.inject

private const val TAG = "WebServerService"

class WebServerService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.WEB_SERVER_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.WEB_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_LOCALHOST_ONLY = "localhost_only"
        const val NOTIFICATION_ID = 2001
    }

    private val webServerManager: WebServerManager by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var latestCommand = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_WEB_SERVER_PORT)
                val localhostOnly = intent.getBooleanExtra(EXTRA_LOCALHOST_ONLY, false)
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                requestStart(port, localhostOnly)
            }

            ACTION_STOP -> {
                val command = ++latestCommand
                val operation = webServerManager.stop()
                serviceScope.launch {
                    operation.join()
                    if (command == latestCommand) {
                        settingsStore.update { it.copy(webServerEnabled = webServerManager.state.value.isRunning) }
                        finishIfIdle(command)
                    }
                }
            }

            null -> {
                // 兜底：intent 为 null 时根据设置决定是否启动
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val restoreCommand = ++latestCommand
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (restoreCommand != latestCommand) return@launch
                    if (settings.webServerEnabled) {
                        requestStart(
                            port = settings.webServerPort,
                            localhostOnly = settings.webServerLocalhostOnly
                        )
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildStartingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildStartingNotification())
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM (如 realme UI/ColorOS) 会在系统侧拒绝 FGS 类型权限，
            // 即使 Manifest 已声明 FOREGROUND_SERVICE_SPECIAL_USE 也会抛 SecurityException
            Log.e(TAG, "Failed to start foreground service", e)
            webServerManager.reportError("Failed to start foreground service: ${e.message}")
            false
        }
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            webServerManager.state.collect { state ->
                when {
                    state.isRunning -> {
                        val host = if (state.localhostOnly) "localhost" else (state.address ?: "localhost")
                        val url = "http://$host:${state.port}"
                        updateNotification(buildRunningNotification(url))
                    }

                    state.isLoading -> updateNotification(buildStartingNotification())
                }
            }
        }
    }

    private fun requestStart(port: Int, localhostOnly: Boolean) {
        val command = ++latestCommand
        startObservingState()
        val operation = webServerManager.start(port = port, localhostOnly = localhostOnly)
        serviceScope.launch {
            operation.join()
            if (command == latestCommand) {
                val state = webServerManager.state.value
                // A duplicate START may not produce a new StateFlow emission.
                if (state.isRunning) {
                    val host = if (state.localhostOnly) "localhost" else (state.address ?: "localhost")
                    updateNotification(buildRunningNotification("http://$host:${state.port}"))
                }
                settingsStore.update { it.copy(webServerEnabled = webServerManager.state.value.isRunning) }
                finishIfIdle(command)
            }
        }
    }

    private fun finishIfIdle(command: Long) {
        val state = webServerManager.state.value
        // A failed first attempt is terminal too; the initial idle emission is not.
        if (shouldFinishWebService(command, latestCommand, state.isRunning, state.isLoading)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStartingNotification() = NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_channel_web_server))
        .setContentText(getString(R.string.notification_web_server_starting))
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun buildRunningNotification(url: String): android.app.Notification {
        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_web_server_running))
            .setContentText(url)
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.notification_web_server_stop), stopPendingIntent)
            .build()
    }
}
