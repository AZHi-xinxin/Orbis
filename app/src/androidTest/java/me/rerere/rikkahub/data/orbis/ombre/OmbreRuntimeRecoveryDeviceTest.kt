package me.rerere.rikkahub.data.orbis.ombre

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Actual AtomicFile persistence and runtime lease/launch admission; service requests are captured.
 * No ST, model, user workspace, credentials or real foreground service is started by this fixture.
 */
class OmbreRuntimeRecoveryDeviceTest {
    @Test fun appRecreationRestoresSameConfigurationOnceAndManualStopPersists() = fixture { f ->
        val first = f.host()
        first.setEnabled(ROOT, true)
        first.requestStart(ROOT, OWNER, SPEC)
        assertTrue(first.saved(ROOT).resumeOnAppOpen)
        assertEquals(1, f.launches.size)
        first.stopAll(forgetRunning = false) // Android destroys the service; user did not stop ST.
        val next = f.host()
        next.restoreOnAppOpen(OWNERS)
        next.restoreOnAppOpen(OWNERS)
        assertEquals(2, f.launches.size)
        assertEquals(SPEC, next.saved(ROOT).spec)
        assertEquals("starting", next.current(ROOT).phase)
        next.stop(ROOT)
        assertFalse(next.saved(ROOT).resumeOnAppOpen)
        f.host().restoreOnAppOpen(OWNERS)
        assertEquals(2, f.launches.size)
    }

    @Test fun stopAfterProcessLossAlsoPreventsRestartAndKeepsConfig() = fixture { f ->
        val first = f.host()
        first.setEnabled(ROOT, true); first.requestStart(ROOT, OWNER, SPEC)
        first.stopAll(forgetRunning = false)
        val next = f.host()
        next.stop(ROOT) // No in-memory run exists in this fresh host.
        next.restoreOnAppOpen(OWNERS)
        assertEquals(1, f.launches.size)
        assertEquals(SPEC, next.saved(ROOT).spec)
    }

    @Test fun disabledOrReassignedWorkspacesDoNotResume() = fixture { f ->
        val first = f.host()
        first.setEnabled(ROOT, true); first.requestStart(ROOT, OWNER, SPEC)
        first.stopAll(forgetRunning = false)
        val next = f.host()
        next.restoreOnAppOpen(mapOf(ROOT to setOf("different-owner")))
        next.restoreOnAppOpen(emptyMap())
        assertEquals(1, f.launches.size)
        next.setEnabled(ROOT, false)
        next.restoreOnAppOpen(OWNERS)
        assertFalse(next.saved(ROOT).resumeOnAppOpen)
        assertEquals(1, f.launches.size)
    }

    @Test fun absentRootfsFailsVisiblyWithoutRepeatedStartsAndManualRetryCanRecover() = fixture { f ->
        val first = f.host()
        first.setEnabled(ROOT, true); first.requestStart(ROOT, OWNER, SPEC)
        first.stopAll(forgetRunning = false)
        val shell = File(f.filesDir, "workspaces/$ROOT/linux/bin/sh")
        shell.delete()
        val next = f.host()
        next.restoreOnAppOpen(OWNERS)
        assertEquals("ombre_restore_unavailable", next.current(ROOT).code)
        shell.writeText("synthetic")
        next.restoreOnAppOpen(OWNERS)
        assertEquals(1, f.launches.size) // No retry loop on rotation/background-return.
        next.restartSaved(ROOT)
        assertEquals(2, f.launches.size)
    }

    @Test fun oldRegistrationIsNotMistakenForEvidenceOfRunning() = fixture { f ->
        val file = File(f.noBackupFilesDir, "ombre-host/$ROOT.json").apply { parentFile!!.mkdirs() }
        file.writeText("""{"enabled":true,"owner":"$OWNER","spec":{"source":"/workspace/OB","python":"/opt/python/bin/python3","config":"/root/private/config.yaml"}}""")
        val host = f.host()
        host.restoreOnAppOpen(OWNERS)
        assertTrue(f.launches.isEmpty())
        host.restartSaved(ROOT)
        assertTrue(host.saved(ROOT).resumeOnAppOpen)
        assertEquals(1, f.launches.size)
    }

    @Test fun enablingAnUnregisteredWorkspaceDoesNotInstallOrStartAnything() = fixture { f ->
        val host = f.host()
        host.setEnabled(ROOT, true); host.restoreOnAppOpen(OWNERS)
        assertTrue(f.launches.isEmpty()); assertNull(host.saved(ROOT).spec)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.packageName == "org.orbis.agent.bodylab")
        val directory = Files.createTempDirectory(inst.targetContext.cacheDir.toPath(), "ombre-recovery-").toFile()
        val fixture = Fixture(inst.targetContext, directory)
        try { block(fixture) }
        finally {
            fixture.hosts.forEach { it.stopAll() }
            directory.deleteRecursively()
        }
    }

    private class Fixture(base: Context, private val directory: File) : ContextWrapper(base) {
        val launches = mutableListOf<Intent>()
        val hosts = mutableListOf<OmbreRuntimeHost>()
        init { File(filesDir, "workspaces/$ROOT/linux/bin/sh").apply {
            parentFile!!.mkdirs(); writeText("synthetic, never executed")
        } }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        override fun startForegroundService(service: Intent): ComponentName {
            launches += service
            return checkNotNull(service.component)
        }
        fun host() = OmbreRuntimeHost::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(this).also { hosts += it }
    }
    companion object {
        private const val ROOT = "ombre-recovery-synthetic"
        private const val OWNER = "synthetic-owner"
        private val OWNERS = mapOf(ROOT to setOf(OWNER))
        private val SPEC = OmbreRuntimeSpec("/workspace/OB", "/opt/python/bin/python3", "/root/private/config.yaml")
    }
}
