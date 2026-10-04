package me.rerere.rikkahub.data.db

import me.rerere.ai.ui.UIMessage

/**
 * Use the same UTF-8 encoding boundary as the generation journal before SQLite binds UTF-16.
 * A truncated/incoming string may contain a lone surrogate: handing it directly to a native
 * SQLite binder can consume the following JSON delimiter. The streaming encoder replaces only
 * invalid Unicode units, as it already does in checkpoints; valid text and JSON remain identical.
 * This applies to new writes only and never repairs, truncates, or rewrites stored history.
 */
internal fun encodeMessageNodeMessages(messages: List<UIMessage>): String {
    return MessageNodeBudget.encodeNode(messages)
}

/** Only for a scalar SQL equality proof against an existing row; never permits a large write. */
internal const val MAX_LEGACY_NODE_COMPARISON_BYTES = 8 * 1024 * 1024

/** Count the actual UTF-8 binding size without allocating another copy of an already-built JSON. */
internal fun measureEncodedMessageNode(json: String, limit: Int = MessageNodeBudget.MAX_NODE_BYTES): Int {
    var bytes = 0
    var index = 0
    while (index < json.length) {
        val unit = json[index++]
        bytes += when {
            unit.code < 0x80 -> 1
            unit.code < 0x800 -> 2
            unit.isHighSurrogate() && index < json.length && json[index].isLowSurrogate() -> {
                index++
                4
            }
            unit.isSurrogate() -> 1 // UTF-8 encoder's replacement for an isolated UTF-16 unit.
            else -> 3
        }
        if (bytes > limit) throw MessageNodeCapacityException("message_node_storage_limit")
    }
    return bytes
}
