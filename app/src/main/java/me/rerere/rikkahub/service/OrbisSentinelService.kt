package me.rerere.rikkahub.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels

/** User-visible local scheduler; Android may still delay it or stop it after a force-stop. */
class OrbisSentinelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var nextAlarmAt = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LcExternalRecoveryGate.isAllowed()) { stopSelf(); return START_NOT_STICKY }
        val owner = runCatching { OrbisSentinels.open(this) }.getOrElse { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == PAUSE) { owner.setHumanEnabled(false); stopSelf(); return START_NOT_STICKY }
        val state = try { owner.rules.refresh() } catch (_: Exception) {
            owner.setRuntime(false, "sentinel_store_unavailable"); stopSelf(); return START_NOT_STICKY
        }
        if (!state.enabled || state.rules.none { it.enabled }) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地哨兵", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, ID, Intent(this, RouteActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pause = PendingIntent.getService(this, ID + 1, Intent(this, OrbisSentinelService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("Orbis 本地哨兵")
                .setContentText("按 AI 设置的规则观察与唤醒 · 可随时总暂停")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .addAction(0, "总暂停", pause).build()
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
            owner.setRuntime(true, null)
        } catch (_: Exception) { owner.setRuntime(false, "foreground_service_unavailable"); stopSelf(); return START_NOT_STICKY }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                try {
                    val current = owner.rules.refresh()
                    if (!current.enabled || current.rules.none { it.enabled }) { stopSelf(); break }
                    owner.tick()
                    if (System.currentTimeMillis() >= nextAlarmAt) {
                        nextAlarmAt = System.currentTimeMillis() + 60_000
                        scheduleCheck(this@OrbisSentinelService, nextAlarmAt)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    owner.setRuntime(false, "scheduler_check_failed")
                    stopSelf(); break
                }
                delay(5_000)
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        // A rejected isolated-test start still receives onDestroy; never open host state here.
        if (LcExternalRecoveryGate.isAllowed()) {
            runCatching { OrbisSentinels.open(this).let { it.setRuntime(false, it.runtimeState.value.error) } }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "orbis_native_sentinel"
        private const val ID = 2016
        private const val PAUSE = "org.orbis.sentinel.PAUSE"
        fun start(context: Context): Boolean {
            if (!LcExternalRecoveryGate.isAllowed()) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OrbisSentinelService::class.java)); true
            }.getOrDefault(false)
        }
        fun stop(context: Context) {
            if (!LcExternalRecoveryGate.isAllowed()) return
            context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
            context.stopService(Intent(context, OrbisSentinelService::class.java))
        }
        private fun alarm(context: Context) = PendingIntent.getBroadcast(context, ID,
            Intent(context, OrbisSentinelReceiver::class.java).setAction("org.orbis.sentinel.CHECK"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun scheduleCheck(context: Context, at: Long) {
            // Inexact idle-aware wakeup supplements the service loop. No new exact-alarm permission request.
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, alarm(context))
        }
    }
}

class OrbisSentinelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LcExternalRecoveryGate.isAllowed()) return
        runCatching { OrbisSentinels.open(context).reschedule() }
    }
}
