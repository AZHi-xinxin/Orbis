package me.rerere.rikkahub.data.recovery

import me.rerere.rikkahub.utils.EmergencyProcessGate

internal enum class EmergencyLauncherState {
    READY, RECOVERY_REQUESTED, PREVIOUS_CRASH, BUSINESS_BUSY, CHECK_FAILED,
}

/**
 * A short inspection only, never an acknowledgement or a request to enter/leave recovery.
 * Do not read even the crash flag until the same business fence used by Application has
 * admitted the inspection. The Android caller uses a fresh read-only disk reader. Release
 * this lease before navigation; the real business process must independently acquire its own.
 */
internal fun inspectEmergencyLauncher(
    gate: EmergencyProcessGate,
    readPreviousCrash: () -> Boolean,
): EmergencyLauncherState = try {
    if (gate.isRecoveryRequested()) {
        EmergencyLauncherState.RECOVERY_REQUESTED
    } else {
        val lease = gate.tryAcquireBusinessLease()
        if (lease == null) {
            if (gate.isRecoveryRequested()) EmergencyLauncherState.RECOVERY_REQUESTED
            else EmergencyLauncherState.BUSINESS_BUSY
        } else lease.use {
            if (readPreviousCrash()) EmergencyLauncherState.PREVIOUS_CRASH
            else EmergencyLauncherState.READY
        }
    }
} catch (_: Exception) {
    EmergencyLauncherState.CHECK_FAILED
}

internal fun isEmergencyLightweightProcessName(name: String, packageName: String): Boolean =
    name == "$packageName:recovery" || name == "$packageName:launcher"

/** This process has no app providers/services and cannot open databases or run tools. */
internal fun isEmergencyLauncherProcessName(name: String, packageName: String): Boolean =
    name == "$packageName:launcher"

/** API failure/empty results may fall back to the kernel name, never to an assumed UI process. */
internal fun resolveEmergencyProcessName(androidName: () -> String?, procName: () -> String): String {
    val primary = try { androidName() } catch (_: Exception) { null }
    if (!primary.isNullOrBlank()) return primary
    return procName().also { check(it.isNotBlank()) { "Cannot identify application process" } }
}

/**
 * Existing recovery may have a DocumentsUI picker on its task. Moving the WHOLE task forward
 * preserves that stack; starting the singleTask recovery Activity again could cancel it.
 * If task enumeration/resumption fails, do not guess and start another recovery Activity.
 */
internal fun openOrResumeEmergencyTask(
    findExistingTask: () -> (() -> Unit)?,
    openNew: () -> Unit,
): Boolean = try {
    val resume = findExistingTask()
    if (resume == null) openNew() else resume()
    true
} catch (_: Exception) {
    false
}
