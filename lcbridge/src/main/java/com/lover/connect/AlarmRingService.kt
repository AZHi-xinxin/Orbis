package com.lover.connect

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat

class AlarmRingService : Service() {

    private val ringtonePlayer by lazy { OrbisRingtonePlayer(this) }
    private var vibrator: Vibrator? = null
    private var recordId: String? = null

    companion object {
        private const val CHANNEL_ID = "lc_alarm"
        private const val NOTIFICATION_ID = 9999
        const val ACTION_STOP = "com.lover.connect.STOP_ALARM"
        @Volatile var activeAlarmId: String? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LcExternalRecoveryGate.isAllowed()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) {
            releasePlayback("notification_stop")
            stopSelf()
            return START_NOT_STICKY
        }
        // A second alarm must not orphan the first looping MediaPlayer.
        releasePlayback("replaced_by_next_alarm")
        recordId = intent?.getStringExtra(AndroidCompanionAlarms.EXTRA_ID)
        val message = intent?.getStringExtra("message") ?: "闹钟响了"
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildAlarmNotification(message))
            activeAlarmId = recordId
            startRinging()
            runCatching { startVibrating() }
        } catch (_: Exception) {
            val failedId = recordId
            releasePlayback("playback_cleanup")
            runCatching { AndroidCompanionAlarms(this).record(failedId, "ring_failed", "playback_start_failed") }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayback("service_destroyed")
        super.onDestroy()
    }

    private fun record(state: String, reason: String) {
        runCatching { AndroidCompanionAlarms(this).record(recordId, state, reason) }
    }

    private fun releasePlayback(reason: String) {
        ringtonePlayer.stop()
        runCatching { vibrator?.cancel() }
        vibrator = null
        if (recordId != null) record("stopped", reason)
        recordId = null
        activeAlarmId = null
    }

    private fun startRinging() {
        ringtonePlayer.startAlarm(
            onStarted = { record("ringing", "playback_started") },
            onFailure = {
                val failedId = recordId
                releasePlayback("playback_cleanup")
                runCatching { AndroidCompanionAlarms(this@AlarmRingService).record(failedId, "ring_failed", "playback_error") }
                stopSelf()
            },
        )
    }

    private fun startVibrating() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 800, 400, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Orbis 陪伴闹钟",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setSound(null, null)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildAlarmNotification(message: String): Notification {
        val stopIntent = Intent(this, AlarmRingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orbis · 陪伴闹钟")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "关闭闹钟", stopPending)
            .build()
    }
}
