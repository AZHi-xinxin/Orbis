package me.rerere.rikkahub.data.orbis.history

/** Merge current node order with deleted-node tombstones; identities, never timestamps, own a turn. */
internal fun mergeHistoryOrder(previous: List<String>, current: List<String>): List<String>? {
    if (previous.distinct().size != previous.size || current.distinct().size != current.size) return null
    val oldIds = previous.toHashSet()
    val currentIds = current.toHashSet()
    val common = current.filter { it in oldIds }
    if (previous.filter { it in currentIds } != common) return null
    val result = ArrayList<String>(previous.size + current.size)
    var old = 0
    var next = 0
    for (anchor in common) {
        while (previous[old] != anchor) result += previous[old++]
        while (current[next] != anchor) result += current[next++]
        result += anchor
        old++
        next++
    }
    while (old < previous.size) result += previous[old++]
    while (next < current.size) result += current[next++]
    return result
}

/** Deleted nodes before the first retained ORIGINAL node leave with that compressed prefix. */
internal fun retainedHistoryOrder(order: List<String>, retainedOriginalIds: Set<String>): List<String> {
    val boundary = order.indexOfFirst { it in retainedOriginalIds }
    return if (boundary < 0) emptyList() else order.drop(boundary)
}

/** Also covers non-prefix manual archives: tombstone gaps belong to their preceding original node. */
internal fun retainedHistoryOrder(
    order: List<String>, originalIds: Set<String>, retainedOriginalIds: Set<String>,
): List<String> {
    var predecessor: String? = null
    return order.filter { node ->
        if (node in originalIds) predecessor = node
        if (node in originalIds) node in retainedOriginalIds else predecessor in retainedOriginalIds
    }
}
