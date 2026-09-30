package me.rerere.rikkahub.ui.components.ui

/**
 * Display-only selection. Keep the tail plus attention-requiring steps in their original
 * positions; do not concatenate lists or deduplicate equal-valued steps. The result never
 * alters the source list, and the number actually hidden is [steps].size minus result.size.
 */
internal fun <T> collapsedChainOfThoughtSteps(
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    retainWhenCollapsed: (T) -> Boolean = { false },
): List<T> {
    val tailStart = (steps.size - collapsedVisibleCount.coerceAtLeast(0)).coerceAtLeast(0)
    if (tailStart == 0) return steps
    return steps.filterIndexed { index, step ->
        index >= tailStart || retainWhenCollapsed(step)
    }
}
