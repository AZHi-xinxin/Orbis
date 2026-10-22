package me.rerere.rikkahub.data.recovery

import java.time.Instant

internal const val EMERGENCY_EXIT_HISTORY_LIMIT = 16

internal enum class EmergencyExitHistoryState {
    COLLECTED,
    PARTIAL,
    UNSUPPORTED,
    PERMISSION_DENIED,
    SERVICE_UNAVAILABLE,
    FAILED,
}

/** Safe metadata only. Description, trace and process state summaries deliberately have no fields. */
internal data class EmergencyExitRecord(
    val timestampMillis: Long,
    val processName: String?,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
)

internal data class EmergencyExitDiagnosticsSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val buildType: String,
    val sdk: Int,
    val supportedAbis: List<String>,
    val pageSizeBytes: Long?,
    val collectedAtMillis: Long,
    val currentProcessName: String?,
    val state: EmergencyExitHistoryState,
    val records: List<EmergencyExitRecord> = emptyList(),
    val failures: List<String> = emptyList(),
    val unreadableRecords: Int = 0,
)

/** Pure formatter: never accepts throwable messages or system-provided free-form descriptions. */
internal fun formatEmergencyExitDiagnostics(snapshot: EmergencyExitDiagnosticsSnapshot): String {
    val records = snapshot.records.asSequence()
        .filter { isEmergencyExitPackageProcess(snapshot.packageName, it.processName) }
        .sortedByDescending { it.timestampMillis }
        .take(EMERGENCY_EXIT_HISTORY_LIMIT)
        .toList()
    return buildString {
        appendLine("Orbis system exit metadata v1")
        appendLine("scope=this_package; source=Android historical process exits; newest_first=true")
        appendLine("collected_at_epoch_ms=${snapshot.collectedAtMillis}")
        appendLine("collected_at_utc=${exitTimestamp(snapshot.collectedAtMillis)}")
        appendLine("package=${safeDiagnosticToken(snapshot.packageName)}")
        appendLine("installed_version=${safeDiagnosticToken(snapshot.versionName)}")
        appendLine("installed_version_code=${safeDiagnosticToken(snapshot.versionCode)}")
        appendLine("installed_build_type=${safeDiagnosticToken(snapshot.buildType)}")
        appendLine("sdk=${snapshot.sdk}")
        appendLine("abis=${snapshot.supportedAbis.take(8).joinToString(",") { safeAbi(it) }.ifEmpty { "unknown" }}")
        appendLine("page_size_bytes=${snapshot.pageSizeBytes?.takeIf { it > 0 } ?: "unknown"}")
        appendLine("collector_process=${safeExitProcess(snapshot.packageName, snapshot.currentProcessName)}")
        appendLine("history_state=${snapshot.state.name.lowercase()}")
        appendLine("requested_max=$EMERGENCY_EXIT_HISTORY_LIMIT; records_returned=${records.size}; unreadable_records=${snapshot.unreadableRecords.coerceAtLeast(0)}")
        appendLine("failures=${snapshot.failures.take(16).joinToString(",") { safeFailure(it) }.ifEmpty { "none" }}")
        appendLine("history_is_bounded=true; empty_history_does_not_rule_out_crash=true")
        appendLine("installed_version_is_not_the_version_at_each_exit=true")
        appendLine("timestamps_must_be_compared_with_incident_and_backup_actions=true")
        records.forEachIndexed { index, entry ->
            appendLine()
            appendLine("exit[$index]")
            appendLine("timestamp_epoch_ms=${entry.timestampMillis}; timestamp_utc=${exitTimestamp(entry.timestampMillis)}")
            appendLine("process=${safeExitProcess(snapshot.packageName, entry.processName)}")
            appendLine("reason=${entry.reason}; reason_name=${exitReasonName(entry.reason)}")
            appendLine("status=${entry.status}; importance=${entry.importance}; pss_kb=${entry.pssKb}; rss_kb=${entry.rssKb}")
        }
    }
}

internal fun isEmergencyExitPackageProcess(packageName: String, processName: String?): Boolean =
    processName == packageName || processName?.startsWith("$packageName:") == true

private fun safeExitProcess(packageName: String, processName: String?): String = when {
    processName == packageName -> "main"
    processName == "$packageName:recovery" -> "recovery"
    isEmergencyExitPackageProcess(packageName, processName) -> "other_app_process_redacted"
    else -> "unknown_or_outside_package"
}

private fun safeDiagnosticToken(value: String): String =
    value.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: "redacted"

private fun safeAbi(value: String): String = when (value) {
    "arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64" -> value
    else -> "unknown"
}

private fun safeFailure(value: String): String = value.takeIf {
    it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_]+:[A-Za-z0-9_]+"))
} ?: "failure:redacted"

private fun exitTimestamp(value: Long): String =
    if (value > 0) Instant.ofEpochMilli(value).toString() else "unknown"

/** Android ApplicationExitInfo reason values, kept numeric as well for future platform reasons. */
private fun exitReasonName(reason: Int): String = when (reason) {
    0 -> "unknown"
    1 -> "exit_self"
    2 -> "signaled"
    3 -> "low_memory"
    4 -> "java_crash"
    5 -> "native_crash"
    6 -> "anr"
    7 -> "initialization_failure"
    8 -> "permission_change"
    9 -> "excessive_resource_usage"
    10 -> "user_requested"
    11 -> "user_stopped"
    12 -> "dependency_died"
    13 -> "other"
    14 -> "freezer"
    15 -> "package_state_change"
    16 -> "package_updated"
    else -> "unrecognized"
}
