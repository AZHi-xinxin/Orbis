package com.lover.connect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject

object McpServiceController {
    private const val CONTROL_PREFS = "lc_service_control"
    private const val KEY_ENABLED = "mcp_enabled"
    private const val DIAGNOSTICS_PREFS = "lc_diagnostics"
    const val EXTRA_START_TRIGGER = "lc_mcp_start_trigger"
    private val recoveryGate = McpRecoveryGate()

    private fun controlPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)

    fun hasStoredPreference(context: Context): Boolean =
        controlPrefs(context).contains(KEY_ENABLED)

    fun isEnabled(context: Context): Boolean =
        controlPrefs(context)
            .getBoolean(KEY_ENABLED, McpServiceLifecyclePolicy.DEFAULT_ENABLED)

    fun initializeForInteractiveLaunch(context: Context) {
        if (!LcExternalRecoveryGate.isAllowed()) return
        if (!hasStoredPreference(context)) setEnabled(context, false)
    }

    fun enableAndStart(context: Context, trigger: String = "user_start"): Boolean {
        if (!LcExternalRecoveryGate.isAllowed()) return false
        setEnabled(context, true)
        cancelLegacyRestartAlarms(context)
        return startIfEnabled(context, trigger)
    }

    fun disableAndStop(context: Context): Boolean {
        if (!LcExternalRecoveryGate.isAllowed()) return false
        val app = context.applicationContext
        setEnabled(app, false)
        recoveryGate.reset()
        cancelLegacyRestartAlarms(app)
        recordStartDecision(app, "user_stop", "disabled")
        return app.stopService(Intent(app, McpService::class.java))
    }

    fun restoreForBroadcast(context: Context, action: String?): Boolean {
        if (!LcExternalRecoveryGate.isAllowed()) return false
        if (!McpServiceLifecyclePolicy.handlesRestoreBroadcast(action)) return false
        // Embedded LC has no legacy always-on installation to migrate. Only an
        // explicit run preference in this host sandbox permits restoration.
        cancelLegacyRestartAlarms(context)
        return startIfEnabled(context, action ?: "restore_broadcast")
    }

    fun startIfEnabled(context: Context, trigger: String): Boolean {
        if (!LcExternalRecoveryGate.isAllowed()) return false
        val app = context.applicationContext
        val enabled = isEnabled(app)
        val alreadyRunning = McpService.instance != null
        if (!McpServiceLifecyclePolicy.shouldRequestStart(enabled)) {
            recordStartDecision(app, trigger, "disabled")
            return false
        }

        return try {
            val intent = Intent(app, McpService::class.java)
                .putExtra(EXTRA_START_TRIGGER, trigger)
            ContextCompat.startForegroundService(app, intent)
            recordStartDecision(
                app,
                trigger,
                if (alreadyRunning) "requested_existing" else "requested",
            )
            true
        } catch (error: RuntimeException) {
            recordStartDecision(app, trigger, "failed:${error.javaClass.simpleName}")
            recordRecoveryFailure(app, error.javaClass.simpleName)
            false
        }
    }

    /** Restore only an existing opt-in. Never grants permissions or retries a business tool. */
    fun requestRecoveryIfEnabled(context: Context, trigger: String): Boolean {
        if (!LcExternalRecoveryGate.isAllowed()) return false
        val app = context.applicationContext
        if (!isEnabled(app)) return false
        if (McpService.instance?.nativeRuntimeReady() == true) return true
        if (!recoveryGate.tryAcquire(SystemClock.elapsedRealtime(), enabled = true, ready = false)) return false
        val requested = startIfEnabled(app, trigger)
        if (!requested) recoveryGate.complete()
        return requested
    }

    internal fun onRuntimeRecoveryFinished() = recoveryGate.complete()

    /** Safe even without a running Service. Persisted history is not presented as live health. */
    fun runtimeStatusJson(context: Context): String {
        val app = context.applicationContext
        val diagnostics = app.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
        val live = McpService.instance
        val ready = live?.nativeRuntimeReady() == true
        return JSONObject().apply {
            put("checked_at_ms", System.currentTimeMillis())
            put("screen_observation_guidance", SCREEN_OBSERVATION_GUIDANCE)
            put("app_package", app.packageName)
            put("mcp_desired_enabled", isEnabled(app))
            put("mcp_service_alive", live != null)
            put("native_runtime_ready", ready)
            put("native_runtime_phase", live?.nativeRuntimePhase() ?: "not_running")
            put("mcp_server_listening", live?.nativeServerListening() == true)
            put("accessibility_connected", LCAccessibilityService.instance != null)
            for (key in listOf("mcp_created_at", "mcp_last_start_at", "mcp_destroyed_at", "mcp_task_removed_at",
                "mcp_restore_last_attempt_at", "native_runtime_ready_at", "native_runtime_failed_at",
                "native_runtime_recovery_failed_at", "mcp_server_last_error_at")) {
                put("${key}_ms", diagnostics.getLong(key, 0L))
            }
            for (key in listOf("mcp_last_start_source", "mcp_restore_last_trigger", "mcp_restore_last_result",
                "native_runtime_last_error", "native_runtime_recovery_last_error", "mcp_server_last_error")) {
                // These fields contain lifecycle trigger/result codes or exception class names only.
                put(key, diagnostics.getString(key, "").orEmpty().take(120))
            }
            put("diagnostic_history_is_live_state", false)
        }.toString()
    }

    private fun recordRecoveryFailure(context: Context, errorClass: String) {
        context.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("native_runtime_recovery_last_error", errorClass)
            .putLong("native_runtime_recovery_failed_at", System.currentTimeMillis())
            .apply()
    }

    private fun setEnabled(context: Context, enabled: Boolean) {
        check(
            controlPrefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit(),
        ) { "mcp_service_preference_not_persisted" }
    }

    /** Cancel restart alarms created by older builds before the run preference existed. */
    private fun cancelLegacyRestartAlarms(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (requestCode in 0..1) {
            val intent = Intent(app, McpService::class.java)
            val identityFlagSets = intArrayOf(
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_ONE_SHOT,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            for (pendingIntentFlags in identityFlagSets) {
                PendingIntent.getService(app, requestCode, intent, pendingIntentFlags)?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        app,
                        requestCode,
                        intent,
                        pendingIntentFlags,
                    )?.let {
                        alarmManager.cancel(it)
                        it.cancel()
                    }
                }
            }
        }
    }

    private fun hasLegacyUseEvidence(context: Context): Boolean {
        val app = context.applicationContext
        if (McpLocalSecurity.hasStoredEndpointToken(app)) return true
        if (app.getSharedPreferences("lc_config", Context.MODE_PRIVATE).all.isNotEmpty()) return true
        return java.io.File(app.filesDir, "lc_memory.json").exists()
    }

    private fun recordStartDecision(context: Context, trigger: String, result: String) {
        context.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("mcp_restore_last_attempt_at", System.currentTimeMillis())
            .putString("mcp_restore_last_trigger", trigger)
            .putString("mcp_restore_last_result", result)
            .apply()
    }
}
