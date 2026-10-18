package me.rerere.rikkahub.data.recovery

import android.app.Activity
import android.app.ActivityManager
import me.rerere.rikkahub.BuildConfig

/** Called only after the ordinary Application has acquired its data-safety lease. */
internal fun Activity.removeRetiredOrbisTasks() {
    // Cosmetic host cleanup must never prevent chat from opening if task metadata is unavailable.
    runCatching {
        val manager = getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        manager.appTasks.forEach { task ->
            runCatching {
                val info = task.taskInfo ?: return@runCatching
                val base = info.baseActivity ?: info.baseIntent.component
                val top = info.topActivity
                if (isRetiredOrbisTask(packageName, taskId, info.taskId,
                        base?.packageName, base?.className, top?.packageName, top?.className,
                        BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED)) {
                    task.finishAndRemoveTask()
                }
            }
        }
    }
}
