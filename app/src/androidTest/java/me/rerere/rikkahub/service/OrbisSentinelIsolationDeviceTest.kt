package me.rerere.rikkahub.service

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Direct component calls only: no host storage, alarms, notifications, or real service lifecycle. */
@RunWith(AndroidJUnit4::class)
class OrbisSentinelIsolationDeviceTest {
    private val forbiddenAccesses = AtomicInteger()
    private lateinit var forbiddenContext: Context

    @Before fun requireIsolatedProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val target = instrumentation.targetContext
        check(target.packageName == "org.orbis.agent.dev")
        assertEquals(Application::class.java, target.applicationContext.javaClass)
        assertFalse(LcExternalRecoveryGate.isAllowed())
        // A cached owner could bypass our rejecting context if an entry guard regressed.
        // Fail before component calls; never reset a singleton that may own private host state.
        assertNull("The fixture must not inherit an existing sentinel owner", sentinelOwner())

        // No fallback to targetContext: an unexpected access cannot escape into real host state.
        forbiddenContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = forbiddenAccess()
            override fun getPackageName(): String = forbiddenAccess()
            override fun getFilesDir(): File = forbiddenAccess()
            override fun getCacheDir(): File = forbiddenAccess()
            override fun getNoBackupFilesDir(): File = forbiddenAccess()
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbiddenAccess()
            override fun getSystemService(name: String): Any? = forbiddenAccess()
            override fun getSystemServiceName(serviceClass: Class<*>): String? = forbiddenAccess()
            override fun startService(service: Intent): ComponentName? = forbiddenAccess()
            override fun startForegroundService(service: Intent): ComponentName? = forbiddenAccess()
            override fun stopService(service: Intent): Boolean = forbiddenAccess()
        }
    }

    @After fun assertNoExternalEffects() {
        assertEquals("No host storage or Android service access is allowed", 0, forbiddenAccesses.get())
        assertFalse("The test must not reopen external recovery", LcExternalRecoveryGate.isAllowed())
        assertNull("The test must not initialize a sentinel owner", sentinelOwner())
    }

    @Test fun bootBroadcastDoesNotOpenSentinelState() {
        receive(Intent.ACTION_BOOT_COMPLETED)
    }

    @Test fun packageReplacedBroadcastDoesNotOpenSentinelState() {
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    @Test fun pendingAlarmBroadcastDoesNotOpenSentinelState() {
        receive("org.orbis.sentinel.CHECK")
    }

    @Test fun unrelatedBroadcastDoesNotOpenSentinelState() {
        receive("test.synthetic.UNRELATED")
    }

    @Test fun startHelperDoesNotStartForegroundService() {
        assertFalse(OrbisSentinelService.start(forbiddenContext))
    }

    @Test fun stopHelperDoesNotCancelHostAlarmsOrStopService() {
        OrbisSentinelService.stop(forbiddenContext)
    }

    @Test fun stickyRestartIsRejectedWithoutOpeningStateIncludingDestruction() {
        assertRejectedStart(null)
    }

    @Test fun pauseCommandCannotChangePersistedHumanSetting() {
        assertRejectedStart(Intent("org.orbis.sentinel.PAUSE"))
    }

    @Test fun explicitStartIsRejectedWithoutOpeningStateIncludingDestruction() {
        assertRejectedStart(Intent("test.synthetic.START"))
    }

    @Test fun destructionWithoutStartDoesNotOpenSentinelState() {
        newUnregisteredService().onDestroy()
    }

    private fun receive(action: String) {
        OrbisSentinelReceiver().onReceive(forbiddenContext, Intent(action))
    }

    private fun assertRejectedStart(intent: Intent?) {
        val service = newUnregisteredService()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        } finally {
            service.onDestroy()
        }
    }

    private fun newUnregisteredService(): OrbisSentinelService = OrbisSentinelService().also { service ->
        // Bind the rejecting ContextWrapper(null), never Service.attach / ActivityManager / a token.
        // Service's SDK override may copy content-capture options; the null-base wrapper is inert.
        // stopSelf has no ActivityManager, so no Android component is started or stopped here.
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, forbiddenContext)
    }

    private fun forbiddenAccess(): Nothing {
        forbiddenAccesses.incrementAndGet()
        throw AssertionError("An isolated sentinel component attempted external access")
    }

    private fun sentinelOwner(): Any? = OrbisSentinels::class.java.getDeclaredField("instance").apply {
        isAccessible = true
    }.get(null)
}
