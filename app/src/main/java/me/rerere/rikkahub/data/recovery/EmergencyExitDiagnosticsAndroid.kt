package me.rerere.rikkahub.data.recovery

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import me.rerere.rikkahub.BuildConfig

/** Call off the UI thread. Read-only, local-only; does not initialize app storage or recovery. */
fun collectEmergencyExitDiagnostics(context: Context): String {
    val failures = mutableListOf<String>()
    val pageSize = diagnosticRead("page_size", failures) { Os.sysconf(OsConstants._SC_PAGESIZE) }
    val currentProcess = if (Build.VERSION.SDK_INT >= 28) {
        diagnosticRead("collector_process", failures) { Application.getProcessName() }
    } else null
    val history = if (Build.VERSION.SDK_INT >= 30) {
        collectExitHistory(context, failures)
    } else EmergencyExitHistory(EmergencyExitHistoryState.UNSUPPORTED)
    return formatEmergencyExitDiagnostics(
        EmergencyExitDiagnosticsSnapshot(
            packageName = context.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(),
            buildType = BuildConfig.BUILD_TYPE,
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            pageSizeBytes = pageSize,
            collectedAtMillis = System.currentTimeMillis(),
            currentProcessName = currentProcess,
            state = if (history.state == EmergencyExitHistoryState.COLLECTED && failures.isNotEmpty()) {
                EmergencyExitHistoryState.PARTIAL
            } else history.state,
            records = history.records,
            failures = failures,
            unreadableRecords = history.unreadableRecords,
        )
    )
}

private data class EmergencyExitHistory(
    val state: EmergencyExitHistoryState,
    val records: List<EmergencyExitRecord> = emptyList(),
    val unreadableRecords: Int = 0,
)

@RequiresApi(30)
private fun collectExitHistory(context: Context, failures: MutableList<String>): EmergencyExitHistory {
    val manager = diagnosticRead("activity_manager", failures) {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    } ?: return EmergencyExitHistory(EmergencyExitHistoryState.SERVICE_UNAVAILABLE)
    val raw = try {
        manager.getHistoricalProcessExitReasons(context.packageName, 0, EMERGENCY_EXIT_HISTORY_LIMIT)
    } catch (error: SecurityException) {
        failures += "history:SecurityException"
        return EmergencyExitHistory(EmergencyExitHistoryState.PERMISSION_DENIED)
    } catch (error: Exception) {
        failures += diagnosticFailure("history", error)
        return EmergencyExitHistory(EmergencyExitHistoryState.FAILED)
    } catch (error: LinkageError) {
        failures += diagnosticFailure("history", error)
        return EmergencyExitHistory(EmergencyExitHistoryState.FAILED)
    }
    var unreadable = 0
    val records = raw.take(EMERGENCY_EXIT_HISTORY_LIMIT).mapNotNull { item ->
        diagnosticRead("exit_record", failures) {
            // Do not call getDescription(), getTraceInputStream() or getProcessStateSummary().
            EmergencyExitRecord(
                timestampMillis = item.timestamp,
                processName = item.processName,
                reason = item.reason,
                status = item.status,
                importance = item.importance,
                pssKb = item.pss,
                rssKb = item.rss,
            )
        }.also { if (it == null) unreadable++ }
    }
    return EmergencyExitHistory(
        state = if (unreadable == 0) EmergencyExitHistoryState.COLLECTED else EmergencyExitHistoryState.PARTIAL,
        records = records,
        unreadableRecords = unreadable,
    )
}

private inline fun <T> diagnosticRead(stage: String, failures: MutableList<String>, read: () -> T): T? = try {
    read()
} catch (error: Exception) {
    failures += diagnosticFailure(stage, error)
    null
} catch (error: LinkageError) {
    failures += diagnosticFailure(stage, error)
    null
}

private fun diagnosticFailure(stage: String, error: Throwable): String =
    "$stage:${error.javaClass.simpleName}"
