package me.rerere.rikkahub.data.orbis.sentinel

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent

/**
 * Post only after a new durable acceptance and the caller's current human-master check.
 * True means NotificationManager accepted the post, not that it was shown, the AI woke or replied.
 * No model call, full-screen UI, permission request or fallback delivery is performed here.
 */
@SuppressLint("MissingPermission")
fun notifySentinelAccepted(context: Context, rule: OrbisSentinelRule, event: OrbisInboxEvent): Boolean {
    val spec = sentinelAcceptedNotificationSpec(rule, event) ?: return false
    val app = context.applicationContext
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            app, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED) return false
    val manager = app.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    return try {
        val light = spec.level == OrbisSentinelNotificationLevel.LIGHT
        manager.createNotificationChannel(NotificationChannel(
            spec.channelId,
            if (light) "本地哨兵 · 轻提醒" else "本地哨兵 · 强提醒",
            if (light) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "规则事件已送入固定会话；不代表 AI 已回复"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            if (light) {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        })
        // Creating an existing channel does not override the human's channel settings.
        if (manager.getNotificationChannel(spec.channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val openConversation = PendingIntent.getActivity(
            app, spec.notificationId,
            Intent(app, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Extras are not PendingIntent identity; the action isolates fixed targets too.
                action = "${app.packageName}.OPEN_SENTINEL.${spec.identity}"
                putExtra("conversationId", spec.conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(spec.title)
            .setContentText(spec.content)
            .setContentIntent(openConversation)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(if (light) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(light)
            .setWhen(spec.receivedAtMs)
            .setAutoCancel(true)
            .build()
        // A fixed rule+binding replaces its prior notification; different rules cannot collide.
        manager.notify(spec.notificationTag, spec.notificationId, notification)
        true
    } catch (_: Exception) {
        // Permission/channel changes and OEM notification failures do not undo durable acceptance.
        false
    }
}
