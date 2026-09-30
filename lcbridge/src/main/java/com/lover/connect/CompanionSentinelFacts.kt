package com.lover.connect

/** Real samples only. null means unknown/unavailable, never an invented zero. */
data class CompanionSentinelPhoneFacts(
    val observedAtMs: Long,
    val screenInteractive: Boolean,
    val unlocked: Boolean,
    val continuousScreenOnMs: Long?,
    val nonChatPackage: String?,
    val nonChatUsageMs: Long?,
    val usageObservedAtMs: Long?,
    val usageAccessGranted: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val isAvailable: Boolean,
    val errorCode: String? = null,
)

/** No raw provider credential, personality prompt, model reasoning, or unrelated diary text. */
data class CompanionSentinelObservation(
    val ok: Boolean,
    val observedAtMs: Long,
    val content: String? = null,
    val reason: String? = null,
    val shouldNotify: Boolean = false,
    val errorCode: String? = null,
    val images: List<CompanionToolImage> = emptyList(),
) {
    val isAvailable: Boolean get() = ok
}

/** A known ended episode is zero so a conditional rule re-arms; lack of evidence stays null. */
internal fun companionNonChatUsageDuration(
    interactive: Boolean,
    unlocked: Boolean,
    usageAccessGranted: Boolean,
    explicitReset: Boolean,
    observedDurationMs: Long?,
): Long? = when {
    !interactive || !unlocked -> 0L
    !usageAccessGranted -> null
    explicitReset -> 0L
    else -> observedDurationMs
}

/** Screen continuity is process-local and monotonic; sleep or missing samples never count as usage. */
internal class CompanionScreenContinuity(private val maxSampleGapMs: Long = 20_000L) {
    private var lastElapsedMs: Long? = null
    private var durationMs = 0L

    @Synchronized fun reset() {
        lastElapsedMs = null
        durationMs = 0L
    }

    @Synchronized fun observe(interactiveAndUnlocked: Boolean, elapsedMs: Long): Long? {
        if (!interactiveAndUnlocked || elapsedMs < 0L) {
            reset()
            return null
        }
        val last = lastElapsedMs
        if (last == null || elapsedMs < last || elapsedMs - last > maxSampleGapMs) durationMs = 0L
        else durationMs += elapsedMs - last
        lastElapsedMs = elapsedMs
        return durationMs
    }
}
