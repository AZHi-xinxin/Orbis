package me.rerere.rikkahub.service

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.accessibilityHostLaunchIntent
import me.rerere.oauth.oauthCallbackAppLaunchIntent
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.activity.EmergencyBackupActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Intent/manifest checks only: never starts an Activity, service, recovery fence or real chat. */
@RunWith(AndroidJUnit4::class)
class NormalAppEntryContractTest {
    @Suppress("DEPRECATION")
    @Test fun serviceEntriesResolveNormalRouteEvenWithTwoLaunchers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val normal = ComponentName(context, RouteActivity::class.java)
        val rescue = ComponentName(context, EmergencyBackupActivity::class.java)
        val manager = context.packageManager
        val launcherComponents = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }, 0).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        assertTrue(launcherComponents.contains(normal))
        assertTrue(launcherComponents.contains(rescue))

        val entries = listOf(webServerAppLaunchIntent(context), oauthCallbackAppLaunchIntent(context),
            accessibilityHostLaunchIntent(context))
        entries.forEach { intent ->
            assertEquals(normal, intent.component)
            assertEquals(normal, intent.resolveActivity(manager))
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            assertNull(intent.data)
            assertNull(intent.extras)
        }
        assertTrue(entries.last().flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertEquals("${context.packageName}:recovery", manager.getActivityInfo(rescue, 0).processName)
    }
}
