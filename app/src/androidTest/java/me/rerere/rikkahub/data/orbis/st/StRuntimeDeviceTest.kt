package me.rerere.rikkahub.data.orbis.st

import android.app.Application
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.workspace.WorkspaceRuntimeLease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class StRuntimeDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun actualLinuxStRemainsHealthyAfterPageLeavesAndStopsCleanly() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.packageName.endsWith(".bodylab") && context.applicationContext.javaClass == Application::class.java)
        val root = "dev128-st-synthetic"
        check(File(context.filesDir, "workspaces/$root/linux/opt/python/bin/python3").isFile)
        val host = StRuntimeHost.get(context)
        compose.setContent { MaterialTheme { Text("Isolated ST hosting test") } }
        host.setEnabled(root, true)
        try {
            host.requestStart(root, "synthetic-owner", StRuntimeSpec("/workspace/ST", "/opt/python/bin/python3", "/root/st-fixture/config.env"))
            waitFor(90_000) { host.current(root).phase in setOf("ready", "failed") }
            assertEquals(host.current(root).toString(), "ready", host.current(root).phase)
            assertTrue(runCatching { WorkspaceRuntimeLease.acquire(root) }.isFailure)
            val first = host.current(root).checkedAt!!
            inst.runOnMainSync { compose.activity.moveTaskToBack(true) }
            waitFor(20_000) { (host.current(root).checkedAt ?: 0L) > first }
            assertEquals("ready", host.current(root).phase)
            assertEquals(29873, host.current(root).gatewayPort)
            host.stop(root)
            waitFor(40_000) { !host.hasRuns() }
            assertEquals("stopped", host.current(root).phase)
            WorkspaceRuntimeLease.acquire(root).close()
            listOf(29871,29872,29873).forEach { port ->
                assertTrue(runCatching { java.net.Socket("127.0.0.1",port).use { } }.isFailure)
            }
        } finally { host.setEnabled(root, false); host.stop(root) }
    }
    private fun waitFor(timeout: Long, test: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!test()) { if(SystemClock.elapsedRealtime()>end) error("timed out"); SystemClock.sleep(100) }
    }
}
