package me.rerere.rikkahub.data.recovery

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.activity.EmergencyBackupActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata only: never launches the real rescue UI, acquires its fence or stops any process. */
@RunWith(AndroidJUnit4::class)
class EmergencyColdEntryContractTest {
    @Suppress("DEPRECATION")
    @Test fun launcherEntryUsesNativeViewsAndAnIndependentProcessWithoutAppProviders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val name = EmergencyBackupActivity::class.java.name
        val manager = context.packageManager
        val activity = manager.getActivityInfo(ComponentName(context.packageName, name), PackageManager.GET_META_DATA)
        assertTrue(activity.enabled)
        assertTrue(activity.exported)
        assertEquals("${context.packageName}:recovery", activity.processName)
        assertEquals("${context.packageName}.recovery", activity.taskAffinity)
        assertEquals(Activity::class.java, EmergencyBackupActivity::class.java.superclass)
        val launchers = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }, 0)
        assertTrue(launchers.any { it.activityInfo.name == name })
        val packageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        assertTrue(packageInfo.providers.orEmpty().none { it.processName == activity.processName || it.multiprocess })
    }
}
