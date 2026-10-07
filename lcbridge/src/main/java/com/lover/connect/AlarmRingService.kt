package com.lover.connect

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.*
import androidx.core.app.NotificationCompat

class AlarmRingService : Service() {

    private val ringtonePlayer by lazy { OrbisRingtonePlayer(this) }
    private var vibrator: Vibrator? = null
    private var recordId: String? = null
    private val vibrationHandler by lazy { Handler(Looper.getMainLooper()) }
    private val vibrationSession by lazy {
        CompanionAlarmVibrationSession(
            isAllowed = ::isVibrationAllowed,
            startVibrating = ::startVibrating,
            stopVibrating = {
                runCatching { vibrator?.cancel() }
                vibrator = null
            },
            scheduleCheck = { vibrationHandler.postDelayed(it, 1_000L) },
            cancelCheck = { vibrationHandler.removeCallbacks(it) },
        )
    }

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
            runCatching { vibrationSession.start() }
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
        vibrationSession.stop()
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

    private fun isVibrationAllowed(): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return@runCatching false
        val groupBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        companionAlarmVibrationAllowed(
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelEnabled = companionAlarmVibrationImportanceAllowed(channel.importance) && !groupBlocked,
            channelVibrationEnabled = channel.shouldVibrate(),
        )
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun startVibrating() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 800, 400, 800, 400)
        // Give Android the correct usage so its alarm/DND vibration policy can also apply.
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Keep the existing ID and the human's existing channel choices intact.
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Orbis 陪伴闹钟",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setSound(null, null)
        }
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
