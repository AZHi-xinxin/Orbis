package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.orbis.diagnostics.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FaultDiagnosticsDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    @Test fun humanCanStartStopAndKeepReportWithoutAiOrDeveloperOptions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.orbis.agent.bodylab")
        OrbisFaultDiagnostics.clear(context)
        try {
            compose.setContent {MaterialTheme {FaultDiagnosticsButton()}}
            compose.onNodeWithTag("fault-diagnostics-open").performClick()
            assertFalse(OrbisFaultDiagnostics.active(context))
            compose.onNodeWithTag("fault-diagnostics-toggle").performScrollTo().performClick()
            compose.waitUntil(10000){OrbisFaultDiagnostics.active(context)}
            OrbisFaultDiagnostics.record(context,FaultStage.PREPARE_FAILED,IllegalStateException("private-key-never-export"))
            compose.onNodeWithTag("fault-diagnostics-toggle").performScrollTo().performClick()
            compose.waitUntil(10000){!OrbisFaultDiagnostics.active(context)}
            val report=OrbisFaultDiagnostics.report(context)
            assertTrue(report.contains("PREPARE_FAILED"));assertTrue(report.contains("IllegalStateException"))
            assertFalse(report.contains("private-key-never-export"))
            assertTrue(report.contains("Orbis system exit metadata"))
            compose.onNodeWithTag("fault-diagnostics-share").performScrollTo().assertIsEnabled()
        } finally {OrbisFaultDiagnostics.clear(context)}
    }
}
