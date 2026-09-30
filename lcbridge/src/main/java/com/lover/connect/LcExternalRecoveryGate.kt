package com.lover.connect

/**
 * Process-local test isolation, not a user setting or Android component toggle.
 * Normal processes always start allowed. The isolated instrumentation runner
 * closes this before creating its plain Application, before a real broadcast
 * can read host preferences or touch the host's retry alarms. Deliberately no
 * persistent state, permission change, or re-enable operation is provided.
 */
object LcExternalRecoveryGate {
    @Volatile private var allowed = true
    fun isAllowed(): Boolean = allowed
    fun disableForIsolatedTestProcess() { allowed = false }
}
