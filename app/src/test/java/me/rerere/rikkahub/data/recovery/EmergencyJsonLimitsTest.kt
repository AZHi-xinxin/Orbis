package me.rerere.rikkahub.data.recovery

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EmergencyJsonLimitsTest {
    @Test fun deepArraysAndObjectsAreRejectedWithoutRecursiveParsing() {
        listOf("[".repeat(10_000) + "0" + "]".repeat(10_000),
            "{\"nested\":".repeat(10_000) + "0" + "}".repeat(10_000)).forEach { text ->
            assertThrows(IOException::class.java) { requireEmergencyJsonShape(text) }
            assertThrows(IOException::class.java) {
                EmergencyJsonInput(ByteArrayInputStream(text.toByteArray()), 1_000_000).readBytes()
            }
        }
        requireEmergencyJsonShape("[".repeat(32) + "0" + "]".repeat(32))
        assertThrows(IOException::class.java) { requireEmergencyJsonShape("[".repeat(33) + "0" + "]".repeat(33)) }
    }

    @Test fun bracketsAndEscapedQuotesInsideTextAreNotNesting() {
        val text = Json.encodeToString(mapOf("value" to ("[]{}\\\"中文".repeat(400))))
        requireEmergencyJsonShape(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val input = EmergencyJsonInput(ByteArrayInputStream(bytes), bytes.size.toLong())
        val actual = mutableListOf<Byte>()
        // One byte per read crosses every quote, escape and multi-byte Unicode boundary.
        while (true) {
            val value = input.read()
            if (value < 0) break
            actual += value.toByte()
        }
        assertArrayEquals(bytes, actual.toByteArray())
    }

    @Test fun countAndMalformedNestingAreBounded() {
        assertThrows(IOException::class.java) {
            requireEmergencyJsonShape("[" + List(100_001) { "0" }.joinToString(",") + "]")
        }
        listOf("{]", "}", "[}").forEach { text ->
            assertThrows(IOException::class.java) { requireEmergencyJsonShape(text) }
        }
    }

    @Test fun streamByteLimitCannotBeBypassedByChunkOrSingleByteReads() {
        listOf(false, true).forEach { single ->
            val input = EmergencyJsonInput(ByteArrayInputStream("[0,1]".toByteArray()), 4)
            assertThrows(IOException::class.java) {
                if (single) while (input.read() >= 0) Unit else input.readBytes()
            }
        }
        val exact = "[0,1]".toByteArray()
        assertArrayEquals(exact, EmergencyJsonInput(ByteArrayInputStream(exact), exact.size.toLong()).readBytes())
    }
}
