package me.rerere.rikkahub.data.orbis.companiontools

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lover.connect.LCAccessibilityService
import com.lover.connect.LcExternalRecoveryGate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.utils.CrashHandler
import java.lang.ref.WeakReference

/**
 * Observes an existing accessibility grant; never writes secure settings, binds the
 * service, starts an Activity from the background, or requests overlay permission.
 * A force-stopped/killed app cannot monitor anything. The next process startup
 * checks again, allowing Android time to reconnect before reporting an outage.
 */
internal class AccessibilityHealthMonitor private constructor(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    companion object {
        private var instance: AccessibilityHealthMonitor? = null
        private const val CHANNEL = "orbis_accessibility_health"
        private const val NOTIFICATION_ID = 0x0B150A11
        private const val CHECK_INTERVAL_MILLIS = 5_000L

        fun start(app: Application) {
            if (instance != null || !LcExternalRecoveryGate.isAllowed()) return
            instance = AccessibilityHealthMonitor(app).also { it.startObserving() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = app.getSharedPreferences("orbis_accessibility_health", Context.MODE_PRIVATE)
    private val diagnostics = app.getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
    private val policy = AccessibilityHealthPolicy(
        AccessibilityHealthPolicy.State(
            everEnabled = preferences.getBoolean("ever_enabled", false) ||
                diagnostics.getLong("accessibility_connected_at", 0L) > 0L,
            outageActive = preferences.getBoolean("outage_active", false),
            foregroundShown = preferences.getBoolean("foreground_shown", false),
            notificationPosted = preferences.getBoolean("notification_posted", false),
        )
    )
    private var foregroundActivity = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val check = Runnable { checkNow() }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { requestCheck() }
    }
    private val diagnosticListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "accessibility_connected" || key == "accessibility_connected_at") requestCheck()
    }

    private fun startObserving() {
        app.registerActivityLifecycleCallbacks(this)
        diagnostics.registerOnSharedPreferenceChangeListener(diagnosticListener)
        // Observing can be restricted by a vendor; polling remains a read-only fallback.
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                settingsObserver,
            )
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                settingsObserver,
            )
        }
        requestCheck()
    }

    private fun requestCheck() {
        handler.removeCallbacks(check)
        handler.post(check)
    }

    private fun readSample(): AccessibilityHealthPolicy.Sample? = runCatching {
        val service = ComponentName(app, LCAccessibilityService::class.java)
        val listed = Settings.Secure.getString(
            app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == service }
        val enabled = listed && Settings.Secure.getInt(
            app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
        ) == 1
        AccessibilityHealthPolicy.Sample(enabled, LCAccessibilityService.instance != null)
    }.getOrNull()

    private fun checkNow() {
        handler.removeCallbacks(check)
        try {
            if (!LcExternalRecoveryGate.isAllowed() || CrashHandler.hasCrashed(app)) return
            val sample = readSample() ?: return
            val before = policy.state
            policy.observe(sample, SystemClock.elapsedRealtime())
            persistIfChanged(before)
            if (sample.enabledInSettings && sample.connected) {
                dismissDialog()
                notifications.cancel(NOTIFICATION_ID)
                return
            }
            val activity = foregroundActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            if (activity != null && policy.shouldShowForeground()) {
                showDialog(activity)
            } else if (activity == null && policy.shouldPostNotification()) {
                postNotification()
            }
        } catch (error: Exception) {
            // Diagnostics are optional and must never make normal chat crash.
            Log.w("OrbisAccessibility", "Health reminder unavailable: ${error.javaClass.simpleName}")
        } finally {
            // This is not a keep-alive timer: it runs only while Android keeps the
            // process alive and awake; no alarm, wake lock, or foreground service.
            handler.postDelayed(check, CHECK_INTERVAL_MILLIS)
        }
    }

    private fun message(): String = when (policy.issue) {
        AccessibilityHealthPolicy.Issue.SETTING_DISABLED ->
            "系统中的「Orbis · 屏幕观察与已授权操作」目前已关闭，相关屏幕观察和应用操作暂不可用。\n\n" +
                "如果还需要这些功能，请前往无障碍设置重新开启。其他聊天和不依赖无障碍的工具不受此提醒影响。" +
                "如果是你主动关闭的，选择「稍后」即可，本次不会反复提醒。"
        else -> "Orbis 的无障碍开关仍开着，但服务目前没有连接，相关屏幕观察和应用操作暂不可用。\n\n" +
            "可以前往无障碍设置检查；若仍未恢复，关闭后重新开启「Orbis · 屏幕观察与已授权操作」。" +
            "其他聊天和不依赖无障碍的工具不受此提醒影响。选择「稍后」后，本次不会反复提醒。"
    }

    private fun showDialog(activity: Activity) {
        if (dialog?.isShowing == true) return
        runCatching {
            val shown = AlertDialog.Builder(activity)
                .setTitle("Orbis 无障碍连接暂不可用")
                .setMessage(message())
                .setPositiveButton("前往设置") { _, _ ->
                    runCatching { activity.startActivity(settingsIntent()) }
                }
                .setNegativeButton("稍后", null)
                .create()
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
            dialog = shown
            val before = policy.state
            policy.foregroundWasShown()
            persistIfChanged(before)
            notifications.cancel(NOTIFICATION_ID)
        }
    }

    private fun postNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return
        if (!notifications.areNotificationsEnabled()) return
        runCatching {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Orbis 无障碍连接提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "仅在曾启用的无障碍连接掉线时提醒；恢复后自动移除"
                    enableVibration(false)
                }
            )
            if (notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            val openSettings = PendingIntent.getActivity(
                app, NOTIFICATION_ID, settingsIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle("Orbis 无障碍连接暂不可用")
                .setContentText("点此检查「屏幕观察与已授权操作」，需要时重新开启")
                .setStyle(NotificationCompat.BigTextStyle().bigText(message()))
                .setContentIntent(openSettings)
                .addAction(0, "前往设置", openSettings)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            notifications.notify(NOTIFICATION_ID, notification)
            val before = policy.state
            policy.notificationWasPosted()
            persistIfChanged(before)
        }
    }

    private fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun persistIfChanged(before: AccessibilityHealthPolicy.State) {
        val state = policy.state
        if (before == state) return
        preferences.edit()
            .putBoolean("ever_enabled", state.everEnabled)
            .putBoolean("outage_active", state.outageActive)
            .putBoolean("foreground_shown", state.foregroundShown)
            .putBoolean("notification_posted", state.notificationPosted)
            .apply()
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is SafeModeActivity) return
        foregroundActivity = WeakReference(activity)
        requestCheck()
    }

    override fun onActivityPaused(activity: Activity) {
        if (foregroundActivity.get() === activity) {
            dismissDialog()
            foregroundActivity.clear()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (foregroundActivity.get() === activity) {
            dismissDialog()
            foregroundActivity.clear()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
