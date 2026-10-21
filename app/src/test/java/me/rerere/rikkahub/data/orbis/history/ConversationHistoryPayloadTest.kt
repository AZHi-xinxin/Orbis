package me.rerere.rikkahub.data.orbis.history

import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryPayloadTest {
    @Test fun asciiAndChineseFragmentsHaveBoundedCellsAndExactRoundTrip() {
        val json = "{\"text\":\"" + "synthetic 汉字".repeat(100_000) + "\"}"
        val chunks = historyPayloadChunks(json)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size < 64 * 1024 })
        assertEquals(json, joinHistoryPayload(chunks))
    }
    @Test fun surrogatePairAtBoundaryIsNotSplitAcrossSqliteTextCells() {
        val json = "a".repeat(16 * 1024 - 1) + "🐈" + "b".repeat(20_000)
        val chunks = historyPayloadChunks(json)
        assertFalse(chunks.first().last().isHighSurrogate())
        assertEquals(json, joinHistoryPayload(chunks))
    }
    @Test fun malformedFragmentShapeFailsClosed() {
        assertTrue(runCatching { joinHistoryPayload(emptyList()) }.isFailure)
        assertTrue(runCatching { joinHistoryPayload(listOf("a".repeat(16 * 1024 + 1))) }.isFailure)
        assertTrue(runCatching { joinHistoryPayload(List(514) { "a" }) }.isFailure)
    }
    @Test fun legacyBudgetStillBoundsReadAndWrite() {
        assertTrue(runCatching { historyPayloadChunks("a".repeat(8 * 1024 * 1024 + 1)) }.isFailure)
    }
}
