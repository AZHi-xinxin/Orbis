package me.rerere.rikkahub.data.recovery

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.orbis.diagnostics.OrbisFaultDiagnostics
import me.rerere.rikkahub.utils.CrashHandler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly selected two-process probe, never runs in ordinary instrumentation suites. */
class FaultCrashPersistenceProbe {
    @Test fun trigger() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbis_fault_crash_probe")=="trigger")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.orbis.agent.bodylab")
        OrbisFaultDiagnostics.clear(context);OrbisFaultDiagnostics.start(context)
        CrashHandler.install(context)
        Handler(Looper.getMainLooper()).post {throw IllegalStateException("synthetic-private-key-not-for-report")}
        Thread.sleep(15000)
        fail("Synthetic uncaught crash should terminate this isolated process")
    }
    @Test fun readback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbis_fault_crash_probe")=="readback")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.orbis.agent.bodylab")
        val report=OrbisFaultDiagnostics.report(context)
        assertTrue(report.contains("UNCAUGHT_EXCEPTION"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("FaultCrashPersistenceProbe"))
        assertFalse(report.contains("synthetic-private-key-not-for-report"))
        assertTrue(report.contains("Orbis system exit metadata"))
        OrbisFaultDiagnostics.clear(context)
    }
}
