package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class OrbisVoicePcmBufferTest {
    @Test fun `odd chunk boundaries preserve exact PCM without setting length limit`() {
        val pcm = ByteArray(2000) { (it % 251).toByte() }
        val buffer = OrbisVoicePcmBuffer(1)
        buffer.append(pcm.copyOfRange(0, 1), 8000)
        assertFalse(buffer.limitReached)
        buffer.append(pcm.copyOfRange(1, 778), 8000)
        assertFalse(buffer.limitReached)
        buffer.append(pcm.copyOfRange(778, pcm.size), 8000)
        val (wav, duration) = buffer.finish()
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(125L, duration)
        assertFalse(buffer.limitReached)
    }

    @Test fun `individual bytes combine into complete samples`() {
        val pcm = ByteArray(1600) { (it % 127).toByte() }
        val buffer = OrbisVoicePcmBuffer(1)
        pcm.forEach { buffer.append(byteArrayOf(it), 8000) }
        val (wav, duration) = buffer.finish()
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(100L, duration)
    }

    @Test fun `final half sample is rejected rather than silently removed or padded`() {
        val buffer = OrbisVoicePcmBuffer(1)
        buffer.append(ByteArray(1601), 8000)
        assertFalse(buffer.limitReached)
        assertThrows(IllegalArgumentException::class.java) { buffer.finish() }
    }

    @Test fun `cap is checked against total bytes and stops at an even sample boundary`() {
        val buffer = OrbisVoicePcmBuffer(1)
        val pcm = ByteArray(16_101) { (it % 251).toByte() }
        buffer.append(pcm.copyOfRange(0, 15_999), 8000)
        assertFalse(buffer.limitReached)
        buffer.append(pcm.copyOfRange(15_999, pcm.size), 8000)
        assertTrue(buffer.limitReached)
        buffer.append(byteArrayOf(55), 8000)
        val (wav, duration) = buffer.finish()
        assertArrayEquals(pcm.copyOf(16_000), wav.copyOfRange(44, wav.size))
        assertEquals(1000L, duration)
    }

    @Test fun `discard prevents late blocks and malformed timing is rejected`() {
        val buffer = OrbisVoicePcmBuffer()
        buffer.append(ByteArray(2000), 8000)
        buffer.discard()
        buffer.append(ByteArray(2000), 8000)
        assertThrows(IllegalArgumentException::class.java) { buffer.finish() }
        assertThrows(IllegalArgumentException::class.java) { OrbisVoicePcmBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { OrbisVoicePcmBuffer(Int.MAX_VALUE) }
        val changed = OrbisVoicePcmBuffer()
        changed.append(byteArrayOf(1), 8000)
        assertThrows(IllegalArgumentException::class.java) { changed.append(byteArrayOf(2), 16000) }
    }
}
