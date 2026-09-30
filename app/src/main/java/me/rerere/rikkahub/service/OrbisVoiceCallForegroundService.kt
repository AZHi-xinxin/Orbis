package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/** Android microphone/playback lifetime only. Conversation and archiving belong to the runtime. */
class OrbisVoiceCallForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "orbis_voice_call"
        private const val NOTIFICATION_ID = 2003
        private const val ACTION_START = "me.rerere.rikkahub.action.VOICE_CALL_START"
        private const val ACTION_HANG_UP = "me.rerere.rikkahub.action.VOICE_CALL_HANG_UP"
        private const val EXTRA_TOKEN = "voice_call_token"
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_MS = 10 * 60 * 1000L

        internal fun start(context: Context, token: String) {
            ContextCompat.startForegroundService(context, Intent(context, OrbisVoiceCallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
            })
        }
    }

    private val runtime by lazy { OrbisVoiceCallRuntime.get(this) }
    private var token: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renewWakeLock = object : Runnable {
        override fun run() {
            val activeToken = token ?: return
            try {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                handler.postDelayed(this, WAKE_LOCK_RENEWAL_MS)
            } catch (e: Exception) {
                runtime.serviceDestroyed(activeToken)
                stopForCall(activeToken)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音通话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "通话中的麦克风与语音播放"
                setSound(null, null)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedToken = intent?.getStringExtra(EXTRA_TOKEN)
        if (intent?.action == ACTION_HANG_UP) {
            requestedToken?.let(runtime::hangUp)
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || requestedToken == null || !runtime.acceptsService(requestedToken)) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        token = requestedToken
        try {
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    (if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(requestedToken), types)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice-call")
                    .apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
                handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEWAL_MS)
            }
            runtime.serviceReady(requestedToken, this)
        } catch (e: Exception) {
            runtime.serviceFailed(requestedToken, e.message ?: "无法获得后台录音权限")
            stopForCall(requestedToken)
        }
        return START_NOT_STICKY
    }

    internal fun refresh(expectedToken: String) {
        if (token != expectedToken) return
        // Notification permission can be revoked mid-call; this must not crash the service.
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(expectedToken))
        }
    }

    internal fun enableMicrophone(expectedToken: String): Boolean {
        if (token != expectedToken) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildNotification(expectedToken), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        }.getOrDefault(false)
    }

    internal fun stopForCall(expectedToken: String) {
        if (token != expectedToken) return
        token = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val stopped = token
        stopped?.let(runtime::serviceDestroyed)
        stopped?.let(::stopForCall)
        stopSelf(startId)
    }

    override fun onDestroy() {
        val interrupted = token
        token = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // No automatic restart or fabricated successful hangup after service/process loss.
        interrupted?.let(runtime::serviceDestroyed)
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun buildNotification(callToken: String): Notification {
        val state = runtime.callState.value
        val returnToCall = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", state.conversationId?.toString())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this, callToken.hashCode(),
            Intent(this, OrbisVoiceCallForegroundService::class.java).apply {
                action = ACTION_HANG_UP
                putExtra(EXTRA_TOKEN, callToken)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(state.title.ifBlank { "语音通话" })
            .setContentText(if (state.connectedAtMillis == null) "正在连接语音通话" else "通话中 · 点此返回")
            .setContentIntent(returnToCall)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(state.connectedAtMillis ?: System.currentTimeMillis())
            .setUsesChronometer(state.connectedAtMillis != null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", hangUp)
            .build()
    }
}
