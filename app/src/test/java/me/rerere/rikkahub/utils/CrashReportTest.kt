package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class CrashReportTest {
    private fun report(stack: String, device: String = "synthetic device") = formatCrashReport(
        "main", stack, "2.6.3", "236", "release", 35, device, "2026-10-03T16:00:00Z",
    )

    @Test fun `includes actionable build and runtime metadata`() {
        val text = report("ComposeRuntimeError: pending composition has not been applied")
        assertTrue(text.contains("2.6.3 (236), release"))
        assertTrue(text.contains("Android SDK 35"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("pending composition has not been applied"))
    }

    @Test fun `preserves short stack exactly`() {
        val stack = "first\n at sample.Main.run(Main.kt:1)\nCaused by: nested"
        assertTrue(report(stack).endsWith(stack))
        assertFalse(report(stack).contains("Report truncated"))
    }

    @Test fun `bounded report retains first error and innermost cause`() {
        val text = report("FIRST_ERROR\n" + "synthetic frame\n".repeat(10_000) + "FINAL_CAUSE")
        assertEquals(MAX_CRASH_REPORT_LENGTH, text.length)
        assertTrue(text.contains("FIRST_ERROR"))
        assertTrue(text.contains("Report truncated"))
        assertTrue(text.endsWith("FINAL_CAUSE"))
    }

    @Test fun `metadata cannot inject extra report lines`() {
        val text = report("STACK", "device\nspoofed\rmetadata" + "x".repeat(300))
        assertTrue(text.contains("device spoofed metadata"))
        assertFalse(text.contains("\nspoofed"))
        assertFalse(text.contains("x".repeat(161)))
    }
}
