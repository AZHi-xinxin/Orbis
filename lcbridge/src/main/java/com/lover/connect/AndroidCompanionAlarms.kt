package com.lover.connect

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.Executors

/** No HTTP, companion service startup, permission dialog or automatic retry in a tool query. */
class AndroidCompanionAlarms(private val context: Context) {
    private val store = CompanionAlarmStore(context.filesDir)
    private val controller = CompanionAlarmController(store, AndroidAlarmGateway(context))

    fun query(args: JSONObject): String = controller.query(args.optBoolean("include_history", true), args.optInt("limit", 30), args.optInt("offset", 0)).toString()
    fun set(args: JSONObject): String = controller.set(args.getInt("hour"), args.getInt("minute"),
        args.optString("message", "Orbis 陪伴闹钟")).toString()
    fun cancel(args: JSONObject): String = controller.cancel(args.getInt("hour"), args.getInt("minute")).toString()
    fun receive(id: String): CompanionAlarmRecord? = controller.receive(id)
    fun record(id: String?, state: String, reason: String) {
        if (id == null) return
        synchronized(CompanionAlarmStore.LOCK) {
            val current = store.records().firstOrNull { it.id == id } ?: return
            // A late service teardown must not overwrite a newer cancellation/replacement receipt.
            if (current.status in setOf("cancelled", "superseded", "cancel_unknown")) return
            store.update(id, state, System.currentTimeMillis(), reason)
        }
    }

    companion object {
        const val EXTRA_ID = "orbis_alarm_id"
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "orbis-alarm-restore").apply { isDaemon = true } }

        fun restoreAsync(context: Context, finished: () -> Unit = {}) {
            if (!LcExternalRecoveryGate.isAllowed()) { finished(); return }
            val appContext = context.applicationContext
            executor.execute {
                try {
                    if (LcExternalRecoveryGate.isAllowed()) {
                        CompanionAlarmController(CompanionAlarmStore(appContext.filesDir), AndroidAlarmGateway(appContext)).restore()
                    }
                } catch (_: Exception) {
                    android.util.Log.w("OrbisAlarms", "Alarm ledger restore failed; existing data retained")
                } finally { finished() }
            }
        }
    }
}

private class AndroidAlarmGateway(private val context: Context) : CompanionAlarmGateway {
    private val manager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun intent() = Intent(context, AlarmReceiver::class.java)

    override fun schedule(record: CompanionAlarmRecord) {
        check(LcExternalRecoveryGate.isAllowed()) { "isolated_process" }
        check(record.triggerAt > System.currentTimeMillis()) { "alarm_already_due" }
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) throw SecurityException("exact_alarm_permission_required")
        // Keep the existing component + HHmm identity, so replacing an old alarm cannot double-ring.
        val pending = PendingIntent.getBroadcast(context, record.hour * 100 + record.minute,
            intent().putExtra("message", record.message).putExtra(AndroidCompanionAlarms.EXTRA_ID, record.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAt, pending)
    }

    override fun cancel(hour: Int, minute: Int): Boolean {
        check(LcExternalRecoveryGate.isAllowed()) { "isolated_process" }
        val pending = PendingIntent.getBroadcast(context, hour * 100 + minute, intent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return false
        manager.cancel(pending)
        pending.cancel()
        return true
    }

    override fun tokenExists(record: CompanionAlarmRecord): Boolean = PendingIntent.getBroadcast(
        context, record.hour * 100 + record.minute, intent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    override fun health(): JSONObject {
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val channel = notifications.getNotificationChannel("lc_alarm")
        return JSONObject().put("can_schedule_exact", Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
            .put("notifications_enabled", notifications.areNotificationsEnabled())
            .put("alarm_channel_exists", channel != null)
            .put("alarm_channel_enabled", channel?.let { it.importance != NotificationManager.IMPORTANCE_NONE } ?: JSONObject.NULL)
            .put("alarm_volume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
            .put("alarm_volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
            .put("live_playback_alarm_id", AlarmRingService.activeAlarmId ?: JSONObject.NULL)
    }
}
