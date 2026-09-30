package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.*
import org.junit.Test

class ChainOfThoughtCollapseTest {
    private data class Step(val id: String, val attention: Boolean = false)

    @Test fun defaultKeepsOnlyTheLastTwoSteps() {
        val original = (1..5).toList()
        val visible = collapsedChainOfThoughtSteps(original)
        assertEquals(listOf(4, 5), visible)
        assertEquals(3, original.size - visible.size)
        assertEquals((1..5).toList(), original)
    }

    @Test fun attentionBeforeTailStaysInOriginalOrderAndHiddenCountIsExact() {
        val original = (1..6).toList()
        val visible = collapsedChainOfThoughtSteps(original) { it == 1 || it == 3 }
        assertEquals(listOf(1, 3, 5, 6), visible)
        assertEquals(2, original.size - visible.size)
    }

    @Test fun attentionInsideTailIsNotDuplicated() {
        val original = (1..5).toList()
        assertEquals(listOf(2, 4, 5), collapsedChainOfThoughtSteps(original) { it == 2 || it == 4 })
    }

    @Test fun allAttentionMeansThereIsNothingToHide() {
        val original = (1..5).toList()
        val visible = collapsedChainOfThoughtSteps(original) { true }
        assertEquals(original, visible)
        assertEquals(0, original.size - visible.size)
    }

    @Test fun emptyAndShortGroupsHaveNoHiddenStepsOrPredicateWork() {
        for (size in 0..2) {
            val original = List(size) { it }
            val visible = collapsedChainOfThoughtSteps(original) { error("No hidden prefix") }
            assertSame(original, visible)
            assertEquals(0, original.size - visible.size)
        }
    }

    @Test fun oversizedTailCountKeepsTheOriginalList() {
        val original = listOf(1, 2, 3)
        assertSame(original, collapsedChainOfThoughtSteps(original, Int.MAX_VALUE) {
            error("No hidden prefix")
        })
    }

    @Test fun zeroOrNegativeTailCountStillRetainsAttention() {
        val original = listOf(1, 2, 3)
        for (tailCount in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(listOf(2), collapsedChainOfThoughtSteps(original, tailCount) { it == 2 })
            assertTrue(collapsedChainOfThoughtSteps(original, tailCount).isEmpty())
        }
    }

    @Test fun equalValuedDistinctStepsKeepTheirIdentityAndMultiplicity() {
        val first = Step("equal", attention = true)
        val second = Step("equal", attention = true)
        val hidden = Step("hidden")
        val tail = Step("tail")
        val original = listOf(first, second, hidden, tail)
        val visible = collapsedChainOfThoughtSteps(original, 1) { it.attention }
        assertEquals(3, visible.size)
        assertSame(first, visible[0])
        assertSame(second, visible[1])
        assertSame(tail, visible[2])
        assertEquals(listOf(first, second, hidden, tail), original)
    }

    @Test fun resolvingPendingAttentionRestoresTheOrdinaryCollapsedTail() {
        val pending = listOf(Step("approval", true), Step("old"), Step("recent"), Step("last"))
        assertEquals(listOf("approval", "recent", "last"),
            collapsedChainOfThoughtSteps(pending) { it.attention }.map { it.id })
        val resolved = pending.map { it.copy(attention = false) }
        assertEquals(listOf("recent", "last"),
            collapsedChainOfThoughtSteps(resolved) { it.attention }.map { it.id })
    }

    @Test fun newFailureOutsideTailBecomesVisibleOnTheNextSelection() {
        val before = listOf(Step("first"), Step("second"), Step("third"), Step("fourth"))
        assertEquals(listOf("third", "fourth"),
            collapsedChainOfThoughtSteps(before) { it.attention }.map { it.id })
        val after = before.map { if (it.id == "first") it.copy(attention = true) else it }
        assertEquals(listOf("first", "third", "fourth"),
            collapsedChainOfThoughtSteps(after) { it.attention }.map { it.id })
    }

    @Test fun streamingGrowthKeepsAttentionAndMovesOnlyTheVisibleTail() {
        val original = (0 until 20).map { Step("step-$it", attention = it == 0) }
        for (size in 1..original.size) {
            val prefix = original.take(size)
            val visible = collapsedChainOfThoughtSteps(prefix) { it.attention }
            val expected = prefix.filterIndexed { index, step -> index >= size - 2 || step.attention }
            assertEquals(expected, visible)
            expected.zip(visible).forEach { (expectedStep, visibleStep) -> assertSame(expectedStep, visibleStep) }
        }
    }

    @Test fun longListVisitsTheHiddenPrefixOnlyOnceInOriginalOrder() {
        val original = (0 until 100_000).toList()
        var expectedIndex = 0
        val visible = collapsedChainOfThoughtSteps(original) { value ->
            assertEquals(expectedIndex++, value)
            value % 10_000 == 0
        }
        assertEquals(original.size - 2, expectedIndex)
        assertEquals(listOf(0, 10_000, 20_000, 30_000, 40_000, 50_000, 60_000, 70_000,
            80_000, 90_000, 99_998, 99_999), visible)
        assertEquals(99_988, original.size - visible.size)
    }
}
