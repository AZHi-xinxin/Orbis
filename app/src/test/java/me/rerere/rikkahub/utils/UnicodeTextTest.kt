package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class UnicodeTextTest {
    @Test fun `old tool preview splits emoji exactly at 4096 UTF16 units`() {
        val prefix = "文".repeat(4095)
        val output = prefix + "🙂" + " remaining output".repeat(4096)
        val oldPreview = output.take(4096)
        assertEquals(4096, oldPreview.length)
        assertTrue(oldPreview.last().isHighSurrogate())
        assertEquals(prefix, output.takeAtCodePointBoundary(4096))
    }

    @Test fun `complete emoji and adjacent ordinary text are kept byte for byte`() {
        for (prefixLength in listOf(4093, 4094, 4096, 4097)) {
            val output = "中".repeat(prefixLength) + "🙂suffix"
            assertEquals(output.take(4096), output.takeAtCodePointBoundary(4096))
        }
        assertEquals("ab🙂", "ab🙂z".takeAtCodePointBoundary(4))
        assertEquals("ab", "ab🙂z".takeAtCodePointBoundary(3))
    }

    @Test fun `empty short BMP and exact boundary keep the old size semantics`() {
        for (text in listOf("", "abc", "中文", "🙂", "e\u0301")) {
            assertEquals("", text.takeAtCodePointBoundary(0))
            assertEquals(text, text.takeAtCodePointBoundary(text.length))
            assertEquals(text, text.takeAtCodePointBoundary(text.length + 1))
        }
        assertEquals("ab", "abc".takeAtCodePointBoundary(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative limits are still rejected`() {
        "text".takeAtCodePointBoundary(-1)
    }
}
