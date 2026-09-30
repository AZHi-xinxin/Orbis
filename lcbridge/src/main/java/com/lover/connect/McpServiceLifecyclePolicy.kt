package com.lover.connect

object McpServiceLifecyclePolicy {
    const val DEFAULT_ENABLED = false
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    fun handlesRestoreBroadcast(action: String?): Boolean =
        action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED

    fun shouldRequestStart(enabled: Boolean): Boolean = enabled

    /** A restored Service shell and its onStartCommand may arrive separately. */
    fun shouldInitializeRuntime(enabled: Boolean, initialized: Boolean, initializing: Boolean): Boolean =
        enabled && !initialized && !initializing

    /**
     * Versions before 2.4.1-r2 did not persist the MCP run preference. Their
     * service was effectively always-on, so the first upgrade to this policy
     * migrates a missing preference to enabled. A clean install remains opt-in.
     */
    fun shouldMigrateLegacyEnabled(
        action: String?,
        hasStoredPreference: Boolean,
        hasLegacyUseEvidence: Boolean,
    ): Boolean =
        action == ACTION_MY_PACKAGE_REPLACED &&
            !hasStoredPreference &&
            hasLegacyUseEvidence
}

/** Process-local recovery lease. Repeated callers must not create a start storm. */
internal class McpRecoveryGate(
    private val cooldownMs: Long = 5_000L,
    private val pendingLeaseMs: Long = 10_000L,
) {
    private var requestedAt: Long? = null
    private var pending = false

    @Synchronized
    fun tryAcquire(nowElapsedMs: Long, enabled: Boolean, ready: Boolean): Boolean {
        if (!enabled || ready) return false
        val previous = requestedAt
        if (previous != null) {
            val age = nowElapsedMs - previous
            if (age >= 0 && (age < cooldownMs || (pending && age < pendingLeaseMs))) return false
        }
        requestedAt = nowElapsedMs
        pending = true
        return true
    }

    @Synchronized
    fun complete() { pending = false }

    @Synchronized
    fun reset() { requestedAt = null; pending = false }
}
