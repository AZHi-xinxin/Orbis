package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PrivateRoomPresentationSafetyTest {
    @Test fun longTextPagesKeepEveryCharacterWithoutSplittingSurrogate() {
        val original = "a".repeat(16_383) + "😀" + "正文".repeat(100_000)
        var offset = 0
        val collected = StringBuilder()
        while (offset < original.length) {
            val (text, next) = privateRoomTextPage(original, offset)
            assertTrue(text.length <= 16_384)
            assertFalse(text.last().isHighSurrogate())
            assertTrue(next > offset)
            collected.append(text); offset = next
        }
        assertEquals(original, collected.toString())
    }

    @Test fun exactReadbackRequiredForPrivateExport() {
        val saved = ByteArrayOutputStream()
        savePrivateRoomExport({ it.write(byteArrayOf(1, 2, 3)) }, { saved }, { ByteArrayInputStream(saved.toByteArray()) }, {})
        for (wrong in listOf(byteArrayOf(1, 2), byteArrayOf(1, 2, 4), byteArrayOf(1, 2, 3, 4))) {
            try {
                savePrivateRoomExport({ it.write(byteArrayOf(1, 2, 3)) }, { ByteArrayOutputStream() }, { ByteArrayInputStream(wrong) }, {})
                fail("mismatch must not report saved")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun cancellationDoesNotBecomeSuccessfulExport() {
        var checks = 0
        try {
            savePrivateRoomExport({ it.write(byteArrayOf(1)) }, { ByteArrayOutputStream() },
                { ByteArrayInputStream(byteArrayOf(1)) }, { if (++checks > 2) throw java.io.InterruptedIOException("cancel") })
            fail("cancel")
        } catch (_: java.io.InterruptedIOException) { }
    }
}
