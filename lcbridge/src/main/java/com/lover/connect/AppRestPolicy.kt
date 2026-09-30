package com.lover.connect

/**
 * A point-in-time, confirmed continuous-use observation.
 *
 * [capturedAtMs] is wall-clock time and is only for display/auditing. All
 * continuity and freshness decisions use monotonic elapsed-realtime values.
 */
data class AppRestSnapshot(
    val packageName: String,
    val durationMinutes: Int,
    val capturedAtMs: Long,
    val capturedElapsedMs: Long,
    val generation: Long,
    val thresholdMinutes: Int,
)

/** Fresh metadata only, before any rest-reminder threshold is applied. */
data class CompanionAppUsageObservation(
    val packageName: String,
    val continuousDurationMs: Long,
    val observedAtMs: Long,
    val generation: Long,
)

/**
 * Pure state reducer for a single, continuously confirmed foreground app.
 *
 * Runtime contract:
 * - Call [observeForeground] only for a real foreground observation. Repeated
 *   observations of the same package must arrive no more than 15 seconds apart.
 * - Call [pause] for a transient system/permission/IME panel or a short unknown
 *   interval. Paused time is never counted. Returning to the same app within
 *   15 seconds resumes the session; a longer gap starts it over.
 * - Call [reset] for lock/screen-off, missing usage permission, process start,
 *   launcher/home, or an exempt chat host.
 * - [freshCheck] is only valid after Runtime has independently confirmed that
 *   the tracked package is still foreground. It must not be used to extrapolate
 *   through an unknown interval.
 *
 * No wall-clock value participates in duration arithmetic.
 */
class AppRestTracker(
    private val maxContinuationGapMs: Long = DEFAULT_MAX_CONTINUATION_GAP_MS,
    private val snapshotFreshnessMs: Long = DEFAULT_SNAPSHOT_FRESHNESS_MS,
) {
    init {
        require(maxContinuationGapMs > 0L)
        require(snapshotFreshnessMs > 0L)
    }

    private var packageName: String? = null
    private var confirmedDurationMs: Long = 0L
    private var lastConfirmedElapsedMs: Long? = null
    private var pausedAtElapsedMs: Long? = null
    private var lastEventElapsedMs: Long? = null
    private var generation: Long = 0L

    @Synchronized
    fun observeForeground(packageName: String?, elapsedMs: Long) {
        val normalized = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            pause(elapsedMs)
            return
        }
        if (AppRestPolicy.isChatPackage(normalized) || AppRestPolicy.isLauncherPackage(normalized)) {
            reset()
            return
        }
        if (AppRestPolicy.isTransientSystemPackage(normalized)) {
            pause(elapsedMs)
            return
        }

        if (elapsedMs < 0L || isClockRollback(elapsedMs)) {
            clearAndAdvanceGeneration()
            if (elapsedMs >= 0L) startSession(normalized, elapsedMs)
            return
        }

        val current = this.packageName
        if (current == null || current != normalized) {
            startSession(normalized, elapsedMs)
            return
        }

        val pauseStarted = pausedAtElapsedMs
        if (pauseStarted != null) {
            if (elapsedMs - pauseStarted <= maxContinuationGapMs) {
                pausedAtElapsedMs = null
                lastConfirmedElapsedMs = elapsedMs
                lastEventElapsedMs = elapsedMs
            } else {
                startSession(normalized, elapsedMs)
            }
            return
        }

        confirmSamePackage(elapsedMs)
    }

    /**
     * Records a positive same-package foreground confirmation.
     * Returns false when there is no resumable session or the confirmation gap
     * is too large; in that case an unsafe session is cleared.
     */
    @Synchronized
    fun freshCheck(elapsedMs: Long): Boolean {
        if (packageName == null || pausedAtElapsedMs != null || elapsedMs < 0L || isClockRollback(elapsedMs)) {
            if (elapsedMs < 0L || isClockRollback(elapsedMs)) clearAndAdvanceGeneration()
            return false
        }
        return confirmSamePackage(elapsedMs)
    }

    @Synchronized
    fun pause(elapsedMs: Long) {
        if (packageName == null) return
        if (elapsedMs < 0L || isClockRollback(elapsedMs)) {
            clearAndAdvanceGeneration()
            return
        }

        val pauseStarted = pausedAtElapsedMs
        if (pauseStarted != null) {
            if (elapsedMs - pauseStarted > maxContinuationGapMs) clearAndAdvanceGeneration()
            return
        }

        val lastConfirmed = lastConfirmedElapsedMs ?: run {
            clearAndAdvanceGeneration()
            return
        }
        val delta = elapsedMs - lastConfirmed
        if (delta > maxContinuationGapMs) {
            clearAndAdvanceGeneration()
            return
        }
        confirmedDurationMs = safeAdd(confirmedDurationMs, delta)
        lastConfirmedElapsedMs = elapsedMs
        pausedAtElapsedMs = elapsedMs
        lastEventElapsedMs = elapsedMs
        advanceGeneration() // Invalidates a snapshot taken before the pause.
    }

    @Synchronized
    fun reset() {
        clearAndAdvanceGeneration()
    }

    @Synchronized
    fun observation(elapsedMs: Long, wallTimeMs: Long): CompanionAppUsageObservation? {
        val pkg = packageName ?: return null
        val last = lastConfirmedElapsedMs ?: return null
        if (pausedAtElapsedMs != null || wallTimeMs <= 0L || elapsedMs < last ||
            elapsedMs - last > maxContinuationGapMs) return null
        // Do not extrapolate missing sampling time into a verified duration.
        return CompanionAppUsageObservation(pkg, confirmedDurationMs, wallTimeMs, generation)
    }

    @Synchronized
    fun snapshot(
        elapsedMs: Long,
        wallTimeMs: Long,
        thresholdMinutes: Int = AppRestPolicy.DEFAULT_THRESHOLD_MINUTES,
    ): AppRestSnapshot? {
        val currentPackage = packageName ?: return null
        val lastConfirmed = lastConfirmedElapsedMs ?: return null
        val effectiveThreshold = thresholdMinutes.coerceAtLeast(AppRestPolicy.MIN_THRESHOLD_MINUTES)
        if (elapsedMs < 0L || wallTimeMs < 0L || pausedAtElapsedMs != null) return null
        if (elapsedMs < lastConfirmed || elapsedMs - lastConfirmed > maxContinuationGapMs) return null
        val thresholdMs = effectiveThreshold.toLong() * MILLIS_PER_MINUTE
        if (confirmedDurationMs < thresholdMs) return null

        return AppRestSnapshot(
            packageName = currentPackage,
            durationMinutes = (confirmedDurationMs / MILLIS_PER_MINUTE)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            capturedAtMs = wallTimeMs,
            capturedElapsedMs = elapsedMs,
            generation = generation,
            thresholdMinutes = effectiveThreshold,
        )
    }

    /**
     * Revalidates immediately before delivery. Runtime events that pause,
     * reset, lock the screen, or switch apps advance the generation, so a
     * snapshot captured before that transition cannot be sent afterward.
     */
    @Synchronized
    fun isCurrent(snapshot: AppRestSnapshot, nowElapsedMs: Long): Boolean {
        if (nowElapsedMs < snapshot.capturedElapsedMs) return false
        if (nowElapsedMs - snapshot.capturedElapsedMs > snapshotFreshnessMs) return false
        return pausedAtElapsedMs == null &&
            packageName == snapshot.packageName &&
            generation == snapshot.generation
    }

    private fun confirmSamePackage(elapsedMs: Long): Boolean {
        val lastConfirmed = lastConfirmedElapsedMs ?: return false
        val delta = elapsedMs - lastConfirmed
        if (delta < 0L || delta > maxContinuationGapMs) {
            clearAndAdvanceGeneration()
            return false
        }
        confirmedDurationMs = safeAdd(confirmedDurationMs, delta)
        lastConfirmedElapsedMs = elapsedMs
        lastEventElapsedMs = elapsedMs
        return true
    }

    private fun startSession(newPackageName: String, elapsedMs: Long) {
        packageName = newPackageName
        confirmedDurationMs = 0L
        lastConfirmedElapsedMs = elapsedMs
        pausedAtElapsedMs = null
        lastEventElapsedMs = elapsedMs
        advanceGeneration()
    }

    private fun isClockRollback(elapsedMs: Long): Boolean =
        lastEventElapsedMs?.let { elapsedMs < it } == true

    private fun clearAndAdvanceGeneration() {
        packageName = null
        confirmedDurationMs = 0L
        lastConfirmedElapsedMs = null
        pausedAtElapsedMs = null
        lastEventElapsedMs = null
        advanceGeneration()
    }

    private fun advanceGeneration() {
        generation = if (generation == Long.MAX_VALUE) Long.MIN_VALUE else generation + 1L
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    companion object {
        const val DEFAULT_MAX_CONTINUATION_GAP_MS = 15_000L
        const val DEFAULT_SNAPSHOT_FRESHNESS_MS = 120_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

object AppRestPolicy {
    const val DEFAULT_THRESHOLD_MINUTES = 60
    const val MIN_THRESHOLD_MINUTES = 60

    private val chatPackages = setOf(
        "me.rerere.rikkahub",
        "com.lover.connect",
        "org.orbis.agent",
        "org.orbis.agent.dev",
    )

    private val launcherPackages = setOf(
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.bbk.launcher2",
        "com.vivo.launcher",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
    )

    private val transientSystemPackages = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.touchtype.swiftkey",
    )

    fun isChatPackage(packageName: String?): Boolean = normalized(packageName) in chatPackages

    fun isLauncherPackage(packageName: String?): Boolean = normalized(packageName) in launcherPackages

    fun isTransientSystemPackage(packageName: String?): Boolean {
        val packageValue = normalized(packageName)
        return packageValue in transientSystemPackages ||
            packageValue.endsWith(".permissioncontroller")
    }

    /** Only explicit package identities are excluded; no broad SYSTEM-flag rule is used. */
    fun isExcludedPackage(packageName: String?): Boolean =
        normalized(packageName).let { it.isBlank() || it == "android" } ||
            isChatPackage(packageName) ||
            isLauncherPackage(packageName) ||
            isTransientSystemPackage(packageName)

    private fun normalized(packageName: String?): String =
        packageName?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
}

enum class SentinelDelivery {
    DELIVERED,
    SUPPRESSED,
    UNAVAILABLE,
    REJECTED,
    UNCERTAIN,
}

object DeliveryPolicy {
    /** HTTP was attempted. A missing status or transport exception is uncertain. */
    fun fromHttpStatus(code: Int?): SentinelDelivery = when {
        code == null -> SentinelDelivery.UNCERTAIN
        code in 200..299 -> SentinelDelivery.DELIVERED
        code == 429 -> SentinelDelivery.SUPPRESSED
        code in 400..499 -> SentinelDelivery.REJECTED
        else -> SentinelDelivery.UNCERTAIN
    }

    /** UNAVAILABLE is reserved for a pre-request disabled/unconfigured sentinel. */
    fun preflight(enabled: Boolean, configured: Boolean): SentinelDelivery? =
        if (!enabled || !configured) SentinelDelivery.UNAVAILABLE else null

    fun fromException(): SentinelDelivery = SentinelDelivery.UNCERTAIN

    /** Never bypass a 429, rejection, 5xx, or uncertain network result locally. */
    fun shouldUseLocalFallback(delivery: SentinelDelivery): Boolean =
        delivery == SentinelDelivery.UNAVAILABLE
}
