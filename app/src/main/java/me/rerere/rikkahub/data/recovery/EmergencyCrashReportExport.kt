package me.rerere.rikkahub.data.recovery

import me.rerere.rikkahub.utils.MAX_CRASH_REPORT_LENGTH
import java.io.InputStream
import java.io.OutputStream

/** An immutable copy of the already-saved report, never a new crash capture or a data backup. */
internal class EmergencyCrashReportExport private constructor(val text: String) {
    private val bytes = text.toByteArray(Charsets.UTF_8)

    fun writeTo(output: OutputStream) = output.write(bytes)

    /** A provider accepting a write is not enough: detect truncation, alterations and appended data. */
    fun verify(input: InputStream) {
        val buffer = ByteArray(4096)
        var offset = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && count <= bytes.size - offset) { "crash_report_readback_mismatch" }
            repeat(count) { index ->
                check(buffer[index] == bytes[offset + index]) { "crash_report_readback_mismatch" }
            }
            offset += count
        }
        check(offset == bytes.size) { "crash_report_readback_mismatch" }
    }

    companion object {
        const val MIME_TYPE = "text/plain"

        fun fromSaved(text: String?): EmergencyCrashReportExport? {
            if (text.isNullOrBlank()) return null
            // Do not silently re-truncate a stored report, especially its innermost cause.
            require(text.length <= MAX_CRASH_REPORT_LENGTH) { "crash_report_size_limit" }
            return EmergencyCrashReportExport(text)
        }
    }
}
