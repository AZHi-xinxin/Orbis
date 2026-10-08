package me.rerere.rikkahub.data.orbis.memory

import org.junit.Assert.*
import org.junit.Test

class OrbisMemoryBudgetTest {
    @Test fun fullPinTextUsesNonblankSummaryOtherwiseOriginal() {
        assertEquals("complete summary", OrbisMemoryBudget.pinText("complete summary", "original"))
        assertEquals("complete original", OrbisMemoryBudget.pinText(" \n", "complete original"))
    }

    @Test fun originalCharacterGateCountsCodepointsNotUtf16OrTokens() {
        assertNull(OrbisMemoryBudget.pinEligibilityError("", "😀".repeat(300)))
        assertEquals("memory_pin_summary_required", OrbisMemoryBudget.pinEligibilityError("", "a".repeat(301)))
        assertTrue(OrbisMemoryBudget.pinsFit(listOf("a".repeat(301))))
        assertNull(OrbisMemoryBudget.pinEligibilityError("summary", "x".repeat(10000)))
    }

    @Test fun summaryHasNoIndependent300CharacterLimit() {
        val summary = "s".repeat(1000)
        assertNull(OrbisMemoryBudget.pinEligibilityError(summary, "long".repeat(1000)))
        assertTrue(OrbisMemoryBudget.pinsFit(listOf(summary)))
        assertEquals(summary, OrbisMemoryBudget.pinText(summary, "original"))
    }

    @Test fun countBudgetAndRenderedOverheadAreIncludedWithoutTruncation() {
        val texts = List(7) { "note $it" }
        assertTrue(OrbisMemoryBudget.pinsFit(texts))
        assertFalse(OrbisMemoryBudget.pinsFit(texts + "eighth"))
        assertEquals(OrbisMemoryBudget.estimatedTokens(OrbisMemoryBudget.renderPinned(texts)),
            OrbisMemoryBudget.pinnedTokens(texts))
        assertFalse(OrbisMemoryBudget.pinsFit(listOf("界".repeat(350))))
        assertTrue(OrbisMemoryBudget.renderPinned(listOf("界".repeat(350))).contains("界".repeat(350)))
    }

    @Test fun emptyPinSetHasNoEnvelopeCost() {
        assertEquals("", OrbisMemoryBudget.renderPinned(emptyList()))
        assertEquals(0L, OrbisMemoryBudget.pinnedTokens(emptyList()))
    }

    @Test fun capacityIsUtf8BytesAndOnlyRefusesRatherThanTruncating() {
        val exact = "x".repeat(OrbisMemoryBudget.MAX_BODY_BYTES)
        assertNull(OrbisMemoryBudget.compactionCapacityError(exact))
        assertEquals("memory_capacity_exceeded", OrbisMemoryBudget.compactionCapacityError(exact + "x"))
        assertEquals("memory_capacity_exceeded", OrbisMemoryBudget.compactionCapacityError("界".repeat(200000)))
        assertEquals("memory_body_required", OrbisMemoryBudget.compactionCapacityError(" \n"))
    }
}
