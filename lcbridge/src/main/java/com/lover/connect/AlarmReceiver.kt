package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LcExternalRecoveryGate.isAllowed()) return
        val alarms = AndroidCompanionAlarms(context)
        val id = intent.getStringExtra(AndroidCompanionAlarms.EXTRA_ID)
        // A legacy pre-ledger broadcast must still ring; it cannot be fabricated into a tracked plan.
        val record = if (id == null) null else try {
            alarms.receive(id) ?: return
        } catch (_: Exception) {
            android.util.Log.w("OrbisAlarms", "Alarm receipt unavailable; rejecting unverifiable generation")
            return
        }
        val message = record?.message ?: intent.getStringExtra("message") ?: "闹钟响了"
        val serviceIntent = Intent(context, AlarmRingService::class.java).apply {
            putExtra("message", message)
            putExtra(AndroidCompanionAlarms.EXTRA_ID, id)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            runCatching { alarms.record(id, "ring_failed", "service_start_failed") }
        }
    }
}
