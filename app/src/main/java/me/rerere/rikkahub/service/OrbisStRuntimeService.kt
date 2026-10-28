package me.rerere.rikkahub.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.st.StRuntimeHost

/** Explicit user-visible ST hosting; app foreground entry restores previously requested runs. */
class OrbisStRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = StRuntimeHost.get(this)
        if (intent?.action == STOP) { host.stopAll(); return START_NOT_STICKY }
        val root = intent?.getStringExtra("root") ?: run { stopSelf(); return START_NOT_STICKY }
        if (!host.hasRuns()) { stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "手机 ST 持续运行", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, ID, Intent(this, RouteActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, ID, Intent(this, OrbisStRuntimeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_rikkahub)
                .setContentTitle("Orbis 正在托管手机 ST").setContentText("可在工作区查看状态；点停止会停止本机托管实例")
                .setContentIntent(open).setOngoing(true).addAction(0, "停止 ST", stop).build()
            ServiceCompat.startForeground(this, ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { host.failPending(root, "st_foreground_unavailable"); stopSelf(); return START_NOT_STICKY }
        scope.launch(Dispatchers.IO) { host.launchPending(root) }
        if (observer == null) observer = scope.launch {
            host.states.collect { if (!host.hasRuns()) stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel(); StRuntimeHost.get(this).stopAll(forgetRunning = false)
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "orbis_st_runtime"
        private const val ID = 2027
        private const val STOP = "org.orbis.st.STOP"
    }
}
