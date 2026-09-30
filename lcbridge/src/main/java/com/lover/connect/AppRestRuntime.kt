package com.lover.connect

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

/**
 * In-process, package-only observations for the single-app rest reminder.
 *
 * Usage access must already be granted. This object never requests permissions,
 * reads window contents, writes preferences, or imports pre-process usage time.
 * Accessibility metadata may invalidate an observation, but cannot start or
 * resume timing: only a UsageEvents foreground event can do that.
 */
object AppRestRuntime {
    private const val SAMPLE_MS = 5_000L
    private const val MAX_SAMPLE_GAP_MS = 15_000L
    private const val CLOCK_TOLERANCE_MS = 250L
    private const val SAME_APP_HANDOFF_MS = 1_000L
    private const val MAX_EVENTS_PER_SAMPLE = 512

    private val lock = Any()
    private val tracker = AppRestTracker(maxContinuationGapMs = MAX_SAMPLE_GAP_MS)
    private val nonChatTracker = CompanionNonChatUsageTracker()
    private var appContext: Context? = null
    private var timer: Timer? = null
    private var cursorWallMs = 0L
    private var cursorElapsedMs = 0L
    private var confirmedPackage: String? = null
    private val eventGate = AppRestEventGate()
    private val processedEvents = mutableSetOf<PackageEvent>()
    private var lastAppliedWallMs = 0L
    private var pendingPauseAtMs: Long? = null
    private var homePackage: String? = null
    private var inputMethodPackage: String? = null
    private var explicitUsageReset = false
    @Volatile private var hostObservationEnabled = false

    private data class PackageEvent(val type: Int, val packageName: String?, val wallMs: Long)

    fun start(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        if (!eyesEnabled(app)) {
            stopLocked()
            return@synchronized
        }
        if (timer != null) return@synchronized
        appContext = app
        resetAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        timer = Timer("app-rest-observer", true).also { createdTimer ->
            // Fixed delay avoids catch-up bursts after a suspended process.
            createdTimer.schedule(object : TimerTask() {
                override fun run() = synchronized(lock) {
                    if (timer !== createdTimer) return@synchronized
                    sampleLocked(app)
                }
            }, 0L, SAMPLE_MS)
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /** The host owns rule selection; this starts only package metadata, never screenshots or alerts. */
    fun setHostObservationEnabled(context: Context, enabled: Boolean) = synchronized(lock) {
        hostObservationEnabled = enabled && LcExternalRecoveryGate.isAllowed()
        if (!LcExternalRecoveryGate.isAllowed() || !McpServiceController.isEnabled(context)) stopLocked()
        else if (eyesEnabled(context)) start(context) else stopLocked()
    }

    fun observation(context: Context): CompanionAppUsageObservation? = synchronized(lock) {
        if (timer == null) return@synchronized null
        sampleLocked(context.applicationContext)
        if (timer == null || confirmedPackage == null) return@synchronized null
        tracker.observation(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun nonChatObservation(context: Context): CompanionAppUsageObservation? = synchronized(lock) {
        if (timer == null) return@synchronized null
        sampleLocked(context.applicationContext)
        if (timer == null || confirmedPackage == null) return@synchronized null
        nonChatTracker.observation(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    /** Negative evidence that the previous usage episode ended; not an inferred foreground app. */
    fun hasConfirmedUsageReset(context: Context): Boolean = synchronized(lock) {
        if (timer == null) return@synchronized false
        sampleLocked(context.applicationContext)
        timer != null && explicitUsageReset
    }

    fun invalidate() = synchronized(lock) {
        resetAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    fun snapshot(context: Context): AppRestSnapshot? = synchronized(lock) {
        if (timer == null) return@synchronized null
        sampleLocked(context.applicationContext)
        if (timer == null || confirmedPackage == null) return@synchronized null
        tracker.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis(), threshold(context))
    }

    fun isCurrent(snapshot: AppRestSnapshot): Boolean = synchronized(lock) {
        val app = appContext ?: return@synchronized false
        if (timer == null) return@synchronized false
        sampleLocked(app)
        timer != null && threshold(app) == snapshot.thresholdMinutes && confirmedPackage == snapshot.packageName &&
            tracker.isCurrent(snapshot, SystemClock.elapsedRealtime())
    }

    private fun threshold(context: Context): Int = try {
        context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            .getInt("rest_threshold_minutes", AppRestPolicy.DEFAULT_THRESHOLD_MINUTES).coerceIn(60, 1440)
    } catch (_: Exception) { 1440 }

    /** Metadata filter only: a false result is not evidence of a foreground app. */
    fun isSystemWindow(context: Context, packageName: String?): Boolean = synchronized(lock) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return@synchronized true
        try {
            refreshExcludedPackages(context.applicationContext)
            pkg == "android" || pkg == homePackage || isTransient(pkg) ||
                AppRestPolicy.isLauncherPackage(pkg)
        } catch (_: Exception) {
            true
        }
    }

    /** Negative evidence only; the caller must not supply text or a window tree. */
    fun onWindowEvent(packageName: String?) = synchronized(lock) {
        if (timer == null) return@synchronized
        val app = appContext ?: return@synchronized
        if (!eyesEnabled(app)) {
            stopLocked()
            return@synchronized
        }
        try {
            val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
            refreshExcludedPackages(app)
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            if (pkg == null || isResetPackage(pkg, app) || isTransient(pkg)) {
                if (isResetPackage(pkg, app) || pkg == null) resetTrackers() else pauseTrackers(elapsed)
                explicitUsageReset = isResetPackage(pkg, app)
                confirmedPackage = null
                pendingPauseAtMs = null
                eventGate.blockThrough(wall)
            } else {
                explicitUsageReset = false
                eventGate.expect(pkg, wall)
                // No usage query on the accessibility callback thread. The next bounded-overlap
                // sample can accept this app's slightly earlier real resume, but never the hint alone.
                if (pkg != confirmedPackage) {
                    pauseTrackers(elapsed)
                    confirmedPackage = null
                }
            }
        } catch (_: Exception) {
            // This is also called outside the intervention handler: never break accessibility.
            resetAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }
    }

    private fun stopLocked() {
        timer?.cancel()
        timer = null
        appContext = null
        homePackage = null
        inputMethodPackage = null
        resetAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    private fun resetAt(wall: Long, elapsed: Long) {
        resetTrackers()
        confirmedPackage = null
        cursorWallMs = wall
        cursorElapsedMs = elapsed
        eventGate.reset(wall)
        processedEvents.clear()
        lastAppliedWallMs = wall
        pendingPauseAtMs = null
        explicitUsageReset = false
    }

    private fun resetTrackers() {
        tracker.reset()
        nonChatTracker.reset()
    }

    private fun pauseTrackers(elapsed: Long) {
        tracker.pause(elapsed)
        nonChatTracker.pause(elapsed)
    }

    private fun observeTrackers(pkg: String, elapsed: Long) {
        tracker.observeForeground(pkg, elapsed)
        nonChatTracker.observeForeground(pkg, elapsed)
    }

    private fun eyesEnabled(context: Context): Boolean = try {
        (hostObservationEnabled && McpServiceController.isEnabled(context)) ||
            context.getSharedPreferences("lc_config", Context.MODE_PRIVATE).getBoolean("eyes_enabled", false)
    } catch (_: Exception) {
        false
    }

    private fun hasUsageAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        // MODE_DEFAULT is not proof that usage access is granted.
        ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun screenAvailable(context: Context): Boolean = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        power?.isInteractive == true && keyguard != null && !keyguard.isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    private fun refreshExcludedPackages(context: Context) {
        // Read current selections, not application labels or installed-app contents.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackage = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        inputMethodPackage = ime?.let { ComponentName.unflattenFromString(it)?.packageName }
    }

    private fun isResetPackage(pkg: String?, context: Context): Boolean = pkg != null &&
        (pkg == context.packageName || pkg == homePackage || pkg == "android" ||
            AppRestPolicy.isChatPackage(pkg) || AppRestPolicy.isLauncherPackage(pkg))

    private fun isTransient(pkg: String?): Boolean = pkg != null &&
        (pkg == inputMethodPackage || AppRestPolicy.isTransientSystemPackage(pkg))

    private fun relevant(type: Int): Boolean = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED,
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN -> true
        else -> false
    }

    private fun sampleLocked(context: Context) {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        if (!eyesEnabled(context)) {
            stopLocked()
            return
        }
        val elapsedGap = elapsed - cursorElapsedMs
        val wallGap = wall - cursorWallMs
        if (elapsedGap < 0L || elapsedGap > MAX_SAMPLE_GAP_MS || wallGap < 0L ||
            abs(wallGap - elapsedGap) > CLOCK_TOLERANCE_MS ||
            !hasUsageAccess(context) || !screenAvailable(context)) {
            resetAt(wall, elapsed)
            return
        }
        try {
            refreshExcludedPackages(context)
            if (confirmedPackage != null && isResetPackage(confirmedPackage, context)) {
                resetAt(wall, elapsed)
                explicitUsageReset = true
                return
            }
            if (confirmedPackage != null && isTransient(confirmedPackage)) {
                pauseTrackers(elapsed)
                confirmedPackage = null
                eventGate.blockThrough(wall)
            }
            if (wall == cursorWallMs) return
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (manager == null) {
                resetAt(wall, elapsed)
                return
            }
            // Bounded overlap also catches an OEM event published slightly late with an older
            // timestamp. De-duplicate in memory; never reconstruct pre-process usage duration.
            val begin = (cursorWallMs - MAX_SAMPLE_GAP_MS).coerceAtLeast(0L)
            val events = manager.queryEvents(begin, wall)
            if (events == null) {
                resetAt(wall, elapsed)
                return
            }
            val ordered = ArrayList<PackageEvent>()
            val event = UsageEvents.Event()
            var count = 0
            while (events.hasNextEvent()) {
                if (++count > MAX_EVENTS_PER_SAMPLE || !events.getNextEvent(event)) {
                    resetAt(wall, elapsed)
                    return
                }
                if (!relevant(event.eventType)) continue
                if (event.timeStamp >= wall || event.timeStamp < begin) {
                    resetAt(wall, elapsed)
                    return
                }
                val item = PackageEvent(event.eventType,
                    event.packageName?.trim()?.takeIf { it.isNotEmpty() }, event.timeStamp)
                if (item.wallMs >= lastAppliedWallMs && processedEvents.add(item)) ordered += item
            }
            ordered.sortBy { it.wallMs }
            var index = 0
            while (index < ordered.size) {
                val current = ordered[index]
                lastAppliedWallMs = maxOf(lastAppliedWallMs, current.wallMs)
                val next = ordered.getOrNull(index + 1)
                if (current.type == UsageEvents.Event.ACTIVITY_PAUSED &&
                    current.packageName == confirmedPackage && next != null &&
                    next.type == UsageEvents.Event.ACTIVITY_RESUMED &&
                    current.packageName == next.packageName &&
                    next.wallMs - current.wallMs <= SAME_APP_HANDOFF_MS) {
                    // Adjacent activities in the same app are not an app switch.
                    index++
                } else {
                    // Small sampling-clock jitter must not move tracker time backwards.
                    val eventElapsed = if (current.wallMs < cursorWallMs) elapsed else
                        (elapsed - (wall - current.wallMs)).coerceIn(cursorElapsedMs, elapsed)
                    applyEvent(current, eventElapsed, next, context)
                }
                index++
            }
            cursorWallMs = wall
            cursorElapsedMs = elapsed
            processedEvents.removeAll { it.wallMs < begin }
            // No fresh event is necessary only while an earlier UsageEvents resume
            // remains confirmed and both the screen and usage grant are still valid.
            confirmedPackage?.let { observeTrackers(it, elapsed) }
        } catch (_: Exception) {
            // Unsupported OEM usage APIs / permission errors are unknown, never zero usage.
            resetAt(wall, elapsed)
        }
    }

    private fun applyEvent(event: PackageEvent, elapsed: Long, next: PackageEvent?, context: Context) {
        if (event.type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
            event.type == UsageEvents.Event.KEYGUARD_SHOWN) {
            resetTrackers()
            explicitUsageReset = true
            confirmedPackage = null
            eventGate.blockThrough(event.wallMs)
            pendingPauseAtMs = null
            return
        }
        if (event.type == UsageEvents.Event.ACTIVITY_PAUSED) {
            if (event.packageName != confirmedPackage) return
            pauseTrackers(elapsed)
            // A same-app activity handoff may straddle two query batches. Only a <=1s
            // handoff can continue; a real intervening app/chat/home still resets it.
            pendingPauseAtMs = if (next?.type == UsageEvents.Event.ACTIVITY_RESUMED &&
                isTransient(next.packageName)) null else event.wallMs
            confirmedPackage = null
            return
        }
        val pkg = event.packageName
        when {
            pkg == null -> {
                resetTrackers()
                explicitUsageReset = false
                confirmedPackage = null
                eventGate.blockThrough(event.wallMs)
                pendingPauseAtMs = null
            }
            isResetPackage(pkg, context) -> {
                resetTrackers()
                explicitUsageReset = true
                confirmedPackage = null
                eventGate.blockThrough(event.wallMs)
                pendingPauseAtMs = null
            }
            isTransient(pkg) -> {
                pauseTrackers(elapsed)
                explicitUsageReset = false
                confirmedPackage = null
                pendingPauseAtMs = null
                eventGate.blockThrough(event.wallMs)
            }
            else -> {
                if (!eventGate.allowsResume(pkg, event.wallMs, System.currentTimeMillis())) return
                explicitUsageReset = false
                if (pendingPauseAtMs?.let { event.wallMs - it > SAME_APP_HANDOFF_MS } == true) tracker.reset()
                pendingPauseAtMs = null
                confirmedPackage = pkg
                observeTrackers(pkg, elapsed)
            }
        }
    }
}
