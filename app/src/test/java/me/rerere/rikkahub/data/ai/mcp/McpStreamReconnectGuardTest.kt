package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.*
import org.junit.Test

class McpStreamReconnectGuardTest {
    @Test fun `rapid stream churn latches until a new explicit connection`() {
        var time = 0L
        val guard = McpStreamReconnectGuard { time }
        repeat(8) { guard.beforeGet(); time += 1_000 }
        assertThrows(IllegalStateException::class.java) { guard.beforeGet() }
        time += 120_000
        assertThrows(IllegalStateException::class.java) { guard.beforeGet() }
        McpStreamReconnectGuard { time }.beforeGet()
    }
    @Test fun `long lived healthy streams may reconnect repeatedly`() {
        var time = 0L
        val guard = McpStreamReconnectGuard { time }
        repeat(40) { guard.beforeGet(); time += 60_000 }
    }
}
