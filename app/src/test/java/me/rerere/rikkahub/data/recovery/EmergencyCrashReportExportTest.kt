package me.rerere.rikkahub.data.recovery

import me.rerere.rikkahub.utils.MAX_CRASH_REPORT_LENGTH
import me.rerere.rikkahub.utils.formatCrashReport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream

class EmergencyCrashReportExportTest {
    @Test fun `missing report is not fabricated`() {
        assertNull(EmergencyCrashReportExport.fromSaved(null))
        assertNull(EmergencyCrashReportExport.fromSaved(" \n\t"))
    }

    @Test fun `saved metadata and unicode are exported exactly as UTF8 text`() {
        val original = "Orbis 2.6.3 (236), release\nAndroid SDK 36\nThread: main\n错误 🛠\nCaused by: original"
        val report = checkNotNull(EmergencyCrashReportExport.fromSaved(original))
        val output = ByteArrayOutputStream()
        report.writeTo(output)
        assertEquals("text/plain", EmergencyCrashReportExport.MIME_TYPE)
        assertEquals(original, report.text)
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), output.toByteArray())
        report.verify(ByteArrayInputStream(output.toByteArray()))
    }

    @Test fun `formatted report retains both existing metadata and innermost cause`() {
        val saved = formatCrashReport("main", "FIRST\n" + "frame\n".repeat(10_000) + "FINAL_CAUSE",
            "2.6.3", "236", "release", 36, "synthetic device", "2026-10-04T01:15:18+08:00")
        val report = checkNotNull(EmergencyCrashReportExport.fromSaved(saved))
        val output = ByteArrayOutputStream().also(report::writeTo).toByteArray()
        assertEquals(MAX_CRASH_REPORT_LENGTH, report.text.length)
        assertEquals(saved, output.toString(Charsets.UTF_8))
        assertTrue(report.text.contains("first and last sections retained"))
        assertTrue(report.text.endsWith("FINAL_CAUSE"))
        report.verify(ByteArrayInputStream(output))
    }

    @Test fun `readback may arrive in small chunks`() {
        val report = checkNotNull(EmergencyCrashReportExport.fromSaved("saved中文\n".repeat(300)))
        val bytes = report.text.toByteArray(Charsets.UTF_8)
        val chunks = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        report.verify(chunks)
    }

    @Test fun `partial altered or appended reports cannot be called verified`() {
        val report = checkNotNull(EmergencyCrashReportExport.fromSaved("saved-error"))
        listOf("", "saved-erro", "saved-other", "saved-error-extra").forEach { readback ->
            assertThrows(IllegalStateException::class.java) { report.verify(readback.byteInputStream()) }
        }
    }

    @Test fun `oversized saved state is refused rather than truncated`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmergencyCrashReportExport.fromSaved("x".repeat(MAX_CRASH_REPORT_LENGTH + 1))
        }
    }
}
