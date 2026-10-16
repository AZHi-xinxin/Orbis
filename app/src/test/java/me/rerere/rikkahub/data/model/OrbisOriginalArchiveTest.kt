package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class OrbisOriginalArchiveTest {
    @Test fun defaultsOffForNewAndOldSettings() {
        assertFalse(Assistant().retainCompactionOriginals)
        assertFalse(me.rerere.rikkahub.utils.JsonInstant.decodeFromString<Assistant>("{}").retainCompactionOriginals)
    }
    @Test fun largeUnicodeMessagesRoundTripWithinBoundedRows() {
        val source = "x".repeat(15999) + "🙂汉字".repeat(500_000)
        val chunks = originalArchiveChunks(source)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.size > 100)
        assertTrue(chunks.all { it.length <= 16000 && it.toByteArray(Charsets.UTF_8).size <= 64000 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }
}
