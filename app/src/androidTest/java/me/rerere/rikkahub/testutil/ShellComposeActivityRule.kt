package me.rerere.rikkahub.testutil

import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only MIUI launch harness. No permission/app-op changes, force-stop, real
 * application activity, server, or user storage. AndroidComposeTestRule wraps
 * this rule so its Compose monitoring is ready before the shell launch.
 * Every launch owns only the fixed test ComponentActivity carrying its nonce.
 */
class ShellComposeActivityRule : TestRule {
    private val owned = AtomicReference<ComponentActivity?>()
    val activity: ComponentActivity
        get() = checkNotNull(owned.get()) { "The owned synthetic activity has not resumed" }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner) {
                "Shell Compose fixtures require IsolatedGenerationLoopRunner"
            }
            check(instrumentation.targetContext.packageName == TARGET_PACKAGE)
            check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
            check(owned.get() == null)

            val nonce = UUID.randomUUID().toString()
            val resumed = CountDownLatch(1)
            val destroyed = CountDownLatch(1)
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { candidate, stage ->
                if (candidate.javaClass == ComponentActivity::class.java &&
                    candidate.packageName == TARGET_PACKAGE &&
                    candidate.intent?.getStringExtra(NONCE_KEY) == nonce &&
                    candidate.application.javaClass == Application::class.java
                ) {
                    if (stage != Stage.DESTROYED) owned.set(candidate as ComponentActivity)
                    if (stage == Stage.RESUMED) resumed.countDown()
                    if (stage == Stage.DESTROYED) destroyed.countDown()
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try {
                launchWithShell(nonce, resumed)
                instrumentation.runOnMainSync {
                    check(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                }
                base.evaluate()
            } finally {
                // No ActivityScenario.close(): its auxiliary EmptyActivity
                // launch is also blocked on affected MIUI devices.
                var finishingOwned = false
                try {
                    instrumentation.runOnMainSync {
                        owned.get()?.let { current ->
                            if (current.javaClass == ComponentActivity::class.java &&
                                current.packageName == TARGET_PACKAGE &&
                                current.intent?.getStringExtra(NONCE_KEY) == nonce &&
                                !current.isFinishing && !current.isDestroyed
                            ) {
                                finishingOwned = true
                                current.finish()
                            }
                        }
                    }
                    if (finishingOwned) check(destroyed.await(5, TimeUnit.SECONDS)) {
                        "The owned synthetic activity did not finish; no broader cleanup was attempted"
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        monitor.removeLifecycleCallback(callback)
                        owned.set(null)
                    }
                }
            }
        }
    }

    private fun launchWithShell(nonce: String, resumed: CountDownLatch) {
        check(nonce.matches(Regex("[0-9a-f-]{36}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_TIMEOUT_MS
        fun remaining(): Long {
            val time = deadline - SystemClock.elapsedRealtime()
            check(time > 0) { "Synthetic shell activity did not resume within 10 seconds" }
            return time
        }
        // All shell tokens are fixed constants except a validated random UUID.
        // Do not add -S, force-stop, permission grants, or real app components.
        val command = "am start --user current -n $TARGET_PACKAGE/$TEST_ACTIVITY " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x10008000 --es $NONCE_KEY $nonce"
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-test-shell-output").apply { isDaemon = true }
        }
        try {
            val result = reader.submit<String> {
                ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
                    val bytes = ByteArray(4096)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break
                        size += count
                    }
                    String(bytes, 0, size, Charsets.UTF_8)
                }
            }.get(remaining(), TimeUnit.MILLISECONDS)
            check(!result.contains("Error:", ignoreCase = true) &&
                !result.contains("Permission Denial", ignoreCase = true)) {
                "Shell rejected the fixed synthetic activity launch"
            }
            check(resumed.await(remaining(), TimeUnit.MILLISECONDS)) {
                "Synthetic shell activity did not resume within 10 seconds"
            }
        } finally {
            runCatching { output.close() }
            reader.shutdownNow()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "org.orbis.agent.dev"
        const val TEST_ACTIVITY = "androidx.activity.ComponentActivity"
        const val NONCE_KEY = "orbis_synthetic_compose_nonce"
        const val LAUNCH_TIMEOUT_MS = 10_000L
    }
}

fun createShellComposeRule(): AndroidComposeTestRule<ShellComposeActivityRule, ComponentActivity> =
    AndroidComposeTestRule(activityRule = ShellComposeActivityRule(), activityProvider = { it.activity })
