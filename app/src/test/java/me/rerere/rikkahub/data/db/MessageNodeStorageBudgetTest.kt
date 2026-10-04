package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNodeStorageBudgetTest {
    @Test fun exactUtf8CountIncludesChineseEmojiEscapesAndMalformedUnits() {
        for (text in listOf("ascii", "中文🙂", "\"\\\n\t", "a\uD83Db\uDC00", "\uD83D\uDE00")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size, measureEncodedMessageNode(text))
        }
    }

    @Test fun exactLimitAllowedButOneAdditionalByteRefused() {
        assertEquals(MessageNodeBudget.MAX_NODE_BYTES,
            measureEncodedMessageNode("a".repeat(MessageNodeBudget.MAX_NODE_BYTES)))
        assertTrue(runCatching { measureEncodedMessageNode("a".repeat(MessageNodeBudget.MAX_NODE_BYTES) + "a") }
            .exceptionOrNull() is MessageNodeCapacityException)
        assertTrue(runCatching { measureEncodedMessageNode("中", 2) }.exceptionOrNull() is MessageNodeCapacityException)
    }

    @Test fun LegacyComparisonDoesNotMakeLargeWritesLegal() {
        val old = "a".repeat(MessageNodeBudget.MAX_NODE_BYTES + 1)
        assertEquals(old.length, measureEncodedMessageNode(old, MAX_LEGACY_NODE_COMPARISON_BYTES))
        assertTrue(runCatching { measureEncodedMessageNode(old) }.exceptionOrNull() is MessageNodeCapacityException)
    }

    @Test fun legacyComparisonItselfHasAbsoluteAllocationBoundary() {
        assertTrue(runCatching { measureEncodedMessageNode("a".repeat(MAX_LEGACY_NODE_COMPARISON_BYTES + 1),
            MAX_LEGACY_NODE_COMPARISON_BYTES) }.exceptionOrNull() is MessageNodeCapacityException)
    }
}
