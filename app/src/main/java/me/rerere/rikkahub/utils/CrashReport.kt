package me.rerere.rikkahub.utils

internal const val MAX_CRASH_REPORT_LENGTH = 32_000
private const val CRASH_REPORT_TRUNCATED = "\n\n[Report truncated; first and last sections retained]\n\n"

/** Local-only diagnostic. Does not read settings, conversation storage or provider credentials.
 * Exception messages themselves may contain private input: sharing is always a human decision. */
internal fun formatCrashReport(
    threadName: String,
    stackTrace: String,
    version: String,
    versionCode: String,
    buildType: String,
    sdk: Int,
    device: String,
    capturedAt: String,
): String {
    fun singleLine(value: String) = value.replace('\n', ' ').replace('\r', ' ').take(160)
    val header = buildString {
        appendLine("Orbis ${singleLine(version)} (${singleLine(versionCode)}), ${singleLine(buildType)}")
        appendLine("Android SDK $sdk; device: ${singleLine(device)}")
        appendLine("Captured: ${singleLine(capturedAt)}")
        appendLine("Thread: ${singleLine(threadName)}")
        appendLine("Local report: review and redact private content before sharing.")
        appendLine()
    }
    val available = MAX_CRASH_REPORT_LENGTH - header.length
    if (stackTrace.length <= available) return header + stackTrace
    // A long outer stack must not crowd out the innermost cause at the end.
    val retained = available - CRASH_REPORT_TRUNCATED.length
    val first = retained * 2 / 3
    return header + stackTrace.take(first) + CRASH_REPORT_TRUNCATED + stackTrace.takeLast(retained - first)
}
