package me.rerere.rikkahub.data.orbis.sentinel

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

/** Package-only observer. Requires existing usage access; never reads app text or requests access. */
internal class OrbisSentinelUsage(private val context: Context) {
    private var wall = System.currentTimeMillis()
    private var elapsed = SystemClock.elapsedRealtime()
    private var current: String? = null
    private var sinceElapsed = elapsed

    fun sample(): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        val oldWall = wall
        val gap = mono - elapsed
        val wallGap = now - wall
        wall = now; elapsed = mono
        fun reset(): Pair<String, Long>? { current = null; sinceElapsed = mono; return null }
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED ||
                !context.getSystemService(PowerManager::class.java).isInteractive ||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                gap < 0 || gap > 15_000 || abs(wallGap - gap) > 1_000) return reset()
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(oldWall, now + 1)
                ?: return reset()
            val event = UsageEvents.Event()
            var count = 0
            while (events.hasNextEvent()) {
                if (++count > 1024) return reset()
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> if (current != event.packageName) {
                        current = event.packageName
                        sinceElapsed = mono // Conservative: no history before this confirmed sample.
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (current == event.packageName) { current = null; sinceElapsed = mono }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> { current = null; sinceElapsed = mono }
                }
            }
            current?.let { it to (mono - sinceElapsed).coerceAtLeast(0) }
        } catch (_: Exception) { reset() }
    }
}
