package me.rerere.rikkahub.data.ai.mcp

/** SDK 0.15 resets its retry counter on every HTTP 200, even an immediately empty SSE.
 * Bound rapid GET reconnects separately. No request bodies/credentials or event IDs are stored.
 * A long-lived stream naturally ages out of this window; only a burst latches the guard closed.
 */
internal class McpStreamReconnectGuard(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val starts = ArrayDeque<Long>()
    private var stopped = false

    @Synchronized fun beforeGet() {
        val time = now()
        while (starts.isNotEmpty() && time - starts.first() >= 60_000) starts.removeFirst()
        if (stopped || starts.size >= 8) {
            stopped = true
            throw IllegalStateException("mcp_sse_reconnect_exhausted")
        }
        starts.addLast(time)
    }
}
