package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Keep UI content and controls, not the layout wrappers that happen to precede them. */
internal object PhonePageProjection {
    const val MAX_BYTES = 20 * 1024

    private fun meaningful(node: PhoneElement) = node.text.isNotBlank() || node.description.isNotBlank() ||
        node.clickable || node.editable || node.scrollable || node.checked || node.selected

    private fun priority(node: PhoneElement) = when {
        node.editable -> 0
        node.enabled && (node.clickable || node.scrollable) -> 1
        else -> 2
    }

    fun project(page: PhonePage, maxBytes: Int = MAX_BYTES): PhonePage {
        val candidates = page.elements.filter(::meaningful)
        // Content outside the returned budget must still invalidate a stale action lease.
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(page.elements).toByteArray()).joinToString("") { "%02x".format(it) }
        fun result(nodes: List<PhoneElement>, omitted: Boolean) = page.copy(
            elements = nodes.mapIndexed { index, element -> element.copy(id = index) },
            truncated = page.truncated || omitted,
            snapshotHash = hash,
        )
        val full = result(candidates, false)
        if (Json.encodeToString(full).toByteArray().size <= maxBytes) return full

        // Account exactly for UTF-8 and JSON escaping. Reserve worst-case ID width so that
        // restoring visual order and renumbering cannot put the page over budget.
        val envelope = Json.encodeToString(result(emptyList(), true)).toByteArray().size
        var bytes = envelope
        val selected = mutableSetOf<Int>()
        candidates.indices.sortedWith(compareBy<Int> { priority(candidates[it]) }.thenBy { it }).forEach { index ->
            val size = Json.encodeToString(candidates[index].copy(id = candidates.lastIndex)).toByteArray().size
            val comma = if (selected.isEmpty()) 0 else 1
            if (bytes + size + comma <= maxBytes) {
                selected += index
                bytes += size + comma
            }
        }
        // Original paths remain intact: IDs are only references into this exact leased page.
        return result(candidates.filterIndexed { index, _ -> index in selected }, selected.size < candidates.size)
    }
}
