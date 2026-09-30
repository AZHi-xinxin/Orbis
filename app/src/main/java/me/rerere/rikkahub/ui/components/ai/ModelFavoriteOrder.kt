package me.rerere.rikkahub.ui.components.ai

import kotlin.uuid.Uuid

/** Reorder only the visible favorites, keeping every other connection's slots unchanged. */
internal fun reorderVisibleModelFavorites(
    currentFavorites: List<Uuid>,
    visibleIds: Set<Uuid>,
    fromId: Uuid,
    toId: Uuid,
): List<Uuid> {
    val visibleFavorites = currentFavorites.filter { it in visibleIds }.toMutableList()
    val fromIndex = visibleFavorites.indexOf(fromId)
    val toIndex = visibleFavorites.indexOf(toId)
    // A favorite may have been removed after the drag started.
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return currentFavorites
    visibleFavorites.add(toIndex, visibleFavorites.removeAt(fromIndex))
    val reordered = visibleFavorites.iterator()
    return currentFavorites.map { if (it in visibleIds) reordered.next() else it }
}
