package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage

/** A prior invalidation must not hide a second reduction of provider-visible content. */
internal fun shouldResetProjectedUsageEstimate(source: List<UIMessage>, projected: List<UIMessage>): Boolean {
    if (projected.none { it.usageContextInvalidated }) return false
    val original = source.associateBy { it.id }
    if (projected.any { item -> item.usageContextInvalidated &&
            (original[item.id]?.usageContextInvalidated != true || original[item.id]?.parts != item.parts) }) return true
    val retained = projected.map { it.id }.toSet()
    return source.any { it.id !in retained }
}
