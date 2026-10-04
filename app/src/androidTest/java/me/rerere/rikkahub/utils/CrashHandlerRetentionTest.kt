package me.rerere.rikkahub.utils

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Never installs the process crash handler or touches the target app's real crash preferences. */
@RunWith(AndroidJUnit4::class)
class CrashHandlerRetentionTest {
    @Test
    fun failedAcknowledgementRetainsInMemoryRecoveryGateAndReport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        val testContext = instrumentation.targetContext
        val namespace = "crash-retention-fixture-${UUID.randomUUID()}"
        val real = testContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        var failNextCommit = true
        val preferences = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                        editor.putBoolean(key, value)
                    }
                    override fun commit(): Boolean {
                        val persisted = editor.commit()
                        // Simulate the important failure contract: the in-memory edit happened.
                        return if (failNextCommit) { failNextCommit = false; false } else persisted
                    }
                }
            }
        }
        val context = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "crash_handler")
                return preferences
            }
        }
        try {
            assertTrue(real.edit().putBoolean("crashed", true).putString("stacktrace", "retained").commit())
            assertFalse(CrashHandler.acknowledgeCrashed(context))
            assertTrue(CrashHandler.hasCrashed(context))
            assertEquals("retained", CrashHandler.getStackTrace(context))
            assertTrue(CrashHandler.acknowledgeCrashed(context))
            assertFalse(CrashHandler.hasCrashed(context))
            assertEquals("retained", CrashHandler.getStackTrace(context))
        } finally {
            check(testContext.deleteSharedPreferences(namespace))
        }
    }

    @Test
    fun readingAndAcknowledgingCrashRetainsReportAcrossRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        val testContext = instrumentation.targetContext
        val namespace = "crash-retention-fixture-${UUID.randomUUID()}"
        val preferences = testContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        fun wrappedContext(): Context = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "crash_handler")
                return preferences
            }
        }
        try {
            val report = "Orbis fixture\nThread: main\nSyntheticException: retained"
            assertTrue(preferences.edit().putBoolean("crashed", true).putString("stacktrace", report).commit())
            val firstActivity = wrappedContext()
            assertTrue(CrashHandler.hasCrashed(firstActivity))
            assertEquals(report, CrashHandler.getStackTrace(firstActivity))
            val recreatedActivity = wrappedContext()
            assertTrue(CrashHandler.hasCrashed(recreatedActivity))
            assertEquals(report, CrashHandler.getStackTrace(recreatedActivity))
            assertTrue(CrashHandler.acknowledgeCrashed(recreatedActivity))
            assertFalse(CrashHandler.hasCrashed(wrappedContext()))
            assertEquals(report, CrashHandler.getStackTrace(wrappedContext()))
            assertTrue(CrashHandler.acknowledgeCrashed(wrappedContext()))
            assertEquals(report, CrashHandler.getStackTrace(wrappedContext()))
        } finally {
            check(testContext.deleteSharedPreferences(namespace))
        }
    }
}
