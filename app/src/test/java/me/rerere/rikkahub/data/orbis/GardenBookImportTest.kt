package me.rerere.rikkahub.data.orbis

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class GardenBookImportTest {
    @Test fun readsTwoHundredAndSevenHundredKiBInChineseLegacyEncoding() {
        for (count in listOf(40_000, 130_000)) {
            val text = "第一章\r\n" + "这是一本书。\n".repeat(count)
            assertEquals(text, GardenBookImport.read(text.toByteArray(Charset.forName("GB18030")).inputStream()))
        }
    }
    @Test fun readsLargeUtf8BeyondOldOneMiBLimit() {
        val text = "书中文字\n".repeat(250_000)
        assertTrue(text.toByteArray().size > 1024 * 1024)
        assertEquals(text, GardenBookImport.decode(text.toByteArray()))
    }
    @Test fun readsUtf8AndUtf16Bom() {
        val text = "第一章\r\n你好，星空。"
        assertEquals(text, GardenBookImport.decode(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray()))
        assertEquals(text, GardenBookImport.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, GardenBookImport.decode(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
    }
    @Test fun rejectsBinaryAndMalformedExplicitUtf8InsteadOfReplacingCharacters() {
        for (bytes in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte()))) {
            assertTrue(runCatching { GardenBookImport.decode(bytes) }.isFailure)
        }
    }
    @Test fun distinguishesEncodingFromCapacityAndEmptyErrors() {
        assertTrue(GardenBookImport.errorMessage(IllegalArgumentException("book_encoding")).contains("编码"))
        assertFalse(GardenBookImport.errorMessage(IllegalArgumentException("book_encoding")).contains("超"))
        assertTrue(GardenBookImport.errorMessage(IllegalArgumentException("book_too_large")).contains("32 MiB"))
        assertTrue(runCatching { GardenBookImport.decode(byteArrayOf()) }.exceptionOrNull()?.message == "book_empty")
    }
    @Test fun enforcesCapacityWhileStreaming() {
        val endless = object : java.io.InputStream() {
            override fun read() = 65
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(65, off, off + len); return len }
        }
        assertEquals("book_too_large", runCatching { GardenBookImport.read(endless) }.exceptionOrNull()?.message)
    }
}
