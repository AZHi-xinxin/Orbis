package com.lover.connect

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock

/** Re-check system state at execution time; this adapter never opens a permission dialog. */
internal class AndroidCompanionRuntime(private val context: Context) : CompanionRuntimeGateway {
    override fun available() = McpService.instance?.nativeRuntimeReady() == true

    override fun prepare(): String? {
        if (available()) return null
        if (!McpServiceController.isEnabled(context)) return "service_disabled"
        if (!LcExternalRecoveryGate.isAllowed()) return "service_unavailable"
        // Called on the interruptible worker dispatcher, not the UI thread. Concurrent
        // callers share a throttled recovery request and only wait for runtime readiness.
        check(Looper.myLooper() != Looper.getMainLooper())
        McpServiceController.requestRecoveryIfEnabled(context, "native_tool_recovery")
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!McpServiceController.isEnabled(context)) return "service_disabled"
            if (available()) return null
            Thread.sleep(50)
        }
        return if (available()) null else "service_unavailable"
    }

    override fun authorizationRevision(name: String): String {
        val keys = when (name) {
            "configure_sentinel", "test_sentinel" -> listOf("sentinel_url", "sentinel_token", "sentinel_enabled")
            "take_screenshot" -> listOf("vision_api_url", "vision_api_key", "vision_model", "sentinel_url", "sentinel_token", "sentinel_enabled")
            else -> emptyList()
        }
        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val snapshot = keys.joinToString("\n") { "$it=${prefs.all[it]}" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override fun permissionProblem(name: String, arguments: org.json.JSONObject): String? {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (name in setOf("send_notification", "play_music", "set_alarm")) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if ((Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) || !manager.areNotificationsEnabled())
                return "notification_permission_required"
            val channel = when (name) { "play_music" -> "lc_music"; "set_alarm" -> "lc_alarm"; else -> "lc_notify" }
            if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE)
                return "notification_channel_disabled"
        }
        if (name == "set_alarm" && Build.VERSION.SDK_INT >= 31 &&
            !(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms())
            return "exact_alarm_permission_required"
        if (name == "lock_screen" && !(context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .isAdminActive(ComponentName(context, LockScreenReceiver::class.java))) return "device_admin_required"
        if (name in setOf("lock_app", "focus_rikka", "redirect_to_rikka")) {
            if (!DeviceCompatibility.activeAppInterventionsSupported()) return "device_control_unsupported"
            val disabling = (name == "focus_rikka" && !arguments.optBoolean("enabled", false)) ||
                (name == "redirect_to_rikka" && arguments.optJSONArray("package_names")?.length() == 0)
            if (!disabling && LCAccessibilityService.instance == null) return "accessibility_permission_required"
        }
        if (name == "get_steps" && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION))
            return "activity_permission_required"
        if (name == "get_now_playing" && context.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context))
            return "notification_listener_permission_required"
        if (name == "take_screenshot") {
            if (LCAccessibilityService.instance == null) return "accessibility_permission_required"
            if (Build.VERSION.SDK_INT < 30 && !ScreenCaptureService.isReady()) return "screen_capture_permission_required"
            if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) return "device_locked"
            // The active chat receives the actual screenshot. Separate Little L vision
            // credentials are only used by the unchanged legacy/periodic analysis path.
        }
        return null
    }

    override fun execute(name: String, arguments: org.json.JSONObject): String {
        if (name == "get_alarms") return AndroidCompanionAlarms(context).query(arguments)
        if (name == "get_l_service_status" && !available())
            return McpServiceController.runtimeStatusJson(context)
        return (McpService.instance ?: error("service_unavailable")).executeNativeTool(name, arguments)
    }

    override fun captureScreen(): CompanionToolResult = AndroidCompanionScreenCapture.capture(context)
}
