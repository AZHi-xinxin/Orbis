package me.rerere.rikkahub.data.orbis.history

import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryOrderTest {
    @Test fun deletionTombstonesRetainOriginalOrderAcrossNewReplies() {
        assertEquals(listOf("a", "deleted", "b", "new"),
            mergeHistoryOrder(listOf("a", "deleted", "b"), listOf("a", "b", "new")))
    }
    @Test fun missingWholeTailStaysBeforeFutureTurns() {
        assertEquals(listOf("a", "deleted1", "deleted2", "new"),
            mergeHistoryOrder(listOf("a", "deleted1", "deleted2"), listOf("a", "new")))
    }
    @Test fun newMiddleBranchAndDeletedNeighborsRemainOrdered() {
        assertEquals(listOf("a", "deleted", "inserted", "b"),
            mergeHistoryOrder(listOf("a", "deleted", "b"), listOf("a", "inserted", "b")))
    }
    @Test fun compactCutsBeforeFirstRetainedOriginalIncludingLeftAdjacentDeletedTurn() {
        assertEquals(listOf("b", "deletedLater", "c"),
            retainedHistoryOrder(listOf("a", "deletedEarlier", "b", "deletedLater", "c"), setOf("b", "c")))
    }
    @Test fun noOriginalRetainedClearsAllDeletedCacheEvenIfNewSummaryExists() {
        assertTrue(retainedHistoryOrder(listOf("a", "removed"), setOf("new-summary")).isEmpty())
    }
    @Test fun unrelatedOrReorderedIdentitiesCannotInheritPreviousOrdering() {
        assertNull(mergeHistoryOrder(listOf("a", "removed", "b"), listOf("b", "a")))
        assertNull(mergeHistoryOrder(listOf("a", "a"), listOf("a")))
        assertNull(mergeHistoryOrder(emptyList(), listOf("a", "a")))
    }
    @Test fun clearThenNewChatKeepsDeletedHistoryOnlyUntilNextCompaction() {
        val order = mergeHistoryOrder(listOf("old1", "old2"), listOf("new1", "new2"))!!
        assertEquals(listOf("new1", "new2"), retainedHistoryOrder(order, setOf("new1", "new2")))
    }
    @Test fun middleArchiveDropsItsFollowingTombstoneGapWithoutTouchingLaterGap() {
        assertEquals(listOf("a", "b", "deletedB", "d", "deletedD"), retainedHistoryOrder(
            listOf("a", "b", "deletedB", "c", "deletedC", "d", "deletedD"),
            setOf("a", "b", "c", "d"), setOf("a", "b", "d")))
    }
    @Test fun currentLiveBranchCacheFollowsItsOwnNodeNotNeighbor() {
        assertEquals(listOf("b"), retainedHistoryOrder(listOf("a", "removedA", "b"),
            setOf("a", "b"), setOf("b")))
    }
}
