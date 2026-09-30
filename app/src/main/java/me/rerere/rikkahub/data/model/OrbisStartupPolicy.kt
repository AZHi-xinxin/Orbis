package me.rerere.rikkahub.data.model

import java.util.concurrent.atomic.AtomicBoolean

internal const val ORBIS_STARTUP_TIMEOUT_MS = 7000L
internal const val ORBIS_STARTUP_SLOW_MS = 2500L

/** Claim once at Activity creation, including special entrances that do not show the cover.
 * No Activity destruction, background return or animation callback resets this process gate. */
internal class OrbisStartupOnceGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

internal object OrbisStartupProcess {
    val gate = OrbisStartupOnceGate()
}

internal enum class OrbisStartupExit { READY, TIMEOUT, LEAVE }

/** The caller supplies monotonic elapsed time; animation progress is deliberately not an input. */
internal fun orbisStartupExit(
    settingsReady: Boolean,
    chatReady: Boolean,
    elapsedMillis: Long,
    leave: Boolean,
): OrbisStartupExit? = when {
    leave -> OrbisStartupExit.LEAVE
    elapsedMillis.coerceAtLeast(0L) >= ORBIS_STARTUP_TIMEOUT_MS -> OrbisStartupExit.TIMEOUT
    settingsReady && chatReady -> OrbisStartupExit.READY
    else -> null
}

internal fun orbisStartupSlow(elapsedMillis: Long): Boolean =
    elapsedMillis.coerceAtLeast(0L) >= ORBIS_STARTUP_SLOW_MS

/** firstActivity must be the result of an unconditional gate claim, not a short-circuited call. */
internal fun shouldShowOrbisStartup(isDebug: Boolean, normalLaunch: Boolean, firstActivity: Boolean): Boolean =
    isDebug && normalLaunch && firstActivity

/** A settled load error must reveal the existing error UI, not wait for an unrelated owner or image. */
internal fun orbisStartupContentReady(
    settingsReady: Boolean,
    loadSettled: Boolean,
    loadFailed: Boolean,
    snapshotsCurrent: Boolean,
    assistantMatches: Boolean,
    backgroundSettled: Boolean,
    laidOut: Boolean,
): Boolean = settingsReady && loadSettled && snapshotsCurrent && laidOut &&
    (loadFailed || (assistantMatches && backgroundSettled))
