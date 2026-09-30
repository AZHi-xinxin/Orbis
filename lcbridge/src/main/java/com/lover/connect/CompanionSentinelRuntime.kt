package com.lover.connect

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/** Host-owned sampling. Installation alone performs no capture, network request, rule edit or wakeup. */
object CompanionSentinelRuntime {
    private val screen = CompanionScreenContinuity()
    @Volatile private var phoneObservationEnabled = false
    private var registeredContext: Context? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) screen.reset()
        }
    }

    @Synchronized fun setPhoneObservationEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val effective = enabled && LcExternalRecoveryGate.isAllowed() && McpServiceController.isEnabled(app)
        if (effective != phoneObservationEnabled) screen.reset()
        phoneObservationEnabled = effective
        AppRestRuntime.setHostObservationEnabled(app, effective)
        if (effective && registeredContext == null) {
            runCatching {
                val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else {
                    @Suppress("DEPRECATION")
                    app.registerReceiver(screenReceiver, filter)
                }
                registeredContext = app
            }
        } else if (!effective && registeredContext != null) {
            runCatching { registeredContext?.unregisterReceiver(screenReceiver) }
            registeredContext = null
        }
    }

    fun phoneFacts(context: Context): CompanionSentinelPhoneFacts {
        val now = System.currentTimeMillis()
        val app = context.applicationContext
        if (!LcExternalRecoveryGate.isAllowed() || !phoneObservationEnabled || !McpServiceController.isEnabled(app)) {
            screen.reset()
            return CompanionSentinelPhoneFacts(now, false, false, null, null, null, null, false,
                null, null, false, "phone_observation_disabled")
        }
        val interactive = runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(false)
        val unlocked = runCatching { !(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked }.getOrDefault(false)
        val usageGranted = runCatching {
            (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        val usage = if (usageGranted && interactive && unlocked) AppRestRuntime.nonChatObservation(app) else null
        val usageReset = usageGranted && AppRestRuntime.hasConfirmedUsageReset(app)
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return CompanionSentinelPhoneFacts(
            observedAtMs = now,
            screenInteractive = interactive,
            unlocked = unlocked,
            continuousScreenOnMs = screen.observe(interactive && unlocked, SystemClock.elapsedRealtime())
                ?: if (!interactive || !unlocked) 0L else null,
            nonChatPackage = usage?.packageName,
            nonChatUsageMs = companionNonChatUsageDuration(interactive, unlocked, usageGranted,
                usageReset, usage?.continuousDurationMs),
            usageObservedAtMs = usage?.observedAtMs,
            usageAccessGranted = usageGranted,
            batteryPercent = if (scale > 0 && level in 0..scale) level * 100 / scale else null,
            charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            isAvailable = true,
        )
    }

    /** Blocking IO: call from a worker. This does not enqueue a sentinel or invoke the main chat model. */
    fun observeScreen(context: Context): CompanionSentinelObservation {
        if (!LcExternalRecoveryGate.isAllowed() || !McpServiceController.isEnabled(context))
            return CompanionSentinelObservation(false, System.currentTimeMillis(), errorCode = "service_disabled")
        return McpService.instance?.observeScreenForHost()
            ?: CompanionSentinelObservation(false, System.currentTimeMillis(), errorCode = "service_not_connected")
    }
}
