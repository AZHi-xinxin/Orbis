package me.rerere.rikkahub.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import me.rerere.rikkahub.BuildConfig
import java.time.Instant

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
                markCrashed(appContext, thread, throwable)
            } catch (_: Throwable) {
                // A full disk or diagnostic failure must not replace the original crash.
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    /** Only a human choosing to enter the app acknowledges the startup gate.
     * Keep the report across Activity recreation and another visit to recovery. */
    fun acknowledgeCrashed(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.edit().remove(KEY_CRASHED).commit()) return true
        // commit(false) may already have removed the flag from the in-process map.
        // Restore that fence too: another launcher intent must not bypass recovery.
        preferences.edit().putBoolean(KEY_CRASHED, true).commit()
        return false
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = formatCrashReport(
            threadName = thread.name,
            stackTrace = throwable.stackTraceToString(),
            version = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(),
            buildType = BuildConfig.BUILD_TYPE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            capturedAt = Instant.now().toString(),
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}
