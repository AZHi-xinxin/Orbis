package me.rerere.rikkahub.data.orbis.history

import me.rerere.rikkahub.data.db.MAX_LEGACY_NODE_COMPARISON_BYTES
import me.rerere.rikkahub.data.db.measureEncodedMessageNode

/** Each text cell stays below 64 KiB even for non-ASCII input; never split a surrogate pair. */
internal fun historyPayloadChunks(json: String): List<String> {
    measureEncodedMessageNode(json, MAX_LEGACY_NODE_COMPARISON_BYTES)
    return buildList {
        var start = 0
        while (start < json.length) {
            var end = minOf(start + 16 * 1024, json.length)
            if (end < json.length && json[end - 1].isHighSurrogate() && json[end].isLowSurrogate()) end--
            add(json.substring(start, end))
            start = end
        }
    }
}

internal fun joinHistoryPayload(chunks: List<String>): String {
    check(chunks.size in 1..513 && chunks.all { it.length <= 16 * 1024 }) { "history_cache_payload_invalid" }
    val json = chunks.joinToString("")
    measureEncodedMessageNode(json, MAX_LEGACY_NODE_COMPARISON_BYTES)
    return json
}
