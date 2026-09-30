package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCompressionSafetyTest {
    @Test
    fun `Orbis debug refuses legacy compression with an explicit unchanged message`() {
        val result = runCatching { requireLegacyCompressionAvailable(isOrbisDebug = true) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(ORBIS_COMPRESSION_UNAVAILABLE_MESSAGE, result.exceptionOrNull()?.message)
        assertEquals("可撤销上下文整理尚未接通，本次未修改原文。", result.exceptionOrNull()?.message)
    }

    @Test
    fun `debug rejection never enters the legacy side effect body`() {
        var modelRequests = 0
        var historyWrites = 0
        var attachmentDeletes = 0

        val result = runCatching {
            requireLegacyCompressionAvailable(isOrbisDebug = true)
            modelRequests++
            historyWrites++
            attachmentDeletes++
        }

        assertTrue(result.isFailure)
        assertEquals(0, modelRequests)
        assertEquals(0, historyWrites)
        assertEquals(0, attachmentDeletes)
    }

    @Test
    fun `non debug guard leaves the legacy path available`() {
        var enteredLegacyBody = false

        val result = runCatching {
            requireLegacyCompressionAvailable(isOrbisDebug = false)
            enteredLegacyBody = true
        }

        assertTrue(result.isSuccess)
        assertTrue(enteredLegacyBody)
    }

    @Test
    fun `repeated debug attempts remain failures without a success state`() {
        repeat(3) {
            val result = runCatching { requireLegacyCompressionAvailable(isOrbisDebug = true) }
            assertFalse(result.isSuccess)
            assertEquals(ORBIS_COMPRESSION_UNAVAILABLE_MESSAGE, result.exceptionOrNull()?.message)
        }
    }
}
