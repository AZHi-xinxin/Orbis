package me.rerere.ai.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageMergeTest {
    private val previous = TokenUsage(promptTokens = 200000, completionTokens = 100, cachedTokens = 83584)

    @Test fun explicitZeroReplacesOldCache() {
        val result = previous.merge(TokenUsage(cachedTokens = 0, cachedTokensReported = true))
        assertEquals(0, result.cachedTokens)
        assertTrue(result.cachedTokensReported)
        assertEquals(previous.promptTokens, result.promptTokens)
    }

    @Test fun freshInputSnapshotWithoutCacheDoesNotBorrowPreviousHit() {
        val result = previous.merge(TokenUsage(promptTokens = 210000, completionTokens = 20))
        assertEquals(0, result.cachedTokens)
        assertFalse(result.cachedTokensReported)
        assertEquals(210020, result.totalTokens)
    }

    @Test fun outputOnlyDeltaRetainsSameRequestCache() {
        val result = previous.merge(TokenUsage(completionTokens = 200))
        assertEquals(83584, result.cachedTokens)
        assertTrue(result.cachedTokensReported)
        assertEquals(200200, result.totalTokens)
    }

    @Test fun emptyDeltaDoesNotEraseCache() {
        assertEquals(83584, previous.merge(TokenUsage()).cachedTokens)
    }

    @Test fun cacheOnlyPositiveSnapshotStillWorks() {
        assertEquals(20, previous.merge(TokenUsage(cachedTokens = 20)).cachedTokens)
    }

    @Test fun absentBaseAcceptsExplicitZero() {
        val result = (null as TokenUsage?).merge(TokenUsage(cachedTokensReported = true))
        assertEquals(0, result.cachedTokens)
        assertTrue(result.cachedTokensReported)
    }

    @Test fun oldJsonRemainsReadableAndNewPresenceRoundTrips() {
        val old = Json.decodeFromString<TokenUsage>("""{"promptTokens":200,"cachedTokens":100}""")
        assertEquals(100, old.merge(TokenUsage(completionTokens = 10)).cachedTokens)
        val explicitZero = TokenUsage(cachedTokensReported = true)
        assertEquals(explicitZero, Json.decodeFromString<TokenUsage>(Json.encodeToString(TokenUsage.serializer(), explicitZero)))
    }

    @Test fun legacyEncodedUsageBytesStayIdenticalForRecoveryChecksums() {
        val codec = Json { encodeDefaults = true }
        listOf(
            """{"promptTokens":200,"completionTokens":10,"cachedTokens":100,"totalTokens":210}""",
            """{"promptTokens":200,"completionTokens":10,"cachedTokens":0,"totalTokens":210}""",
        ).forEach { original ->
            val decoded = codec.decodeFromString<TokenUsage>(original)
            assertEquals(original, codec.encodeToString(TokenUsage.serializer(), decoded))
        }
    }
}
