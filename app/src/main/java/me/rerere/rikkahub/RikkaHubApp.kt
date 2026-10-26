package me.rerere.rikkahub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.RestoreFailedException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    private var emergencyBusinessLease: me.rerere.rikkahub.utils.EmergencyProcessGate.Lease? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime.isLightweightProcess(base)) return
        // This runs before providers, settings, migrations and any automatic event recovery.
        CrashHandler.install(base)
        try {
            val gate = me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime.gate(base)
            // Both editions ignore stale crash/request flags. An active backup file lease
            // and actual unfinished legacy file moves are the only startup exclusions.
            emergencyBusinessLease = gate.tryAcquireNoRescueBusinessLease {
                me.rerere.rikkahub.data.recovery.requireSafeNoRescueStartup(
                    File(base.applicationInfo.dataDir, "orbis-emergency"))
            }
            if (emergencyBusinessLease != null) return
        } catch (_: Exception) {
            // Do not initialize data writers if the recovery fence cannot be checked.
        }
        // Providers/services can start Application without a human launcher tap. Never launch
        // a UI here or disturb an active rescue save picker. The independent :launcher process
        // displays the blocked state when the human next opens the ordinary app icon.
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    override fun onCreate() {
        super.onCreate()
        if (me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime.isLightweightProcess(this)) return
        // Restore files and settings before eager Koin singletons or workers can access them.
        try {
            val restored = runBlocking(Dispatchers.IO) {
                BackupManager.applyPendingRestore(this@RikkaHubApp, JsonInstant)
            }
            if (restored) {
                Toast.makeText(this, R.string.backup_page_restore_success, Toast.LENGTH_LONG).show()
            }
        } catch (e: RestoreFailedException) {
            Log.e(TAG, "Backup restore rolled back", e)
            Toast.makeText(this, "备份恢复失败，已保留原数据。请重新导入备份。", Toast.LENGTH_LONG).show()
        }
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // CrashHandler and the lifetime backup lease were installed before providers.
        me.rerere.rikkahub.data.orbis.companiontools.AccessibilityHealthMonitor.start(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()
        if (BuildConfig.ORBIS_ENABLED) me.rerere.rikkahub.service.OrbisVideoCallRuntime.get(this)

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        get<AppScope>().launch {
            runCatching { get<me.rerere.rikkahub.service.ChatService>().restoreOrbisEvents() }
                .onFailure { Log.w(TAG, "Orbis event recovery unavailable; receipts retained") }
            runCatching { me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels.open(this@RikkaHubApp).reschedule() }
                .onFailure { Log.w(TAG, "Native sentinel unavailable; configuration retained") }
            if (BuildConfig.ORBIS_ENABLED) runCatching {
                me.rerere.rikkahub.data.orbis.health.AndroidHealthSync.schedule(this@RikkaHubApp)
            }.onFailure { Log.w(TAG, "Health sync configuration retained") }
            if (BuildConfig.ORBIS_ENABLED) runCatching {
                withContext(Dispatchers.IO) { me.rerere.rikkahub.data.orbis.workshop.ImageWorkshop.open(this@RikkaHubApp).recoverQueue() }
            }.onFailure { Log.w(TAG, "Image workshop recovery unavailable; jobs retained") }
        }

        // Increment launch count
        if (BuildConfig.ORBIS_ENABLED) get<AppScope>().launch {
            var applied: me.rerere.tts.controller.VoiceMixerConfig? = null
            get<SettingsStore>().settingsFlow.collect { settings ->
                if (!settings.init && settings.voiceMixer != applied) runCatching {
                    me.rerere.tts.controller.VoiceMixer.apply(settings.voiceMixer)
                    applied = settings.voiceMixer
                }
                    .onFailure { Log.w(TAG, "Voice mixer configuration unavailable; original audio retained") }
            }
        }
        incrementLaunchCount()
        if (BuildConfig.ORBIS_ENABLED) get<AppScope>().launch(Dispatchers.IO) {
            get<me.rerere.rikkahub.data.orbis.memory.SemanticMemoryRecall>().start()
        }

        if (BuildConfig.ORBIS_ENABLED) get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val initial = get<SettingsStore>().settingsFlow.first { !it.init }
                me.rerere.rikkahub.data.sync.AutoBackupRuntime.schedule(this@RikkaHubApp, initial.autoBackup)
                me.rerere.rikkahub.data.sync.ContinuousBackup.start(
                    this@RikkaHubApp, get<me.rerere.rikkahub.data.db.AppDatabase>(), get<SettingsStore>(), get<AppScope>())
            }.onFailure { Log.w(TAG, "Background backup unavailable; local chat remains available") }
        }

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
        if (BuildConfig.ORBIS_ENABLED) get<AppScope>().launch(kotlinx.coroutines.Dispatchers.IO) {
            var previous = emptyMap<String, Boolean>()
            get<SettingsStore>().settingsFlow.collect { settings ->
                if(!settings.init) {
                    val owners = settings.assistants.associate { it.id.toString() to it.orbisPetEnabled }
                    (previous.keys + owners.keys).forEach { owner ->
                        if(owner !in previous || previous[owner] != owners[owner]) runCatching {
                            val enabled = owners[owner] == true &&
                                me.rerere.rikkahub.data.orbis.activities.OrbisActivities.pet(this@RikkaHubApp, owner).snapshot().aiConsent
                            me.rerere.rikkahub.data.orbis.activities.PetAiRuntime.schedule(this@RikkaHubApp, owner, enabled)
                        }
                    }
                    previous = owners
                }
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        if (me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime.isLightweightProcess(this)) return
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
