package com.lover.connect

import android.app.NotificationManager

/** LOW/MIN are silent even when an older channel still retains shouldVibrate=true. */
internal fun companionAlarmVibrationImportanceAllowed(importance: Int): Boolean =
    importance >= NotificationManager.IMPORTANCE_DEFAULT

/** Active alarm vibration must not bypass an app/channel opt-out. Missing settings fail closed. */
internal fun companionAlarmVibrationAllowed(
    notificationsEnabled: Boolean,
    channelEnabled: Boolean,
    channelVibrationEnabled: Boolean,
): Boolean = notificationsEnabled && channelEnabled && channelVibrationEnabled

/** Owns exactly one loop and one permission check; stop/replacement/destruction cancels both. */
internal class CompanionAlarmVibrationSession(
    private val isAllowed: () -> Boolean,
    private val startVibrating: () -> Unit,
    private val stopVibrating: () -> Unit,
    private val scheduleCheck: (Runnable) -> Unit,
    private val cancelCheck: (Runnable) -> Unit,
) {
    private var active = false
    private val checkPermission = object : Runnable {
        override fun run() {
            if (!active) return
            if (!isAllowed()) stop() else scheduleCheck(this)
        }
    }

    fun start() {
        stop()
        if (!isAllowed()) return
        try {
            startVibrating()
            active = true
            scheduleCheck(checkPermission)
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    fun stop() {
        active = false
        cancelCheck(checkPermission)
        stopVibrating()
    }
}
